# Design note: a closed file keeps its buffer number, as with Vim's `:bd`

Status: **planned** (2026-10-29); steps 1 and 2 built, step 3 designed (Q3). Branch `feature/buffer-numbers` from `release/0.1.10`
at `7bbe802`. The experiments used Neovim 0.12.6 on Linux, headless (`nvim --clean -l`). Decisions
made with the maintainer are marked *(decided)*.

## Summary

In Vim, `:bd` *unloads* a buffer and takes it off the `:ls` list, but keeps it: its number, its
marks and its last cursor position (`'"`). `:e` of the file later brings the same buffer back. The
plug-in instead *wipes* a file's buffer when its last editor closes (`DocumentBuffers.close`,
`nvim_buf_delete` without `unload`, which is `:bwipeout`). So a reopened file gets a new, higher
number, and its marks are gone. Example: a.txt is buffer 1, b.txt 2; close a.txt's editor and open
it again: Vim says 1, the plug-in says 3, and `:b 1` fails.

**Plan (B, *decided*):** do what `:bd` does. When the last editor of a file closes, unload and
unlist its buffer instead of wiping it. When an editor opens on that file again, reuse the buffer.
When Neovim moves into a workspace file that has no editor by itself (`:b 3` or `Ctrl-^` into a
closed file's buffer, `gf`, `'A`), Eclipse opens the file's editor, as Vim edits the file. That last
part is the quickfix plan's "way B" (docs/design/DIAGNOSTICS.md, section 5), built here *(decided
2026-10-29, Q1)*.

**Why:** Neovim already does this; the plug-in only stops undoing it ("Neovim does the work"). The
numbers in `:ls`, `:b N` and `:bd N` then mean what they mean in Vim, and marks survive a close.

Not changed: the undo history is lost when the buffer unloads (as in Vim, unless `'undofile'`), and
jumps into a closed file are dropped (Neovim's default `'jumpoptions'` has `clean`). Restart Neovim
starts the numbers over, as a new Vim does.

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

(X4 is observed, not documented by Neovim as far as I found. Uncertain why; X5 works around it.)

### 1.1 Neovim reading a file by itself (for 2.3)

Probed headless with the plug-in's scripts, then in the test Eclipse (a temporary UI test, not
kept). "The hook" is option (d) of 2.3: a `BufReadCmd` limited to the open projects' folders that
reads the file with `lockmarks keepalt noautocmd silent read ++edit`, then deletes the empty first
line, with `'undolevels'` -1 and `'modified'` off.

| # | Experiment | Result |
|---|---|---|
| X11 | Today: `gf` from a document onto a workspace file without an editor | Neovim reads it: `BufReadPre`, filetype detection (`java`), the java ftplugin (`indentexpr=GetJavaIndent()`, `commentstring`, `suffixesadd`), editorconfig, the user's `FileType` autocommands (a test language server started). The plug-in sends Neovim back with no message; the buffer stays loaded and listed (`5 #h`) **with a swap file**. Its jump is gone from the jump list, so `Ctrl-O` does not go there. |
| X12 | X11, then that file's editor opened (what way B does) | The attach takes the buffer over: same number, swap file deleted, but filetype, ftplugin options and the started server stay. |
| X13 | Clearing `'filetype'` at takeover | `b:undo_ftplugin` removes Neovim's own ftplugin options; what the user's autocommands started stays. |
| X14 | Turning language servers off | `vim.lsp.enable(name, false)` misses later enables and direct `vim.lsp.start` (nvim-jdtls's way). Wrapping `vim.lsp.start` with a list of names stops all of them. Blocking only in document buffers fails: the buffer is not one yet when Neovim reads it, and a started server is reused. |
| X15 | The hook: `gf`, `:b` into a closed document, `:cc` (quickfix), `bufload()` (a picker's preview) | No `BufReadPre`/`BufReadPost`/`FileType`, no server, no undo step (`u`: "Already at oldest change"), the text is there. The takeover keeps the number and leaves no filetype: the file is like one Eclipse opened. Marks survive (`lockmarks`); the cursor is not on `'"` (the attach's `GO_TO_LAST_POSITION` puts it there). |
| X16 | The hook's read | Decodes like Neovim: latin1 (`fenc=latin1`), CRLF (`ff=dos`), a BOM (`bomb`), an empty file. `readfile()` instead does not decode (`caf<e9>`). **Creates a swap file** unless the hook turns `'swapfile'` off first. A missing file (a mark into a deleted file) fails with E484 inside the hook. |
| X17 | Outside the hook | A file outside the projects' folders is read by Neovim as usual; so is a sibling folder whose name starts the same (`proj2` beside `proj`). `:read file` uses `FileReadCmd` and is not affected. The plug-in's own buffers (`noautocmd call bufload`) never reach the hook. |
| X18 | Patterns | With `\` before each of `\ * ? [ ] { } , ~ $` (spaces need none), a project folder named with any of `[ack] {ly} , * ? $ ~ % # \ ' " ( ) + ^ ; = @ & ! | ä`, spaces or a tab matches, and a decoy sibling does not. Neovim matches the resolved path: the pattern must be built from the real path (`fs_realpath`), and then a file reached through a link matches. `'fileignorecase'` (on by default on Windows and macOS) makes the match case-insensitive. Patterns use `/` on every system (`:help file-pattern`). **macOS** (arm64): every check passes; `fs_realpath` turns `/var` into `/private/var` and an NFC folder name into APFS's NFD one, and a pattern built without it misses an NFD name. **Windows** (11, arm64): drive letters, `\` or `/`, other case and a lower-case drive all match once the pattern is written with `/` (a `\` pattern never matches); but a folder named `$HOME` misses (`\$` does not stop Windows from expanding it: the follow-up tries `[$]`), and a junction keeps its own path in the buffer name, so the pattern must be made from Eclipse's path as well as the real one. |
| X18b | The hook in the real Eclipse, other systems | macOS: as on Linux (the probe's hook left out `'swapfile'` off, so swap files appeared). Windows: after `gf` the hook stopped between its `:read` and its `1delete`, and Neovim then stopped answering; with `'swapfile'` off first it ran cleanly, twice. |
| X18c | Why: a stale swap file (Linux, test Eclipse) | With a swap file left for `B.java` and the hook's `'swapfile'` on, the hook's `:read` stops at Neovim's E325 ATTENTION question (mode `r?`), the buffer showing `{ "", "class B {", ... }` as on Windows. `noautocmd` also silences `SwapExists`, so nothing can answer it. With `'swapfile'` off before the read: no question. (Today's `gf`, without the hook, asks the same question for such a file.) |
| X18d | More Windows patterns | A folder named `$` followed by a set variable's name (`$HOME`, `$TEMP`) misses with every escape (`$`, `\$`, `[$]`); `${HOME}`, `a$b`, `$(x)` match. A junction keeps its own path in the buffer name: only patterns for both the junction path and the real path catch both. An 8.3 short name stays in the buffer name (`C-139B~1\...\BAA8E~1.JAV`), so a long-name pattern misses it; Neovim's `:8` does not shorten, so no pattern can be made for it. |
| X19b | The hook without patterns (Linux; sent to Windows and macOS) | A `BufNew` autocommand for every buffer compares the buffer's file, resolved (`fs_realpath`: links, junctions, 8.3 names, NFD) with `/` separators and lower-cased under `'fileignorecase'`, with the projects' resolved folders, and gives a buffer inside one a buffer-local `BufReadCmd`. Linux: `gf`, relative `:edit`, `:badd`+`:b`, `bufadd()`+`bufload()`, `:cc`, a `'B` mark read from shada, an unloaded buffer reloaded twice, a link, a missing file, every special folder name (`$HOME` included): all read by the hook, no `BufReadPre` or `FileType`; a sibling `Proj2` and `:help` untouched. macOS: the same, plus other case, and NFD folders opened by their NFC spelling (also a project folder that is NFD on disk, registered by its NFC spelling). Windows: the same, plus other case, a junction (the buffer keeps the junction path) and an 8.3 short name (`LONGFO~1\LONGFI~1.JAV`). Only folders named `$HOME` and `$TEMP` miss, and not because of the hook: on Windows `:edit` expands the variable even after `fnameescape()` (the buffer becomes `...\names\C:\Users\Joshua\home\f.txt`, which does not exist); `bufadd()` keeps the name literal. That is Neovim's own handling, the same without the plug-in. |
| X19 | `bufload()` of a project file in the real session | Its `BufEnter` comes from Neovim's autocommand window; Eclipse does not move. |
| X20 | A script's `:edit!` on a document (not typed: a typed `:e!` goes to Eclipse) | Today Neovim reads the file from disk into the document's buffer, which then differs from Eclipse's document, and runs filetype detection. With the hook, the hook reads it: same divergence, no filetype. The hook should leave a live document's reload to Eclipse. |
| X21 | Eclipse: finding the file | `findFilesForLocationURI` finds a file through a link to the project, and a file under a linked folder (links may be nested, at any depth) once per link. A file in a closed project is found but not accessible. A file made on disk since the last refresh is found but does not exist for Eclipse, and `IDE.openEditor` opens it **empty**: refresh it first. |
| X22 | Eclipse: which editor would open | `IDE.getEditorDescriptor`: `.dat`, `.zip`, `.jar`, `.pdf` (and `.java` without JDT) go to the system's external editor, `.png` and `.html` to the browser. Way B may open only an internal text editor; anything else is sent back as today. |

## 2. The plan, by part

### 2.1 Close: unload, not wipe

`DocumentBuffers.close` keeps its first half (forget the buffer in Java, `RELEASE_BUFFER` closes
its windows, return to the attached buffer). The wipe becomes a script `CLOSE_DOCUMENT_BUFFER`:

1. Remove the plug-in's buffer-local autocommands (`BufWriteCmd`, the safety net): they belong to a
   document with an editor. They move into an augroup `neovim4eclipse_document` so that they can
   be cleared for that buffer alone, and set again when the buffer is reused (today
   `DOCUMENT_BUFFER` would add them a second time).
2. `'buftype'` back to `''` (X4, X5), so the marks survive.
3. `nvim_buf_delete(buf, { force = true, unload = true })`, then `'buflisted'` off: `:bd`.

`b:neovim4eclipse_file` stays: it marks the buffer as a closed document's (the safety net's
`BufDelete` notice from step 3 reaches Eclipse after Java has forgotten the buffer, and is ignored,
as today for the wipe).

### 2.2 Open: reuse the closed buffer

`CREATE_DOCUMENT_BUFFER` today makes a new unnamed buffer when one of that name exists, so that two
documents never share one. A closed document's buffer (`b:neovim4eclipse_file` set, and not a live
document: unlisted, or not `acwrite`) is instead reused: the same options as a new one
(`nofile`, `bufhidden=hide`, no swap file), `bufload` if it is not loaded (X8 may have loaded it from
disk), listed again. Eclipse's text is pushed next, as for a new buffer, under `:lockmarks` (X10), so that the marks
stay on their lines. A live document's buffer of that name still gets the unnamed fallback.

### 2.3 Neovim moves into a file without an editor: Eclipse opens it (way B)

`:b 3`, `Ctrl-^` and `'A` into a closed document's buffer load it from disk (X8, X9); so do `gf`,
`'A` and `:b name` into a workspace file never opened in this session. Today the plug-in sends Neovim
back ("a closed file is undone", README). With B, `EditorAttachManager.followNeovimInto` opens that
file's editor instead, when the buffer is a file in the Eclipse workspace (as `:e` of a file without
an editor does, `EclipseFileCommands`), then follows as for an open file: the window that jumped
travels into the new editor (docs/design/WINDOWS.md, E2), so `Ctrl-O` there goes back. The attach
finds the buffer by name (2.2) and pushes Eclipse's text. Anything else Neovim enters (`:help`, a
scratch buffer, a file outside the workspace) is still undone, as today.

