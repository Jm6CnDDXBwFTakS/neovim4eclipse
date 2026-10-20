# Design note: `:e newfile` opens an empty editor that `:w` creates

Status: **implemented** (four commits after `9fe4804`). Planned against `develop` at `64c6993`. The
experiments used Neovim 0.12.4 on Windows 11, and the plug-in's UI test runtime. Decisions made with
the maintainer are marked *(decided)*. The plan below is kept as written; *As built* says where the
code differs from it and why.

## Summary

In Vim, `:e new.txt` for a file that does not exist opens an empty buffer named `new.txt` and shows
`"new.txt" [New]`. Nothing is created on disk until `:w`. The plug-in instead answers
`Can't find file "new.txt"`: `EclipseFileCommands.edit` only opens files that exist.

**Plan:**

- **`:e` opens Eclipse's own editor on the missing file.** It opens fine, empty, with Neovim
  attached, and nothing is written (section 2).
- **`:w` (and `:wq`, `:x`, `ZZ`, `:wa`) creates the file.** Eclipse would otherwise ask Save As,
  because it treats a missing file like a deleted one.
- **The editor is chosen by the file's name.** Eclipse's default lookup picks the system's external
  editor for a missing file, which would launch Notepad or a similar program.

Size: about 250 lines of code and 350 of tests, in four commits (section 7).

## As built

The code follows the plan, with these differences:

- **The opening and the creating write are in `editor/NewFiles`**, not in `EclipseFileCommands`,
  which only calls it (`NewFiles.open`, `NewFiles.save`, `NewFiles.missingFileOf`). The written
  message is `util/WrittenMessage`.
- **`:wq` and `:x` are told apart.** The parser gave both the same kind, but only `:wq` writes an
  untouched new editor (section 1). `HostExCommand` gained `writesUnchanged()`, true for `:w` and
  `:wq`, false for `:update`, `:x`, `:exit`, and `Ide.save` takes it next to `createFolders`.
- **A failed write keeps `ZZ` from closing.** Before, `ZZ` closed the editor even when the save
  failed. Now it closes only after a successful write, as in Vim, so `ZZ` on a new file in a missing
  folder shows E212 and keeps the text. This also applies to existing files (a read-only file).
- **`:wa` when a new file cannot be written:** Eclipse's Save All would offer Save As for it, so the
  other modified editors are then saved one by one instead, and `:wa` reports failure.
- **An untouched editor's `:w`** creates the empty file, then asks the document provider to
  synchronize, so Eclipse does not report the file as changed on disk. The UI test switches editors
  after it and sees no dialog (section 8, second point).
- **Only outside files opened as `FileStoreEditorInput` count as new files**, the kind `:e` opens.
  Other inputs without a file, such as an untitled document (File › New › Untitled Text File), keep
  Eclipse's Save As on `:w`, as before.
- **The editor choice** also rejects in-place (OLE) editors, not only external ones:
  `IEditorDescriptor.isInternal()`.
- **The first key** (section 5): the cause is the text editor's one-time `Validator`, a
  `VerifyListener` that runs `validateEditorInputState()` on the first typed change and drops the
  key if the check fails or changes the document or input on the way. For a missing workspace file
  it does (which of the two was not traced). The controller now runs the check when it attaches to
  an editor whose workspace file does not exist (`NeovimEditorController.validateNewWorkspaceFile`).
  Existing files keep Eclipse's timing, because their check may ask the user. The bug only showed
  when Eclipse types the text; keys typed before insert mode reached Eclipse go to Neovim and were
  never lost, which is why typing `ihello` in one go in a test did not reproduce it.
- **The names in the messages:** `[New]` on opening shows the name as typed (`"sub/new.txt" [New]`);
  the written message shows the file name (`"new.txt" [New] 1L, 6B written`).
- **Not tested:** `:w ++p` for a file outside the workspace in a missing folder (it takes the same
  path as a workspace file, with `Files.createDirectories`), and the encoding of the byte count for
  a workspace file with a non-default charset.

## 1. Behavior

- **`:e name` where no file of that name is found** in the folders `:e` searches today (the current
  file's folder, then its project's root) opens an empty editor for `name` *in the current file's
  folder* *(decided)*. Absolute and `~` paths are used as typed. The status line shows
  `"name" [New]`, as Vim does.
