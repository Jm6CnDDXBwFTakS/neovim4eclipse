# Design note: a closed file keeps its buffer number, as with Vim's `:bd`

In Vim, `:bd` *unloads* a buffer and takes it off the `:ls` list, but keeps it: its number, its
marks and its last cursor position (`'"`). `:e` of the file later brings the same buffer back. The
plug-in does the same. When the last editor of a file closes, its buffer is unloaded and unlisted,
not wiped (wiping, `:bwipeout`, is what gives a reopened file a new, higher number and loses its
marks). When an editor opens on that file again, it reuses the buffer. When Neovim moves into a
workspace file that has no editor by itself (`:b 3` or `Ctrl-^` into a closed file's buffer, `gf`,
`'A`), Eclipse opens the file's editor, as Vim edits the file (section 2.3). That last part is also
how quickfix entries reach closed files (docs/design/DIAGNOSTICS.md, section 5).

Example: a.txt is buffer 1, b.txt 2. Close a.txt's editor and open it again: it is buffer 1 again,
as in Vim, with its marks, and the caret on `'"`; `:b 1` works while it is closed, and opens it.
(Wiping instead would make it 3, and `:b 1` would fail.)

**Why:** Neovim already does this; the plug-in only does not undo it ("Neovim does the work"). The
numbers in `:ls`, `:b N` and `:bd N` mean what they mean in Vim, and marks survive a close.