This is the quickfix plan's way B *(decided 2026-10-29, Q1: general now rather than closed documents
only)*: the mechanism and its risk are the same, so it is built once. Quickfix (phase 2 of
DIAGNOSTICS.md) then only adds the lists, `:Eproblems`, `:Etasks` and `:copen`.

**The risk, and the experiment before building** (DIAGNOSTICS.md section 5): Neovim has just read
the file from disk (`BufReadPre`, `BufReadPost`, filetype detection, the user's `BufRead`
autocommands, a swap file, an undo history starting there), while the plug-in fills a document's
buffer from Eclipse's text. Step 3 starts with a probe in the test Eclipse of what that buffer holds
when the attach takes it over: options, filetype, swap file, undo, cursor, and whether the user's
autocommands ran twice. Then the reuse in 2.2 turns that buffer into a document buffer.

**What the probes found, and the design** (1.1, *Q3 decided: d*). Neovim's own read runs the
filetype plug-ins and the user's `FileType` setup (a language server may start: X11), which the
plug-in's buffers never do. So Neovim never reads a project's file itself:

1. **Project folders.** Eclipse sends Neovim the locations of the open projects and of their
   linked folders (any depth), again whenever a project opens, closes, is created, deleted or
   moved, or a link is added or removed (a workspace listener, as `ProjectFolderExit` has).
