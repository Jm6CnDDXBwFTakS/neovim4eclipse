# Design note: `:e newfile` opens an empty editor that `:w` creates

## Summary

In Vim, `:e new.txt` for a file that does not exist opens an empty buffer named `new.txt` and shows
`"new.txt" [New]`. Nothing is created on disk until `:w`. The plug-in does the same with Eclipse
editors (`editor/NewFiles`):

- **`:e` opens an Eclipse editor on the missing file, by its location** (a
  `FileStoreEditorInput`), also inside a project. It opens empty, with Neovim attached, and nothing
  is written (sections 2 and 3).
- **`:w` (and `:wq`, `:x`, `ZZ`, `:wa`) creates the file.** Eclipse would otherwise ask Save As,
  because it treats a missing file like a deleted one (section 4).
- **The editor is chosen by the file's name.** Eclipse's default lookup picks the system's external
  editor for a missing file, which would launch Notepad or a similar program.
- **The first write switches the editor to the workspace file** when the file is inside a project,
  so later saves and project features go through the workspace.

The experiments (section 2) used Neovim 0.12.4 on Windows 11, and the plug-in's UI test runtime.

## 1. Behavior

- **Where a name points.** As in Neovim, a relative name, `./` and `../` included, is relative to
  the window's folder, which the plug-in sets to the project root (`:lcd`, see WINDOWS.md, "Working
  folder per window"), or to the file's folder for a file outside any project. Neovim expands the
  name before Eclipse sees it (`LuaScripts.HOST_COMMANDS`: `expandcmd()`, then `fnamemodify(':p')`),
  so `%:h/name` is the file beside the current one, `~` is the home folder, and a new file goes
  where the name points. `util/EditPath` applies the same rule to a name that arrives relative
  anyway. Rejected: searching the current file's folder first and then the project root, with `./`
  meaning the current file's folder only. That search came from a time when Neovim's working folder
  was wherever Eclipse had started; with `:lcd` it made `:e name` and `:w name` mean different files.
- **`:e name` of a file that does not exist** opens an empty editor for it. The status line shows
  `"name" [New]`, as Vim does, with the name as typed (`"sub/new.txt" [New]`). A path that is a
  folder gives `"name" is a directory`.
- **`../` is not limited to the project.** Eclipse opens files anywhere, as outside files without
  project features.
- **Nothing is on disk until a write.** Closing the editor without writing (`:q!`, `ZQ`, the tab's
  close button) leaves nothing behind.
- **`:w`, `:wq`, `:x`, `ZZ` and `:wa` create the file** and then save as usual. As in Vim, `:w` and
  `:wq` write even when nothing was typed (an empty file), while `:x` and `ZZ` write only when there
  are changes.
- **After the write that creates the file,** the status line shows Vim's message, with the file
  name: `"new.txt" [New] 1L, 6B written` (lines and bytes of what was written, in the file's
  encoding; `util/WrittenMessage`). Vim counts a last line without a line break as a line too.
- **`Ctrl+S` and File › Save keep Eclipse's Save As dialog.** The plug-in does not take over
  Eclipse's save command.
- **A missing folder** (`:e sub/new.txt`):
  - `:e` opens the empty editor as for any new file;
  - `:w` fails with Vim's message, plus a hint:
    `E212: Can't open file for writing: no such file or directory (:w ++p creates it)`;
  - `:w ++p` creates the missing folders, then the file, as in Neovim (`:help ++p`). So do
    `:wq ++p` and `:x ++p`. `ZZ` has no `++p`: it shows the same error and keeps the editor open.
- **A failed write keeps `ZZ` and `:wq` from closing**, as in Vim, so `ZZ` on a new file in a
  missing folder shows E212 and keeps the text. This holds for existing files too (a read-only file).
- **Untitled editors** (File › New › Untitled Text File) have no file; their window's folder is
  chosen as WINDOWS.md, "Working folder per window", says, and a relative `:e` name is relative to
  it. A relative name that reaches Eclipse with no folder known gives `Can't find file "name"`.
  `:w` without a name in an untitled editor keeps Eclipse's Save As: only an editor opened by
  location on a local file counts as a new file.