- **`./` and `../` mean the current file's folder only** *(decided)*. `:e ./Foo.txt` creates
  `Foo.txt` beside the current file even when the project root has a `Foo.txt`; plain
  `:e Foo.txt` still finds the root one. This changes `:e ./name` for existing files too: today
  it falls back to the project root. This is the plug-in's rule, not Vim's: in Vim, `./Foo.txt`
  and `Foo.txt` are the same, but Vim has no two-folder search to opt out of. As in a shell,
  `./script` runs the file here, while `script` searches `PATH`.
- **`../` is not limited to the project.** From `src/Main.java`, `../x` is the project root's `x`
  and `../../x` is in the folder above the project. Eclipse opens files anywhere, as outside
  files without project features. The rule only drops today's hidden second try, where `../x` is
  also resolved from the project root, which silently meant the folder above the project.
- **After the write that creates the file,** the status line shows Vim's message *(decided)*:
  `"new.txt" [New] 1L, 6B written` (lines and bytes of what was written).
- **Nothing is on disk until a write** *(decided)*. Closing the editor without writing (`:q!`, `ZQ`,
  the tab's close button) leaves nothing behind.
- **`:w`, `:wq`, `:x`, `ZZ` and `:wa` create the file** and then save as usual *(decided)*. As in
  Vim, `:w` writes even when nothing was typed (an empty file), while `:x` and `ZZ` write only
  when there are changes.
- **`Ctrl+S` and File › Save keep Eclipse's Save As dialog** *(decided)*. The plug-in does not take
  over Eclipse's save command.
- **A missing folder** (`:e sub/new.txt`) *(decided)*:
  - `:e` opens the empty editor as for any new file;
  - `:w` fails with Vim's message, plus a hint:
    `E212: Can't open file for writing: no such file or directory (:w ++p creates it)`;
  - `:w ++p` creates the missing folders, then the file, as in Neovim (`:help ++p`). So do
    `:wq ++p` and `:x ++p`. `ZZ` has no `++p`: it shows the same error.
- **Untitled editors** (File › New › Untitled Text File) have no folder, so a relative name there
  keeps today's error, `Can't find file "name"`. An absolute path works.
- **A file that was deleted while its editor was open** is recreated by `:w`. That matches Vim, and
  falls out of the design: the rule is "the editor's file does not exist", not "the editor was
  opened as new".

## 2. The experiment

A throwaway UI test (since deleted) opened editors on missing files in the test runtime, typed
`ihello<Esc>`, ran `:w`, and recorded each step. Results:

| Step | Workspace file | File outside the workspace |
|---|---|---|
| Default editor, `IDE.getEditorDescriptor(file)` / `getEditorDescriptorForFileStore` | `systemExternalEditor` | `systemExternalEditor` |
| Default editor for the same name when the file exists | `DefaultTextEditor` | `DefaultTextEditor` |
| Default editor by name only, `IDE.getEditorDescriptor("new.txt", true, false)` | `DefaultTextEditor` | (same call) |
| Open with the Text Editor | opens, empty, not dirty, Neovim attaches | same |
| The folder is missing too (`sub/new.txt`) | opens the same way | not tried |
| Close without saving | nothing on disk | not tried |
| `:w` (Eclipse's `doSave`) | **Save As dialog**; no file | **the test hung** in the save |

- **The external editor.** The first run used `IDE.openEditor(page, file, true)`, as `:e` does
  today. It hung in Windows' `ShellExecuteEx`, opening the missing file with the system's
  program. Today's `:e` never meets this, because it opens only existing files.
- **Save As.** `AbstractTextEditor.doSave` asks the document provider whether the input is deleted.
  A file that does not exist counts as deleted, so the editor runs Save As instead of saving.
- **The outside file's save** hung in Windows' native Save As dialog (thread dump:
  `FileDialog.open` from `AbstractDecoratedTextEditor.performSaveAs`). The workspace file's Save
  As is an SWT dialog, which the test's dialog closer could close; a native one it cannot. So
  `Ctrl+S` on a new outside file shows the native Save As, and no UI test may call Eclipse's own
  save on a missing outside file: it would hang instead of failing.

**A second experiment** tried the two ways to create the file on `:w` (section 4), and three open
questions:

| Step | Result |
|---|---|
| `findFilesForLocationURI` for a missing file, and for one in a missing folder | a workspace handle (`L/e2e/missing.txt`, `L/e2e/nosub/missing.txt`), `exists()` false |
| (a) save through the document provider, workspace file with text | file created with the text, editor clean, no dialog |
| (a) the same, untouched new editor | nothing written: the provider skips a clean document |
| (a) the same, workspace file in a missing folder | folder **and** file created, no dialog |
| (a) the same, outside file with text | file created, editor clean, no dialog |
| (b) create an empty file, then Eclipse's save, workspace file | "Update conflict" dialog, not saved; "File Changed" on the next activation |
| (b) the same, outside file | "Update conflict", not saved |
| Type `ihello` at once in a new workspace file | `ello` |
| The same after waiting one second | `ello` |
| The same in an existing empty workspace file | `hello` |
| The same in a new outside file | all keys arrive |

So (a) is the way, the `++p` check must run before it (it creates workspace folders on its own),
and the lost first key is real, specific to new workspace files, and not a matter of timing.

## 3. Opening (`:e`)

`EclipseFileCommands.edit`, after `findFile` finds nothing:

1. **Where:** `name` resolved against the first entry of `editFolders(editor)` (the current file's
   folder) through `EditPath`, which already handles `~` and absolute paths. With no folder (an
   untitled editor) and a relative name, today's error.
2. **`./` and `../`:** `EditPath.candidates` resolves a name starting with `./` or `../` (or
   `.\`, `..\` on Windows) against the first base only, for existing files too. It is a pure
   class, so this is a unit-tested change of its own.
3. **Not a directory:** if the path exists as a folder, today's "is a directory" error.
4. **Which editor:** `IDE.getEditorDescriptor(fileName, true, false)`, by name only. If that is
   `null` or `isOpenExternal()`, fall back to the Text Editor (`org.eclipse.ui.DefaultTextEditor`).
   Never the default lookup on the missing file itself (section 2).
5. **Which input:** a workspace file if the path is inside a project. `findFilesForLocationURI`
   returns a handle for a missing file, also in a missing folder (section 2). `open` skips handles
   that are not accessible today, so the new-file path takes the first handle as it is. Otherwise
   a `FileStoreEditorInput`.
6. **Message:** `"name" [New]` in the status line, through the same channel as other host command
   messages (`HostCommands`).

## 4. Writing (`:w`, `:wq`, `:x`, `ZZ`, `:wa`)

**The rule, with no state kept:** before saving an editor, check whether its file exists
(`IFile.exists()`, or `IFileStore.fetchInfo().exists()`). If it does, save exactly as today. If
not:

1. **Folder check, first:** if the parent folder is missing, then without `++p` show the E212
   message and stop. This must come before the save, because saving through the provider creates
   missing workspace folders on its own (section 2). With `++p`, create the folders
   (`IFolder.create`, parents first, in the workspace; `Files.createDirectories` outside).
2. **Save through the document provider** *(decided by the experiment)*. This is what
   `AbstractTextEditor.performSave` does, without its "deleted file means Save As" step:
   `provider.aboutToChange(input)`, `provider.saveDocument(monitor, input, document, true)`,
   `provider.changed(input)`. It creates the file, writes the text and leaves the editor clean,
   with no dialog, for workspace and outside files. Creating an empty file first and then saving
   is ruled out: it raised "Update conflict" and "File Changed".
3. **An untouched new editor:** the provider writes nothing for a clean document, so for `:w` the
   plug-in creates the empty file itself (`IFile.create` with no content, `Files.createFile`
   outside). `:x` and `ZZ` write only changed editors, as in Vim, so they create nothing.
4. **Message:** `"name" [New] 1L, 6B written`: the document's line count and the bytes written
   in the file's encoding. Vim counts a last line without a line break as a line too.

**Where:**

- A new `EclipseFileCommands.saveCreating(editor, createFolders)`, used by `save`, by `saveAll`
  for each editor, and by `zz`.
- `util/HostExCommand` learns `++p`. Today `:w ++p` parses as WRITE with the argument `++p`,
  which is ignored, as is any `:w otherfile`. It gets a `createFolders()` flag, set for `WRITE`
  and `WRITE_QUIT` when the argument is `++p`. Other arguments stay ignored, as now.
- `HostCommandRunner.Ide.save()` gets the flag: `save(boolean createFolders)`.
- The line and byte counts come from a small pure function in `util` (unit-tested).

## 5. The lost first key *(decided: part of this feature)*

In a new workspace file, the first key typed in insert mode is lost (section 2). In an existing
empty file and in a new outside file it is not, and waiting does not help.

- **Likely cause, to confirm:** on the first change, the editor asks the document provider to
  validate the input's state (`validateEditorInputState`, which for a workspace file goes through
  `IWorkspace.validateEdit`). For a file that does not exist, the answer may stop the change or
  reset the document, and the key goes with it.
- **Find it:** a UI test that types `ihello` right after `:e new.txt` and expects `hello`. Then
  step through the first `VerifyEvent` to see who sets `doit = false`, or which document change
  undoes it. Check also whether the key reached Neovim: a key lost in Eclipse only would also
  leave the two out of step.
- **Fix:** depends on the cause. Candidates: validate the state once when the new editor opens
  (`ITextEditorExtension2.validateEditorInputState()`), before any key; or let the first key
  through again if validation consumed it. The test above is the acceptance test.

## 6. Tests

- **`tests/unit`:**
  - `EditPathTest`: `./x` and `../x` give only the first base's candidate; `x` still gives both;
  - `HostExCommandTest`: `:w ++p`, `:wq ++p`, `:x ++p` set the flag, while `:w`, `:w foo` and
    `:wa` do not;
  - `HostCommandRunnerTest`: the flag reaches `Ide.save`, and `:wq ++p` closes only after the save
    worked;
  - the written-message function: lines, bytes, a missing final line break, non-ASCII text.
- **UI tests** (`CommandBoxEndToEndTest`, real Text Editor):
  - `:e new.txt` opens an empty editor titled `new.txt`; the status line says `[New]`; nothing is
    on disk;
  - typing `ihello` at once, then `:w`, creates `new.txt` with `hello` (the first key, section 5).
    No dialog, the editor clean, and the written message;
  - `:w` on an untouched new editor creates an empty file;
  - `:e gone.txt`, then `:q!`: no file;
  - `:e sub/new.txt`, then `:w`: the E212 message with the `++p` hint, and no file and no folder;
    then `:w ++p` creates `sub/` and the file;
  - an outside file (absolute path in a temporary folder): `:e`, then `:w` creates it;
  - `:e notes.unknownext`: opens in the Text Editor, never an external program;
  - `:wq` and `ZZ` create the file and close the editor; `:x` on an unchanged new editor
    closes without creating one;
  - `:e ./Foo.txt` with a `Foo.txt` only at the project root opens a new editor beside the
    current file;
  - an untitled editor keeps `Can't find file`.

  Every UI test that opens a missing file runs `closeModalDialogsWhile`, so an SWT dialog shows up
  as a failure instead of a hang. No test may call Eclipse's own save on a missing outside file:
  the test cannot close its native Save As (section 2).

## 7. Docs and commits

- **README**, command-line section: `:e` of a missing file, `./`, and `:w ++p`.
- **`docs/KEYS.md`:** no change.
- **`docs/ARCHITECTURE.md`:** the host-command row mentions new files.
- **`CHANGELOG.md`, *Unreleased*:**
  - *Added*: `:e` of a missing file opens an empty editor, `:w` creates the file, and `:w ++p`
    creates missing folders.
  - *Changed*: `:e ./name` looks only beside the current file.
- **Commits**, in UTC, as AGENTS.md asks:
  1. `EditPath`'s `./`, with unit tests;
  2. `++p` parsing, the runner's flag and the written message, with unit tests;
  3. the lost first key, with its UI test;
  4. opening, creating and saving, with UI tests and docs. Set this note's status to implemented.

## 8. To check while implementing

- **The first key's cause** (section 5): confirm it before choosing the fix.
- **`:w` on an untouched editor:** creating the empty file while the editor is open and clean
  should not raise "File Changed", since the document already matches the empty file. Confirm it.
- **`++p` outside the workspace:** the provider save was tried only with an existing folder
  there. Check that a missing outside folder fails cleanly without `++p`. It should, because the
  folder check stops it before the save.
- **Encodings** for the byte count: a workspace file's charset comes from its project or
  workspace setting. Use the one the save used (`IEncodingSupport`, or the file buffer's).