2. **A `BufNew` hook, no patterns** (X19b). For each new buffer, Neovim compares the buffer's file,
   resolved with `fs_realpath` (links, junctions, 8.3 names, NFD), `/` separators, lower-cased under
   `'fileignorecase'`, with the folders resolved the same way. A buffer inside one gets a
   buffer-local `BufReadCmd`, kept when the buffer unloads, so a closed document's buffer has it
   too. When the folders change, existing buffers gain or lose it. Patterns were tried first and
   fail on Windows (X18d).
3. **The read** (X15, X16): `'swapfile'` off first (a stale swap file otherwise stops the read at
   E325: X18c), nothing for a missing file, then `lockmarks keepalt noautocmd silent read ++edit`
   and the empty first line deleted, with `'undolevels'` -1 and `'modified'` off. No filetype, no
   `BufReadPost`. When the buffer is the current one, the cursor goes to `'"` (where `:b N` of a
   kept buffer puts it; a mark, a quickfix entry or `Ctrl-O` moves it on afterwards, as Neovim does
   after any read).
4. **A live document's reload** (X20: `:edit!` from a script, not typed): the buffer is a
   document's (`'buftype'` `acwrite`), so the hook does not read the disk; it asks Eclipse to load
   the document's text into the buffer again.
5. **Following** (`followNeovimInto`): a buffer that is no document's but has the hook is a
   workspace file. On the UI thread: refresh it (a file made on disk since the last refresh would
   open empty: X21), find it (`findFilesForLocationURI`, the first accessible one), and open it only
   if Eclipse's editor for it is internal (never the system editor: X22); if what opened is not a
   text editor (an image), close it again. Then as for an open document: the window that jumped
   travels into the new editor and the attach takes Neovim's cursor. The attach reuses the buffer
   (2.2) and pushes Eclipse's text (the cursor keeps its line). Anything else, or a failure, is
   sent back as today.