- **A file that was deleted while its editor was open** is recreated by `:w`. That matches Vim, and
  falls out of the design: nothing is kept about which editors are new; the rule is "the editor's
  file does not exist", not "the editor was opened as new".

## 2. The experiments

**Opening and saving.** A throwaway UI test opened editors on missing files in the test runtime,
typed `ihello<Esc>`, ran `:w`, and recorded each step:

| Step | Workspace file | File outside the workspace |
|---|---|---|
| Default editor, `IDE.getEditorDescriptor(file)` / `getEditorDescriptorForFileStore` | `systemExternalEditor` | `systemExternalEditor` |
| Default editor for the same name when the file exists | `DefaultTextEditor` | `DefaultTextEditor` |
| Default editor by name only, `IDE.getEditorDescriptor("new.txt", true, false)` | `DefaultTextEditor` | (same call) |
| Open with the Text Editor | document empty, not dirty, Neovim attaches; **on screen "File ... does not exist."** (below) | opens, empty, Neovim attaches |
| The folder is missing too (`sub/new.txt`) | opens the same way | not tried |
| Close without saving | nothing on disk | not tried |
| `:w` (Eclipse's `doSave`) | **Save As dialog**; no file | **the test hung** in the save |

- **The external editor.** `IDE.openEditor(page, file, true)` on a missing file hung in Windows'
  `ShellExecuteEx`, opening the missing file with the system's program.
- **A workspace file that does not exist cannot be shown.** For a file editor input,
  `TextFileDocumentProvider.getStatus` reports "File ... does not exist." on purpose (its comment:
  "we don't open an empty document for an non-existent IFile"), and the editor puts that message,
  as a read-only page, in front of its text: keys and `:w` do nothing. The document and the hidden
  text widget look fine, so a UI test that sends keys straight to the widget passes anyway; a UI
  test must check that the editor shows its text and has the keyboard focus. This is why every new
  file is opened by location.
- **Save As.** `AbstractTextEditor.doSave` asks the document provider whether the input is deleted.
  A file that does not exist counts as deleted, so the editor runs Save As instead of saving.
- **The outside file's save** hung in Windows' native Save As dialog (thread dump:
  `FileDialog.open` from `AbstractDecoratedTextEditor.performSaveAs`). The workspace file's Save
  As is an SWT dialog, which the test's dialog closer could close; a native one it cannot. So
  `Ctrl+S` on a new outside file shows the native Save As, and no UI test may call Eclipse's own
  save on a missing outside file: it would hang instead of failing.

**Creating the file.** A second experiment tried two ways to create the file on `:w`: (a) save
through the document provider, (b) create an empty file, then Eclipse's save:

| Step | Result |
|---|---|
| `findFilesForLocationURI` for a missing file, and for one in a missing folder | a workspace handle (`L/e2e/missing.txt`, `L/e2e/nosub/missing.txt`), `exists()` false |
| (a), workspace file with text | file created with the text, editor clean, no dialog |
| (a), untouched new editor | nothing written: the provider skips a clean document |
| (a), workspace file in a missing folder | folder **and** file created, no dialog |
| (a), outside file with text | file created, editor clean, no dialog |
| (b), workspace file | "Update conflict" dialog, not saved; "File Changed" on the next activation |
| (b), outside file | "Update conflict", not saved |

So (a) is the way, and the `++p` check must run before it, since it creates workspace folders on
its own.

**The lost first key.** Typing `ihello` at once in a new editor opened as a workspace file gave
`ello`, also after waiting one second; in an existing empty workspace file and in a new outside
file all keys arrived. The cause is the text editor's one-time `Validator`, a `VerifyListener`
that runs `validateEditorInputState()` on the first typed change and drops the key if the check
fails or changes the document or input on the way; for a missing workspace file it does. It only
shows when Eclipse types the text: keys typed before insert mode reached Eclipse go to Neovim and
are never lost, which is why typing `ihello` in one go in a test may not reproduce it. Opened by
location, a new file never meets it, so no workaround is needed
(`NewFileEndToEndTest.theFirstKeyTypedInANewWorkspaceFileIsKept` guards it).

## 3. Opening (`:e`)

`EclipseFileCommands.edit`, for a name whose file does not exist, calls `NewFiles.open`:

1. **Where:** the full path Neovim expanded (section 1); `EditPath.resolve` for a name that
   arrives relative, against the window's folder (`EclipseFileCommands.windowFolder`).
2. **Not a directory:** a path that exists as a folder gives the "is a directory" error.
3. **Which editor:** `IDE.getEditorDescriptor(fileName, true, false)`, by name only, never the
   default lookup on the missing file itself (section 2). `NewFiles.internalOr` replaces it with
   the Text Editor (`org.eclipse.ui.DefaultTextEditor`) when it is `null`, not internal
   (`isInternal()`), external (`isOpenExternal()`), in place (an OLE document on Windows,
   `isOpenInPlace()`), or for a content type that is not text. So `:e notes.unknownext` opens in
   the Text Editor, never an external program. (An existing workspace file goes by every content
   type the workspace gives it, its project's own content types included: a cloned project can map
   a name to the image type, which the Web Browser editor shows, scripts and all.)
4. **Which input:** always a `FileStoreEditorInput` (section 2). `NewFiles.workspaceFileAt` finds
   the project from the location wherever the code needs it (`missingFileOf`, the project root for
   `:e` lookups).
5. **Encoding:** an editor opened by location would use the workspace default, so it takes the
   encoding the file will have in its project or folder (`IFile.getCharset()`, which answers for a
   missing file too). Example: in an ISO-8859-1 project, `é` is written as one byte, not two.
6. **Message:** `"name" [New]` in the status line, through the same channel as other host command
   messages (`HostCommands`), after the plug-in attaches.
7. **A dangling symbolic link:** a link whose target does not exist counts as missing
   (`Files.exists` follows links), so it opens as a new file, as in Vim, with the target named in
   the `[New]` message. Its write would create the target, wherever the link points, so `:w`
   refuses it and `:w!` writes there (`editor/DanglingLinks`). `:wq` refuses it too and keeps the
   editor open, `:wq!` writes through it and closes (`util/HostCommandRunner` passes the `!` on).
   The same goes for a copy onto one, Neovim's own `:w other.txt` (`editor/OtherFileWrites`,
   since Neovim's E13 check follows the link and lets it pass) and `:wq other.txt` alike.

## 4. Writing (`:w`, `:wq`, `:x`, `ZZ`, `:wa`)

**Where the writes come from.** `:w` and `:update` are Neovim's own writes: the buffer's
`BufWriteCmd` (`LuaScripts.DOCUMENT_BUFFER`, `core/DocumentWrite`) asks Eclipse to save
(`EditorAttachManager.saveOnUi`, then `EclipseFileCommands.save`), always as a write even of an
unchanged editor, since Neovim asks only for `:w` or for `:update` of a modified buffer. `:w ++p`
reaches Eclipse in the write request. `:wq`, `:x` and `:exit` are host commands (`util/HostCommand`,
`WRITE_QUIT`): `writesUnchanged()` is true for `:wq`, false for `:x` and `:exit`, and
`createFolders()` is set by `++p`; `HostCommandRunner.Ide.save(createFolders, evenIfUnchanged)`
takes both, and closes only after the save worked. `ZZ` (`EclipseFileCommands.zz`) and `:wa`
(`saveAll`) end in the same `NewFiles.save`.

**The rule, with no state kept:** before saving an editor, `NewFiles.missingFileOf` checks whether
its file exists (`IFile.exists()`, or the location on disk). If it does, Eclipse saves it as
`Ctrl+S` would. If not, `NewFiles.save`:

1. **Folder check, first:** if the parent folder is missing, then without `++p` show the E212
   message and stop. This comes before the save, because saving through the provider creates
   missing workspace folders on its own (section 2). With `++p`, create the folders
   (`IFolder.create`, parents first, in the workspace; `Files.createDirectories` outside).
2. **A changed editor is saved through the document provider.** This is what
   `AbstractTextEditor.performSave` does, without its "deleted file means Save As" step:
   `provider.aboutToChange(input)`, `provider.saveDocument(monitor, input, document, true)`,
   `provider.changed(input)`. It creates the file, writes the text and leaves the editor clean,
   with no dialog. The workspace file is then refreshed, since an editor opened by location writes
   to disk without telling the workspace.
3. **An untouched new editor:** the provider writes nothing for a clean document, so for `:w` and
   `:wq` the plug-in creates the empty file itself, refreshes the workspace file, then asks the
   provider to synchronize the editor, in that order: the editor's synchronize compares with what
   the workspace knows, so in the other order Eclipse later asks "File Changed". `:x` and `ZZ`
   write only changed editors, as in Vim, so they create nothing.
4. **The switch to the workspace file.** For a file inside a project, the first write switches the
   editor to the workspace file (`IReusableEditor.setInput`), as Eclipse's File › Open File does
   for a file in a project (`IDE.openEditorOnFileStore`). Why: every later save of an editor opened
   by location would bypass the workspace (a stale Project Explorer, no build), and project
   features (problem markers, a Java editor's code assist) need a workspace file. Eclipse reads the
   file into a new document; `EditorAttachManager.replaceInputKeepingBuffer` moves the Neovim
   buffer to it (`EditorBuffers.move`) and attaches again, so Neovim keeps the buffer's number,
   undo history and marks; Eclipse's own undo history starts over. The text is the same, so nothing
   is sent to Neovim. The switch is skipped (logged) when another editor shows the same document
   (*Window › Editor › Clone* before the first `:w`) or the editor cannot take a new input.
5. **Releasing the left-over file buffer** (`NewFiles.releaseLeftOverBuffer`). Eclipse files a
   buffer under a key from `FileBuffers.normalizeLocation`: the location (`/ws/proj/new.txt`) while
   the file does not exist, its workspace path (`/proj/new.txt`) once it does. The editor connected
   under the location; at the switch the file exists, so the disconnect looks under the workspace
   path, finds nothing and returns, and the buffer stays connected, with its text, until Eclipse
   exits. Without the release: write `new.txt`, delete it in Eclipse, `:e new.txt` again, and the
   clean new editor shows the old text. The plug-in disconnects it by the location without
   normalizing (`LocationKind.LOCATION`), only when it is not the editor's current buffer. With the
   switch skipped (two editors), the buffer stays connected; ARCHITECTURE.md lists that case.
6. **Message:** `"name" [New] 1L, 6B written`: the document's line count and the bytes written
   in the file's encoding (`WrittenMessage`).

**`:wa` when a new file cannot be written:** Eclipse's Save All would offer Save As for it, so the
new files are written first, and when one fails the other modified editors are saved one by one
instead, and `:wa` reports failure.

## 5. Tests

- **`tests/unit`:** `EditPathTest` (relative names, `./`, `../`, `~`, absolute paths);
  `HostCommandTest` (`:wq ++p` and `:x ++p` set `createFolders`, `writesUnchanged` for `:wq` and
  not `:x`); `HostCommandRunnerTest` (the flags reach `Ide.save`, and `:wq ++p` closes only after
  the save worked); `WrittenMessageTest` (lines, bytes, a missing final line break, non-ASCII text).
- **UI tests, `NewFileEndToEndTest`** (real Text Editor): `:e` of a missing file opens an empty
  editor that shows its text and has the focus, and `:w` creates it
  (`editOfAMissingFileOpensAnEmptyEditorAndWriteCreatesIt`); the first write switches to the
  workspace file and keeps Neovim's buffer and undo; written, deleted and opened again, the file is
  empty; the first key typed is kept; `:w` on an untouched editor creates an empty file; quitting
  without writing leaves nothing; the `[New]` message names the file short after `:lcd` in another
  case; a missing folder needs `++p`, inside and outside the workspace; an outside file is created
  by `:w`; the written message counts bytes in the file's encoding (`é` in an ISO-8859-1 project is
  `1B`, and one byte on disk); an unknown extension opens in the Text Editor; `:wq` and `ZZ` create
  the file and close; `:x` on an untouched editor closes without creating one; `:wa` creates new
  files and saves the others; `./` is the project root and `%:h` the file's folder.
  `CommandBoxEndToEndTest.anUntitledFileFindsNamesInTheFolderOfTheEditorBeforeIt` covers the
  untitled case.

  Every UI test that opens a missing file runs `closeModalDialogsWhile`, so an SWT dialog shows up
  as a failure instead of a hang. No test may call Eclipse's own save on a missing outside file:
  the test cannot close its native Save As (section 2).