Not changed: the undo history is lost when the buffer unloads (as in Vim, unless `'undofile'`), and
jumps into a closed file are dropped (Neovim's default `'jumpoptions'` has `clean`). Restart Neovim
starts the numbers over, as a new Vim does.

The experiments used Neovim 0.12.6 on Linux, headless (`nvim --clean -l`), unless a row names
another system.

## 1. What Neovim does (experiments)

| # | Experiment | Result |
|---|---|---|
| X1 | `:e a.txt`, `:e b.txt`, `:e c.txt`, `:bd a.txt`, `:e a.txt` | a.txt is buffer 1 again. After `:bd` it is listed by `:ls!` only, as `1u`. |
| X2 | `:bw b.txt`, `:e b.txt` | b.txt gets a new number (4): wiping is what renumbers. |
| X3 | `ma`, cursor on line 3, `:bd`, `:e` | `'a` and `'"` are kept, and `:e` puts the cursor back on line 3. |
| X4 | X3 with `'buftype'` `acwrite` or `nofile` (the plug-in's document buffers) | The marks are **lost** on unload (`'a` reads 0). With `swapfile` off or `bufhidden=hide` they are kept: it is the buftype. |
| X5 | X4, but `'buftype'` set back to `''` just before unloading | The marks are kept. |
| X6 | A buffer made as `CREATE_DOCUMENT_BUFFER` makes it, closed as in X5, then `bufadd(name)` | Returns the same number; `bufload` loads it again. Options and `b:neovim4eclipse_file` survive the unload. |
| X7 | Closing as in X5 fires | `BufUnload`, then `BufDelete` (from unlisting). Not `BufWipeout`. |
| X8 | `:b 2` into the closed buffer | Neovim reads the file from disk (`BufReadPre`, `BufReadPost`, `BufEnter`, `BufWinEnter`), the cursor goes to `'"` (line 3), `'a` is there, `swapfile` stays off. |
| X9 | `Ctrl-^` when the alternate file is the closed buffer | Neovim enters and loads it, as X8. |
| X10 | The plug-in's first push of Eclipse's text (`LOAD_WITHOUT_UNDO`, all lines replaced) into the reloaded buffer | The marks move: `'a` 2 → 5, `'"` 3 → 6 (the one empty line is replaced, the lines below shift). Under `:lockmarks` they stay (2 and 3). |

X4 is observed behavior; Neovim's documentation does not describe it, and why it happens is not
known. X5 works around it.

### 1.1 Neovim reading a file by itself (for 2.3)

Probed headless with the plug-in's scripts, then in the test Eclipse (a temporary UI test). "The
hook" is the design of 2.3: a `BufReadCmd` limited to the open projects' folders that reads the
file with `lockmarks keepalt noautocmd silent read ++edit`, then deletes the empty first line, with
`'undolevels'` -1 and `'modified'` off.

| # | Experiment | Result |
|---|---|---|
| X11 | Without the hook: `gf` from a document onto a workspace file without an editor | Neovim reads it: `BufReadPre`, filetype detection (`java`), the java ftplugin (`indentexpr=GetJavaIndent()`, `commentstring`, `suffixesadd`), editorconfig, the user's `FileType` autocommands (a test language server started). A plug-in that sends Neovim back with no message leaves the buffer loaded and listed (`5 #h`) **with a swap file**. Its jump is gone from the jump list, so `Ctrl-O` does not go there. |
| X12 | X11, then that file's editor opened | The attach takes the buffer over: same number, swap file deleted, but filetype, ftplugin options and the started server stay. |
| X13 | Clearing `'filetype'` at takeover | `b:undo_ftplugin` removes Neovim's own ftplugin options; what the user's autocommands started stays. |
| X14 | Turning language servers off | `vim.lsp.enable(name, false)` misses later enables and direct `vim.lsp.start` (nvim-jdtls's way). Wrapping `vim.lsp.start` with a list of names stops all of them. Blocking only in document buffers fails: the buffer is not one yet when Neovim reads it, and a started server is reused. |
| X15 | The hook: `gf`, `:b` into a closed document, `:cc` (quickfix), `bufload()` (a picker's preview) | No `BufReadPre`/`BufReadPost`/`FileType`, no server, no undo step (`u`: "Already at oldest change"), the text is there. The takeover keeps the number and leaves no filetype: the file is like one Eclipse opened. Marks survive (`lockmarks`); the cursor is not on `'"` (the attach's `GO_TO_LAST_POSITION` puts it there). |
| X16 | The hook's read | Decodes like Neovim: latin1 (`fenc=latin1`), CRLF (`ff=dos`), a BOM (`bomb`), an empty file. `readfile()` instead does not decode (`caf<e9>`). **Creates a swap file** unless the hook turns `'swapfile'` off first. A missing file (a mark into a deleted file) fails with E484 inside the hook. |
| X17 | Outside the hook | A file outside the projects' folders is read by Neovim as usual; so is a sibling folder whose name starts the same (`proj2` beside `proj`). `:read file` uses `FileReadCmd` and is not affected. The plug-in's own buffers (`noautocmd call bufload`) never reach the hook. |
| X18 | Patterns (an `autocmd` pattern per folder) | With `\` before each of `\ * ? [ ] { } , ~ $` (spaces need none), a project folder named with any of `[ack] {ly} , * ? $ ~ % # \ ' " ( ) + ^ ; = @ & ! | ä`, spaces or a tab matches, and a decoy sibling does not. Neovim matches the resolved path: the pattern must be built from the real path (`fs_realpath`), and then a file reached through a link matches. `'fileignorecase'` (on by default on Windows and macOS) makes the match case-insensitive. Patterns use `/` on every system (`:help file-pattern`). **macOS** (arm64): every check passes; `fs_realpath` turns `/var` into `/private/var` and an NFC folder name into APFS's NFD one, and a pattern built without it misses an NFD name. **Windows** (11, arm64): drive letters, `\` or `/`, other case and a lower-case drive all match once the pattern is written with `/` (a `\` pattern never matches); but a folder named `$HOME` misses, and a junction keeps its own path in the buffer name, so the pattern must be made from Eclipse's path as well as the real one (X18d). |
| X18b | The hook in the real Eclipse, other systems | macOS: as on Linux (a hook without `'swapfile'` off leaves swap files). Windows: after `gf`, a hook without `'swapfile'` off stopped between its `:read` and its `1delete`, and Neovim then stopped answering; with `'swapfile'` off first it ran cleanly, twice. |
| X18c | Why: a stale swap file (Linux, test Eclipse) | With a swap file left for `B.java` and the hook's `'swapfile'` on, the hook's `:read` stops at Neovim's E325 ATTENTION question (mode `r?`), the buffer showing `{ "", "class B {", ... }` as on Windows. `noautocmd` also silences `SwapExists`, so nothing can answer it. With `'swapfile'` off before the read: no question. (Neovim's own read, without the hook, asks the same question for such a file.) |
| X18d | More Windows patterns | A folder named `$` followed by a set variable's name (`$HOME`, `$TEMP`) misses with every escape (`$`, `\$`, `[$]`); `${HOME}`, `a$b`, `$(x)` match. A junction keeps its own path in the buffer name: only patterns for both the junction path and the real path catch both. An 8.3 short name stays in the buffer name (`C-139B~1\...\BAA8E~1.JAV`), so a long-name pattern misses it; Neovim's `:8` does not shorten, so no pattern can be made for it. |
| X19b | The hook without patterns (Linux, macOS, Windows) | A `BufNew` autocommand for every buffer compares the buffer's file, resolved (`fs_realpath`: links, junctions, 8.3 names, NFD) with `/` separators and lower-cased under `'fileignorecase'`, with the projects' resolved folders, and gives a buffer inside one a buffer-local `BufReadCmd`. Linux: `gf`, relative `:edit`, `:badd`+`:b`, `bufadd()`+`bufload()`, `:cc`, a `'B` mark read from shada, an unloaded buffer reloaded twice, a link, a missing file, every special folder name (`$HOME` included): all read by the hook, no `BufReadPre` or `FileType`; a sibling `Proj2` and `:help` untouched. macOS: the same, plus other case, and NFD folders opened by their NFC spelling (also a project folder that is NFD on disk, registered by its NFC spelling). Windows: the same, plus other case, a junction (the buffer keeps the junction path) and an 8.3 short name (`LONGFO~1\LONGFI~1.JAV`). Only folders named `$HOME` and `$TEMP` miss, and not because of the hook: on Windows `:edit` expands the variable even after `fnameescape()` (the buffer becomes `...\names\C:\Users\<name>\home\f.txt`, which does not exist); `bufadd()` keeps the name literal. That is Neovim's own handling, the same without the plug-in. |
| X19 | `bufload()` of a project file in the real session | Its `BufEnter` comes from Neovim's autocommand window; Eclipse does not move. |
| X20 | A script's `:edit!` on a document (not typed: a typed `:e!` goes to Eclipse) | Without a reload hook, Neovim reads the file from disk into the document's buffer, which then differs from Eclipse's document, and runs filetype detection. With only the project hook, the hook reads it: same divergence, no filetype. So a live document's reload is left to Eclipse (2.3, point 4). |
| X21 | Eclipse: finding the file | `findFilesForLocationURI` finds a file through a link to the project, and a file under a linked folder (links may be nested, at any depth) once per link. A file in a closed project is found but not accessible. A file made on disk since the last refresh is found but does not exist for Eclipse, and `IDE.openEditor` opens it **empty**: refresh it first. |
| X22 | Eclipse: which editor would open | `IDE.getEditorDescriptor`: `.dat`, `.zip`, `.jar`, `.pdf` (and `.java` without JDT) go to the system's external editor, `.png` and `.html` to the browser. So Eclipse may open only an internal text editor; anything else is sent back. |
| X23 | A file another `BufReadCmd` reads: a real `.zip` in a project, with Neovim's `zip.vim` | Both readers run, `zip.vim`'s first (defined at startup): it lists the archive and makes the buffer read-only, and the hook's `:read` on top fails with E21. So the hook leaves a buffer that is filled or read-only already to the other reader. |

Also probed in the test Eclipse: keys typed in the moment between a script's `:edit!` and Eclipse's
reload of the document (about 1 to 15 ms) were lost on both sides when the buffer was simply
refilled (a `RACE` queued before the reload reached neither), and the cursor went to line 1, as the
buffer was empty for that moment. 2.3, point 4 keeps the text across the reload for this reason.

## 2. The design, by part

### 2.1 Close: unload, not wipe

`DocumentBuffers.close` forgets the buffer in Java, `RELEASE_BUFFER` closes its windows and Neovim
returns to the attached buffer. Then `CLOSE_DOCUMENT_BUFFER`:

1. Clears the plug-in's buffer-local autocommands (the write hook `BufWriteCmd`, the reload hook,
   the safety net): they belong to a document with an editor. They live in the augroup
   `neovim4eclipse_document` so that they can be cleared for that buffer alone, and are set again
   when the buffer is reused (without the group, `DOCUMENT_BUFFER` would add them a second time).
2. Sets `'buftype'` back to `''` (X4, X5), so the marks survive.
3. `nvim_buf_delete(buf, { force = true, unload = true })`, then `'buflisted'` off: `:bd`. `force`,
   because unsaved changes live in Eclipse.

`b:neovim4eclipse_file` stays: it marks the buffer as a closed document's. The safety net's
`BufDelete` notice from step 3 reaches Eclipse after Java has forgotten the buffer, and is ignored.

### 2.2 Open: reuse the closed buffer

`CREATE_DOCUMENT_BUFFER` makes a new unnamed buffer when a live document's buffer of that name
exists, so that two documents never share one. A closed document's buffer (`b:neovim4eclipse_file`
set, and not a live document: unlisted, or not `acwrite`) is instead reused: the same options as a
new one (`nofile`, `bufhidden=hide`, no swap file), `bufload` if it is not loaded (X8 may have
loaded it from disk), listed again. Eclipse's text is pushed next, as for a new buffer, under
`:lockmarks` (X10), so that the marks stay on their lines. `LOAD_WITHOUT_UNDO` runs as
`:lockmarks lua ...` rather than passing the lines through a `g:` variable (13 ms against 35 ms for
200,000 lines; NUL bytes work either way).

**The caret** of the reopened editor is Neovim's `'"`, where Vim puts the cursor on `:e` of a
buffer it kept (X3, X8), not where Eclipse would open the file: the attach of a reused buffer takes
Neovim's cursor instead of pushing Eclipse's caret (`GO_TO_LAST_POSITION`).

### 2.3 Neovim moves into a file without an editor: Eclipse opens it

`:b 3`, `Ctrl-^` and `'A` into a closed document's buffer load it from disk (X8, X9); so do `gf`,
`'A` and `:b name` into a workspace file never opened in this session.
`EditorAttachManager.followNeovimIntoFile` opens that file's editor when the buffer is a file in the
Eclipse workspace (as `:e` of a file without an editor does, `EclipseFileCommands`), then follows as
for an open file: the window that jumped travels into the new editor (docs/design/WINDOWS.md, E2),
so `Ctrl-O` there goes back. The attach finds the buffer by name (2.2) and pushes Eclipse's text.
Anything else Neovim enters (`:help`, a scratch buffer, a file outside the workspace) is undone:
Neovim goes back to the editor's buffer.

This applies to any workspace file, not only closed documents: the mechanism and its risk are the
same, so it is built once, and quickfix (DIAGNOSTICS.md, section 5) only adds the lists,
`:Eproblems`, `:Etasks` and `:copen`.

**The risk.** Neovim's own read of a file (`BufReadPre`, `BufReadPost`, filetype detection, the
user's `BufRead` and `FileType` autocommands, a swap file, an undo history starting there) is not
how the plug-in fills a document's buffer, which comes from Eclipse's text. Neovim's read runs the
filetype plug-ins and the user's `FileType` setup (a language server may start: X11), which the
plug-in's buffers never do, and taking such a buffer over leaves them behind (X12). So **Neovim
never reads a project's file itself** (1.1):

1. **Project folders.** Eclipse sends Neovim the locations of the open projects and of their
   linked folders (any depth), again whenever a project opens, closes, is created, deleted or
   moved, or a link is added or removed (a workspace listener, as `ProjectFolderExit` has).
   `editor/ProjectFolders` sends them at each Neovim's first attach and after workspace changes,
   through one coalescing job (`Sender`) that does every walk: the first attach's job and the
   listener would otherwise send out of order, and the walk stays off the thread of each change
   (it is fast: 0.2 ms for 30 projects of 120 folders, so the job is for order and the UI thread).
   Each project's walk is kept: the first attach walks every project, a later change only the
   projects it touched, so a huge workspace is not walked whole at each project opened or closed.
2. **A `BufNew` hook, no patterns** (X19b; `LuaScripts.PROJECT_FILES`, module
   `neovim4eclipse.project_files`, and `SET_PROJECT_FOLDERS`). For each new buffer, Neovim compares
   the buffer's file, resolved with `fs_realpath` (links, junctions, 8.3 names, NFD), `/`
   separators, lower-cased under `'fileignorecase'`, with the folders resolved the same way. A
   buffer inside one gets a buffer-local `BufReadCmd`, kept when the buffer unloads, so a closed
   document's buffer has it too. When the folders change, existing buffers gain or lose it.
   Patterns (an `autocmd` pattern per folder) fail on Windows (X18d), hence the `BufNew` form.
3. **The read** (X15, X16): `'swapfile'` off first (a stale swap file otherwise stops the read at
   E325: X18c), nothing for a missing file, then `lockmarks keepalt noautocmd silent read ++edit`
   and the empty first line deleted, with `'undolevels'` -1 and `'modified'` off. No filetype, no
   `BufReadPost`. When the buffer is the current one, the cursor goes to `'"` (where `:b N` of a
   kept buffer puts it; a mark, a quickfix entry or `Ctrl-O` moves it on afterwards, as Neovim does
   after any read). A buffer that another `BufReadCmd` filled or made read-only already is left to
   that reader (X23). `PROJECT_FILES` leaves `acwrite` buffers (live documents) alone.
4. **A live document's reload** (X20: `:edit!` from a script, not typed). Every document's buffer,
   inside a project folder or not, has its own reload hook, in `DOCUMENT_BUFFER` (not
   `PROJECT_FILES`: a document outside every project folder would otherwise read the disk and end
   the buffer's line events, so keys typed in Neovim would no longer reach the document). The hook
   does not read the disk; it asks Eclipse to load the document's text into the buffer again
   (`eclipse_reload`, `RELOAD_DOCUMENT_BUFFER`; `NeovimSession.reloadDocumentAsync` attaches the
   buffer's line events again, since the unload ended them). The text is kept across the reload
   (the probe at the end of section 1): `BufUnload` keeps the lines, `'modified'` and the marks (which Neovim
   drops for an `acwrite` buffer: X4), and the `BufReadCmd` puts them back; the buffer is attached
   again with `send_buffer`, so a change made in between reaches the document as a minimal edit.
   While Eclipse types in insert mode the attach sends nothing (the document is ahead until Esc).
   A buffer unloaded on its own and read later is still filled from the document.
5. **Following** (`followNeovimIntoFile`; `eclipse_buf_enter` carries a project file's name as its
   fourth value): a buffer that is no document's but has the hook is a workspace file.
   `editor/ProjectFileEditors` finds it with `NewFiles.workspaceFileAt` (the one
   path-to-workspace-file lookup; `findFilesForLocationURI`, the first accessible one) and refreshes
   it (a file made on disk since the last refresh would open empty: X21), in a job: on the UI
   thread a job holding the workspace (a build's rule) would freeze the UI for as long (3009 ms for
   a 3000 ms job). Only the opening runs on the UI thread, and only if Eclipse's editor for the file
   is internal (never the system editor: X22); if what opened is not a text editor (an image), it
   is closed again. Then as for an open document: the window that jumped travels into the new
   editor and the attach takes Neovim's cursor. The attach reuses the buffer (2.2) and pushes
   Eclipse's text (the cursor keeps its line). Anything else, or a failure, is sent back.

`:e #` needs nothing: `HOST_COMMANDS` already gives `:e` of a file without an editor to Eclipse.

With this, language servers need no plug-in setting: none starts for a project's file, and the
README's `vim.g.neovim4eclipse` check stays the way to skip them altogether.

**Rejected alternatives** for a workspace file Neovim reads by itself:

- Keep Neovim's read (filetype, ftplugin, the user's `FileType` setup): the buffer then differs from
  every buffer Eclipse opened, and a language server may start inside Neovim (X11, X12).
- Clear the filetype at takeover (X13): Neovim's ftplugin options go, but what the user's setup
  started (a language server) stays.
- A hook on every read: it would have to redo Neovim's read by hand for files outside the projects.

### 2.4 What else used to assume the wipe

| Place | Behavior |
|---|---|
| `wipe_left_over` (`DOCUMENT_BUFFER`) | wipes unloaded buffers named like a written file; skips buffers with `b:neovim4eclipse_file`, so a closed document's buffer stays |
| `HOST_COMMANDS` `open_document_edited` | loaded document buffers only: a closed one is not loaded (or, after X8, not listed) |
| `:bd 3` of a closed file's number | an E516-like "no editor" message: nothing to close |
| `:ls` | closed files are gone from `:ls`, and shown by `:ls!` with `u`, as in Vim |

## 3. Tests

- `project_files_spec.lua` (the hook, links, folders coming and going, a closed document's buffer,
  the reload, a stale swap file, encodings, `bufload()`, another reader), `document_buffer_spec.lua`
  (close and reuse; the text kept across a reload), `buffer_scripts_spec.lua` (marks kept by a load,
  X10).
- `NeovimBufferIntegrationTest`, `NeovimWindowsIntegrationTest`: the same number after reopening,
  `:b N` reopens.
- `NotificationBridgeTest`: the notifications.
- UI tests: `TwoEditorsEndToEndTest` (`:ls!` numbers after closing and reopening an editor, the
  caret on `'"`); `ProjectFilesEndToEndTest` (`gf` and `Ctrl-O` back, `:b N` and `Ctrl-^` into a
  closed file, a quickfix entry, a file made outside Eclipse, a `.zip` refused and `:help` undone, a
  script's `:edit!` and the text kept across its reload). Its tests fail with the folders not sent,
  except the refusal, which checks that Eclipse was asked. Each half of the reload's attach rule,
  undone, fails one test in `document_buffer_spec.lua` or `ProjectFilesEndToEndTest`.

## 4. Probed and left as they are; known limits

- Nested projects: Eclipse lists the inner project's file first, so a Maven module's editor opens.
- A multi-page editor that opens on a form page: Neovim goes back until the source tab is chosen,
  then `Ctrl-O` returns through Eclipse's Back.
- Junctions, 8.3 names, a lower-case drive letter and `/` on Windows: `bufexists` of the real path
  finds the same buffer (on Linux through a symbolic link too).
- A linked single file (not folder) is read by Neovim itself, as any file outside the projects.
- A link whose target changes in place is seen at the next project or link change.
- On Windows a folder literally named `$HOME` is expanded by Neovim's own `:edit` (X19b), with or
  without the plug-in.