`:e #` needs nothing: `HOST_COMMANDS` already gives `:e` of a file without an editor to Eclipse.

### 2.4 What else assumes the wipe

| Place | Today | With B |
|---|---|---|
| `wipe_left_over` (`DOCUMENT_BUFFER`) | wipes unloaded buffers named like a written file | already skips buffers with `b:neovim4eclipse_file`: unchanged |
| `HOST_COMMANDS` `edits_an_open_document` | loaded document buffers only | unchanged: a closed one is not loaded (or, after X8, not listed) |
| `:bd 3` of a closed file's number | E516-like "no editor" message | the same: nothing to close |
| `:ls` | closed files gone | closed files gone from `:ls`, shown by `:ls!` with `u`, as in Vim |
| Tests that wait for a wipe | `NeovimBufferIntegrationTest` (buffers a and b), `NeovimWindowsIntegrationTest`, `TwoEditorsEndToEndTest` (`:ls!` count) | rewritten to Vim's rules: same number after reopening, `:b N` reopens |

## 3. Steps (one commit each)

1. **Close unloads** (2.1), with the augroup. Lua specs: the number, `'a` and `'"` survive a close;
   the plug-in's autocommands are gone from the closed buffer. Integration tests adjusted.
2. **Open reuses** (2.2). Lua spec: `CREATE_DOCUMENT_BUFFER` returns the closed buffer's number.
   Integration test: close and reopen a document, same number, `'a` still there. UI test:
   `:ls` numbers after closing and reopening an editor.
3. **Neovim moving into a workspace file without an editor opens it** (2.3, way B): the probe
   first, then the change. UI tests: `:b N` and `Ctrl-^` into a closed file, `gf` and `'A` into a
   file never opened, each followed by `Ctrl-O` back; `:help` still undone.
4. **Docs**: README (`:ls`, `:bd`, "a closed file is undone"), WINDOWS.md, ARCHITECTURE, LEARNING
   (a walkthrough of a close and a reopen), CHANGELOG.

## 4. Open questions

- **Q1** 2.3's limit: *(decided 2026-10-29)* general now: any workspace file Neovim moves into
  (the quickfix plan's way B), not only closed documents.
- **Q2** The reopened editor's caret *(decided 2026-10-29)*: Neovim's `'"`, where Vim puts the
  cursor on `:e` of a buffer it kept (X3, X8), not where Eclipse opens a file. The attach of a
  reused buffer takes Neovim's cursor instead of pushing Eclipse's caret.
- **Q3** A workspace file Neovim reads by itself (1.1, X11): *(decided 2026-10-29: d, in its
  `BufNew` form; 2.3, "What the probes found")*. The others: (a) keep Neovim's read (filetype,
  ftplugin, the user's `FileType` setup); (b) clear the filetype at takeover (X13; what the user's
  setup started stays); (c) a hook on every read, which would redo Neovim's read by hand for files
  outside the projects. (d) was probed on Linux, macOS and Windows (X15-X22); the Windows hang was a
  swap-file question (X18c), and patterns failed on Windows (X18d), hence the `BufNew` form (X19b).
  Language servers then need no plug-in setting: none starts for a project's file, and the README's
  `vim.g.neovim4eclipse` check stays the way to skip them altogether.
