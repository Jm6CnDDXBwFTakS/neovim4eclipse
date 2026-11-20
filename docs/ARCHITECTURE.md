# Architecture

What the plug-in does and why. For the Eclipse and Neovim concepts it is built on (bundles,
`plugin.xml`, editors, key bindings, msgpack-rpc, `nvim_input`, buffer events), read
[LEARNING.md](LEARNING.md) first; [GLOSSARY.md](GLOSSARY.md) says which words are Eclipse's and which
Neovim's (an editor tab is no tab page).

## Goals

- Use **real Neovim** for modal editing (not a Java reimplementation of Vim).
- Keep Eclipse IDE services available on the same buffer (save, build, content assist).
- Remain understandable for a single maintainer: small surface, explicit threading, few singletons.

## Runtime components

```text
┌─────────────────────────── Eclipse UI thread ───────────────────────────┐
│  StyledText ──VerifyKeyListener──► KeyInterceptor                       │
│       ▲                              │ inputAsync (queue only)            │
│       │ setSelection / caret / text  │                                    │
│  BufferSynchronizer / Controller ◄───┼── listeners (mode, cursor, visual) │
└──────────────────────────────────────┼──────────────────────────────────┘
                                       ▼
┌──────────────────────── input executor (single thread) ─────────────────┐
│  NeovimSession.input / command                                          │
│       nvim_input / executeCommand / get_mode / get_cursor / execute_lua │
└──────────────────────────────────────┬──────────────────────────────────┘
                                       ▼
┌──────────────────── nvim --embed (core/rpc client) ─────────────────────┐
│  one buffer per document ◄── nvim_buf_set_lines / lines_event           │
│  ui_attach(TERMINAL) for input processing                               │
└─────────────────────────────────────────────────────────────────────────┘
```

## Key design decisions

### 1. Neovim owns the buffer (except while typing)

In normal, visual and operator-pending mode every key goes to Neovim and is swallowed; Neovim mutates
its buffer and Eclipse applies `nvim_buf_lines_event` as minimal edits (`LineRangeEdit`). Neovim
reports whole lines, but the edit is trimmed to the characters that change: Eclipse features that
track positions need that. In a linked rename (Alt+Shift+R), `ciw` on the name must arrive as
"replace the name"; "replace the line" would end the rename. An event without a changedtick is
dropped (`NotificationBridge.onBufLines`): it is `'inccommand'`'s preview of `:s`, which changes the
screen and not the buffer, and Neovim sends nothing when the preview ends, so applied it would stay
in Eclipse's document after Esc.

In **insert/replace mode Eclipse inserts the characters itself** (no flicker, content assist and IME
keep working). On `Esc` the document is pushed to Neovim (`BufferSynchronizer.pushDocumentToNeovim`)
and *then* `<Esc>` is sent. A push replaces only the lines that differ (`LuaScripts.SET_LINES_MARKED`), so it adds no
undo step when nothing changed and keeps the marks of untouched lines. A few keys (`p P x X s ~ . u <C-r>`) and every `:` command are followed by
a full re-sync instead of trusting line events alone (`NvimKeys.isBufferMutating`).

Changes **Eclipse makes by itself outside insert mode** (a format, a refactoring, Organize Imports,
Eclipse's undo, a revert or `:e!`) are pushed too (`BufferSynchronizer.pushLocalChanges`): a document
listener queues one push on the input thread, coalescing a burst of edits, and ignores the changes the
plug-in itself applies from Neovim. Without it, Neovim's next edit would be applied at stale line
numbers. Remote listening is paused during that push, so Neovim's echo cannot undo a newer Eclipse edit.
After the push Neovim's cursor is moved to Eclipse's caret, or to the start of Eclipse's selection:
Eclipse's undo selects the line it brings back, and inserting that line above Neovim's cursor would
otherwise leave the cursor one line too low.

**While Eclipse types in insert mode, Neovim's cursor reports are dropped** (`CaretSync.onNeovimCursor`).
Neovim's lines lack the typed text until `Esc`, so a caret move sent to Neovim comes back clamped to
its old line (`CursorMovedI`). After `O` above a class's `}`, a method signature and Enter, the caret
jumped from column 5 of JDT's new indented line to column 1, the length of Neovim's `}` there. No key
reaches Neovim while Eclipse types, so such a report is only that echo; in a Neovim-driven insert
(macro, block insert) the reports apply as usual.

Eclipse can also change the document **in reaction to a change applied from Neovim**: in a linked
rename, `x` on the name is copied to every other occurrence, inside the same `IDocument.replace`.
`BufferSynchronizer` counts the document changes while it applies one; more than one means Neovim
is behind, so the result is pushed, and Neovim's full-text syncs are skipped until then (they would
undo the copies).

The **first push into a new buffer** loads the file and is not an undo step (`LuaScripts.LOAD_WITHOUT_UNDO`,
Vim's `:help clear-undo`), so `u` right after opening a file does nothing, as in Vim.

### 2. Ordered async input

`VerifyKeyListener` must return quickly. Keys are enqueued on a **single-thread executor** so sequences like `ciw` stay ordered without blocking SWT.

### 3. Visual selection

When mode is `v` / `V` / Ctrl-V, after each key the state snapshot reads `getpos('v')` and `getpos('.')`. Characterwise and linewise
selections are mapped onto `StyledText.setSelection`; a blockwise selection is painted as an overlay (see "Blockwise visual").

**The mouse is Neovim's too** (normal and visual mode). `editor/NeovimMouse` puts a display filter in front of
the editor widget and swallows the left button's press, drag, release and double-click event (`event.type =
SWT.None`), so the widget neither moves its caret nor selects. Each becomes a `core/MouseInput` (the document
line and byte column of the character under the pointer, Shift and Alt as `S`/`A`), which the session hands to
`KeyInput.mouse`: `LuaScripts.MOUSE` finds the character on Neovim's screen with `screenpos()` and calls
`nvim_input_mouse`, then the state is read as after a key. Neovim does the rest: one click moves the cursor, two
select a word (from a bracket, up to its match), three the line (`V`), four a block, a drag selects, Shift+click
extends, `'mouse'`, `'mousetime'` and `<2-LeftMouse>` mappings apply, and a click while recording is recorded.

- *A click off Neovim's screen.* Neovim's window shows what the editor shows (section 9), so a clicked character
  is normally on its screen. It may not be with the viewport turned off (`vim.g.neovim4eclipse_viewport = 0`,
  Neovim's fixed 120 × 40 screen), past the end of the document (Neovim can scroll further than Eclipse), or for a
  moment while a scroll is on its way. The script then scrolls Neovim's window for a press; for a drag in visual
  mode it moves the cursor there (which extends the selection, as the drag would; scrolling first would lose the
  drag's start); a first drag goes to the nearest edge of Neovim's screen. Measured: about 0.2 ms per event, the state
  read included.
- *Drags are merged.* Mouse moves can come faster than Neovim answers; `util/LatestValue` keeps only the newest
  drag until the input thread sends it, and a release still comes after it.
- *Stays Eclipse's:* insert and replace mode and Eclipse mode (all of the mouse), Ctrl+click and Cmd+click
  (hyperlinks; on macOS also the context menu), the right and middle buttons, moves without a button (hovers).
- *Dropped:* an event while Neovim waits for the rest of a command (`d`, `f`): Neovim would not run the script
  until then.
- With `'mouse'` empty (or without `n`/`v`) a press only moves the cursor, as before the mouse was forwarded.

### 4. UI chrome

- **Mode status**: `ModeStatusContribution` in Eclipse's status bar; Neovim's status line under the
  text (`StatusLineRow`).
- **Caret**: block vs thin via SWT `Caret` size (`CursorStyleController`); Eclipse's own width comes back when Neovim leaves the editor.
- **Ex / search**: Neovim draws its own command line, prompts, pager and messages (its ui2, in
  floating windows), and the editor copies their cells onto its command line row, under the status
  line, like any float's (see
  "Command line" and "Messages and output" below). No widget takes the keyboard from the editor's
  text: every key goes on to Neovim, which edits the line itself.

### 5. Auto-attach

`EditorAttachManager` + `EarlyStartup` register `IPartListener2` so opening/activating a text editor attaches Neovim, once it is the page's active editor: Neovim follows the editor Eclipse makes active. An editor that only opens is left alone, such as the new half of Eclipse's *Toggle Split Editor* on macOS, where the first half stays active. One controller is active at a time (switching editors detaches/reattaches).

### 6. One Neovim buffer per document

`EditorAttachManager` keeps a `DocumentBuffer` per open `IDocument` (two editors on one file share
it) in a `core/EditorBuffers` (the bookkeeping, unit-tested without Eclipse) and passes it to the controller, whose attach push (`sync/DocumentPush`) calls
`NeovimSession.showBuffer` first. If that fails, the document is not pushed (it would overwrite the
buffer Neovim is in, another file's), but the editor listens for Neovim's changes again. Buffers are
listed, named after the file path, without a swap file or filetype detection, and `buftype=acwrite`:
Neovim never writes them itself; a write (`:w`, `:update`, `:bufdo update`, `vim.cmd.write()`) runs their
`BufWriteCmd` hook (`LuaScripts.DOCUMENT_BUFFER`), which sends the `eclipse_write` request with `v:cmdarg`
(`++p`) and waits: `EditorAttachManager.saveForNeovim` saves the document on the UI thread (waiting at
most 30 s) and answers `[error, message]` (`core/DocumentWrite`); the hook echoes the message, Vim's
`"a.txt" 12L, 140B written` (`util/WrittenMessage`), which Neovim does not give for an `acwrite` buffer.
The hook compares the written name with the editor's file (`b:neovim4eclipse_file`, not the buffer's
name, which `:saveas` and `:file` change): another name is a copy (`:w other.txt`, request kind
`"copy"` with the full path), which `editor/OtherFileWrites` writes in the editor's encoding, through
the workspace inside a project, never over unsaved changes in another editor, and without touching
the editor or `'modified'`. Some lines (`:5,10w part.txt`, `FileWriteCmd`) and appends (`:w >> log.txt`,
`FileAppendCmd`) send the lines between the marks `'[` and `']` (kinds `"lines"`, `"append"`; the text
cut by `util/TextLines`); both are refused for the editor's own file, saved only whole. `:saveas new.txt`
renames the buffer, then writes it under the new name: kind `"saveas"`, which `OtherFileWrites.saveAs`
carries out as Eclipse's Save As without the dialog (the document provider saves to the new input,
then `replaceInputKeepingBuffer` moves the editor, keeping the Neovim buffer). A `BufFilePost` check
puts the old name back after `:file new.txt` or a refused `:saveas`. Writes and renames under another
name also leave an empty, unloaded buffer of that name (Vim's alternate file); the hook wipes it once
the command is over, or an editor opened on that file later could not give its buffer that name.
`:wq`, `:x` and `:wa` stay Eclipse's commands (`util/HostCommand`): they close editors or save editors
that have no buffer. An error fails the write as a real failed write does
(`nvim_echo` with `err = true`, which works like `:echoerr`): Neovim shows it, `:w | q` stops before
quitting, and `:bufdo update` stops at that buffer, which stays `'modified'`. `'modified'` follows Eclipse's
dirty flag: `EditorAttachManager` listens to each editor's `PROP_DIRTY` and calls
`NeovimSession.setModifiedAsync` (`LuaScripts.SET_MODIFIED`), and every push of Eclipse's text sets it
again (writing lines marks a buffer modified, though a revert is not dirty). So `:update` writes exactly
the files Eclipse would save, skips the others, and `:ls` shows `+` for unsaved ones. Only the attached buffer's cursor reports reach Eclipse.

Neovim can change any of those buffers (`:bufdo %s/old/new/g`), and Eclipse can change a document
whose editor is in the background (a rename refactoring). Every document with a buffer therefore has a
`sync/UnattachedDocumentSync`, kept by `EditorAttachManager`, that carries changes both ways while the
document is not attached: `NeovimSession` routes the line events of a document buffer that is not
attached to that document's listener (`core/DocumentBufferPort`), which applies them with `asyncExec`
(the RPC thread must not wait, or `:bufdo`'s `BufEnter`s would queue up behind the changes); and each
change Eclipse makes to the document is pushed to its buffer (`setDocumentBufferLines`, which drops the
echo of its own write). While the document is attached, `BufferSynchronizer` does the pushing; on
detach, one push leaves the buffer equal to the document, and records the document's modification
stamp in its `DocumentBuffer`. Any line event Neovim sends for the buffer clears it; an attach whose
document still has that stamp skips its push (`DocumentPush`), so switching back to an unchanged
editor reads neither the whole document nor the whole buffer. Both sides changing the same document at once
is applied in arrival order; a Neovim change that no longer fits is logged and replaced by Eclipse's text.

Eclipse can also give an open editor a new input itself, and with it a new `IDocument`: a rename or
move of its file (`AbstractTextEditor`'s `elementMoved` calls `setInput`), File › Save As, or an
editor reused for another file. The text editor fires `PROP_INPUT`, which `EditorAttachManager`
hears on the text editor itself (a multi-page editor's page moves to the renamed file on its own,
while the multi-page editor keeps its input), and `onInputChanged` binds the editor to its new
document (`rebind`): the old document's controller and syncs stop, and the editor is attached again
if it was attached. When the editor moved with its file (the old file is gone, or the new input
came during a Save As: `editor/SaveAsWatch`, an execution listener on the Save As command, or
`util/SaveAsFrames`, which finds the editor's `doSaveAs` or `performSaveAs` on the call stack, for
the Save As paths without the command, such as the prompt for a file deleted while open) and every clone sharing the document moved too, the Neovim buffer moves to
the new document and is renamed after the new file (`NeovimSession.renameBufferAsync`,
`LuaScripts.RENAME_DOCUMENT_BUFFER`): its number, undo history and marks stay. The new name is
recorded in `DocumentBuffer` at once, so a Neovim started by Restart Neovim makes the buffer under
it. An editor reused for another file gives its buffer up instead, as that file's text must not get
the old one's undo history; that holds for a file with the same text too (two empty files), which
is why "moved" is not read from the text. The plug-in's own input changes (a new file's first `:w`, `:saveas`)
keep the buffer through `replaceInputKeepingBuffer`, which makes the syncs again for a document in
the background too.

A `BufEnter` autocmd reports buffer switches. Neovim's switch to another open document's buffer is
followed: its editor is activated and attached, keeping Neovim's cursor. When several editors show
that document, the first one opened is chosen. Commands such as `:bufdo`, `:argdo` and `:cdo` enter
every buffer in turn; `DocumentBuffers` remembers the buffer entered last, and a queued follow-up for a
buffer Neovim has left again is skipped, so Eclipse activates only the editor Vim ends in instead of
flicking through all of them. A switch to a file that has no editor (`gf`, `'A`, `:b 3`
of a closed file, a quickfix entry) opens that file's editor (`editor/ProjectFileEditors`: a
project's file as a workspace file, any other by its location, as *File › Open File...* does) and is
followed the same way; a switch to any other buffer (`:help`, a scratch buffer) is undone
(`returnToAttachedBufferAsync`). When
a document's last editor closes, its buffer is unloaded and unlisted, as by Vim's `:bd`, not wiped
(`closeBufferAsync`, `LuaScripts.CLOSE_DOCUMENT_BUFFER`), and the file's next editor gets the same
buffer back, with its number and marks (`LuaScripts.CREATE_DOCUMENT_BUFFER`;
docs/design/BUFFER_NUMBERS.md).

**Project files Neovim reaches by itself (docs/design/BUFFER_NUMBERS.md, 2.3).** Neovim reading a
file runs filetype detection and the user's `FileType` setup (a language server may start), which
the plug-in's buffers never do. So it reads no file of an open project itself: `editor/ProjectFolders`
sends the open projects' folders and linked folders (at the first attach, and again when projects
or links come or go), and `LuaScripts.PROJECT_FILES` gives every buffer inside one, found by
comparing resolved paths on `BufNew` (and on `BufAdd` for a buffer `BufNew` did not check:
`:vimgrep` makes its matches' buffers without it), a buffer-local `BufReadCmd` that reads only the
text (with `'swapfile'` off, so a stale swap file cannot stop it at E325). The one exception is
`:vimgrep`, which reads every file it searches itself, filetype detection included; its first match
stays loaded from that read. The search makes no swap files (`QuickFixCmdPre` turns the global
`'swapfile'` off until `QuickFixCmdPost`): where one could not be made (E303), Neovim stopped at a
prompt for every file searched. `eclipse_buf_enter` carries such a
buffer's file as its fourth value, for Eclipse to open; a background job finds and refreshes the
workspace file (a refresh waits while a build runs), then the editor opens on the UI thread. The
folders are walked and sent by one job, so the walks never run on the thread of a change and never
overtake each other. Every document's buffer, in a project or not, has a `BufUnload` and a `BufReadCmd` of its own
(`LuaScripts.DOCUMENT_BUFFER`): a script's `:edit!` gets its text and marks back at once instead of
the file from disk, so the cursor keeps its line, and Eclipse attaches the buffer again with the
whole buffer sent first, so a change Neovim made meanwhile reaches the document (`eclipse_reload`,
`NeovimSession` "re-attach after a reload"). A buffer loaded empty later (after a plug-in's
`:bunload`) is filled from the document (`reloadDocumentAsync`).

**A window per editor (docs/design/WINDOWS.md).** Each editor has its
own Neovim window, alone in a hidden tabpage (`'showtabline'` 0), so that it keeps its own cursor,
scroll position, folds and jump history between editor switches, and two editors on one file have a
cursor each. `EditorAttachManager` keeps a `core/EditorWindow` token per editor part, as it keeps a
`DocumentBuffer` per document, and the attach push (`DocumentPush`) shows it;
`NeovimSession.showWindow(buffer, window, folder)` opens the window at first use
(`nvim_open_tabpage` without entering it, its jump history cleared, `:lcd` to the folder once) and
enters it afterwards; `closeWindowAsync` closes it, and `travelWindow` swaps two editors' windows for
`Ctrl-O` across files. `core/EditorWindows` is the bookkeeping (which token holds which window id),
`DocumentBuffers` the Neovim side, through the Lua module `LuaScripts.WINDOWS`, which does to windows
that are not current what it does with autocommands off. Cursor reports, `BufEnter`, `WinScrolled`,
the wrap `OptionSet` and the state snapshot carry the window id, and only the attached window's count
(`DocumentBuffers.isAttached(winid, bufnr)`). When Neovim jumps into another open document,
`EditorAttachManager.followNeovimInto` queues the travel (`travelWindowAsync`) before attaching
that document's editor: the editors swap windows, and the editor jumped into, if it had none yet,
takes the window. The folder (`EclipseFileCommands.windowFolder`, the project root) is set when
the window opens, not on every attach; an untitled document's window keeps the folder it copies
from the editor attached before, or gets the home folder. Without a token (`showBuffer`) a
document is shown in the *home window*, the one Neovim starts with, which only tests use now. A
window closed in Neovim by itself sends `eclipse_window_closed` (`WinClosed`) and is opened again
at its editor's next show; closing an editor closes its window (`closeWindowAsync`) before its
buffer may be unloaded. Vim's window commands: `:close` and `:tabclose` are host commands that close
the editor (`util/HostCommand`, `QUIT`); Vim's splits are Eclipse editors in stacks of their own
(`:split`, the `Ctrl-W` keys, `:only`: `LuaScripts.WINDOW_COMMANDS` sends them through the host
commands' `translate` hook as `:Eclipse window`, and `editor/EditorSplits` does them to the editor
area; [design/SPLITS.md](design/SPLITS.md)); the tab commands are refused through the `refuse`
hook; a `WinNew` safety
net closes any other window that is not the plug-in's, floating or special, and the quickfix,
location list and preview windows, which Eclipse cannot show (the quickfix and location list
windows are then shown the Eclipse way, section 8); a floating window is closed too when it opens
with the focus (a picker's prompt, `:Lazy`), since Eclipse does not draw it and it would take the
keys, and so is one the user's config leaves the focus in at start (lazy.nvim's installer), before
the home buffer goes into it (`LuaScripts.LEAVE_START_FLOAT`); in a floating window the user entered later, `:q` and
`:close` are left to Neovim (the host commands' `leave_to_neovim` hook).

**The focus and the attached document.** Two things that used to be one: the *focused window*,
Neovim's current window, and the *attached document*, the text editor whose buffer and caret
Neovim mirrors. What a terminal Neovim shows for the current window, whichever it is, follows the
focus: the mode, the recording register, the status line and messages, command output, the `:`
line, the command-line window, floating windows (ui2's and the popup menu among them), and the host commands and
IDE actions a key asks for. `core/FocusRouter` hears these once for the session and hands each to
the one `core/FocusTarget` that has the focus (`NeovimSession.focus`); a text editor's controller
takes it when it attaches and gives it up when it closes. The document's own events (cursor,
selection, folds, wrap, top line, search, highlights, buffer lines) stay with the attached editor,
filtered by window and buffer as above. While a text editor has the focus the two are the same
editor, as before. A page's window (opened by `NeovimSession.openPageWindow`, quietly, with a page's options and
Neovim's own IDE keys: design/WINDOWS.md, "Pages' windows") can take the focus without the
document (`NeovimSession.focusPageAsync`, `DocumentBuffers.focusPage`): Neovim enters that
window, its `BufEnter` is known as the focus moving (not undone; every `BufEnter` case is
`util/EnterDecision`, and a file Neovim puts in a page's window goes to its own editor while the
page gets its buffer back), its cursor reports are no caret's, and the
caret's calls (`setCursor`, `getCursor`) name the attached window instead of Neovim's current one.
`focusAttachedAsync`, an editor shown, or the page window's close give the focus back.
A host command carries the window it ran in (`HostCommand.origin`, the fourth value of
`eclipse_command`), and `util/CommandOrigin` is the one rule for whose it is: a page with the
focus takes only its own window's commands, a text editor every command not from a page's window,
and one without an origin goes to the focus as before. So a `:q` typed in a page and read after a
click back into the code is dropped instead of closing the code. What a page does with a command
from its own window is `util/PageCommands`: `:q` closes the page, `:split` makes a second view of
it, `:e file` opens the file in the code's tab group, `:wa` is done as from the code, `:wq` is
refused with Vim's E382. A page's window is drawn from its grid (`NeovimSession.drawPageWindow`,
`util/Grids.pagesIfChanged`, one snapshot per page to the page grid listeners, its clicks through
`pageMouseAsync` on that grid); a page in a tabpage that is not current gets no redraws from
Neovim and keeps its last cells, so it changes only while it has the focus. The overlays a text
editor shows (floats, ui2's line among them, and the `q:` panel) take any SWT `Canvas` as
their host, sized by its line and character (`OverlayFrame.lineHeight` and `charWidth`: a
`StyledText` line or a page's grid cell), so a page shows them over itself, Neovim's command line
included. ([GLOSSARY.md](GLOSSARY.md) has the words.)

### 7. Eclipse is Neovim's clipboard provider

Neovim reads and writes the system clipboard only through a *provider*, the `copy` and `paste`
functions in `g:clipboard`. Unless the user's config sets `g:clipboard`, `LuaScripts.SETUP` installs one
whose copy sends the `eclipse_clipboard_copy` notification and whose paste is the
`eclipse_clipboard_paste` *request*: Neovim waits for its answer. `core/ClipboardRegisters` maps `"+`
to the clipboard and `"*` to the primary selection, joins and splits lines, and remembers what Neovim
copied so that it pastes back with its type (lines, characters, block). `editor/ClipboardBridge`
reads and writes SWT's `Clipboard` on the UI thread.

A paste is the one place where Neovim waits for Eclipse, and the answer needs the UI thread. So the
request runs on the RPC thread and waits for the UI thread at most 2 s; after that it answers "nothing
to paste". Without the bound, a UI thread that waits for Neovim and a Neovim that waits for the UI
thread would wait for each other forever.

The `TextYankPost` hook still copies every other yank to the clipboard (`eclipse_yank`), except yanks
into `"+`/`"*` (the provider already wrote them) and all yanks when `'clipboard'` contains `unnamed`
(then every yank goes to the provider). One writer per yank: two would race, and a linewise yank
could come back charwise.

Notifications and requests both reach `NotificationBridge` (`notification`, `request`, the two
methods of `core/rpc/Incoming`) through `NeovimConnection.setIncoming`, which pick the handler by
name. `core/rpc/RpcChannel` delivers them on its reader thread, in arrival order, and answers
each request exactly once.

### 8. Eclipse's problems are Neovim diagnostics

Eclipse finds the problems (compiler, builders, language servers); Neovim moves between them. Each
document with a buffer has a `sync/DiagnosticsSync` that reads its editor's annotation model, keeps
the annotations that are problems (`editor/ProblemAnnotations`: subtypes of the platform's error,
warning and info types), converts their offsets to lines and UTF-8 byte columns
(`util/DiagnosticPositions`), and sends the list with `setDiagnosticsAsync`;
`LuaScripts.SET_DIAGNOSTICS` puts it in the namespace `neovim4eclipse` with `vim.diagnostic.set`.
Neovim's own `]d` / `[d` then work, and the plug-in maps no key. Changes are debounced (300 ms) and
an unchanged list is not sent again: Neovim's diagnostics move with edits by themselves (extmarks).
`SETUP` makes the namespace draw nothing (Eclipse draws the problems) and, unless the user's config
has one, sets `vim.diagnostic`'s `on_jump` hook to echo the message after a jump, which ui2 shows like any message. Design and decisions: [design/DIAGNOSTICS.md](design/DIAGNOSTICS.md).

The workspace's problems and tasks become Neovim's *quickfix list* on request. `:Eproblems` and
`:Etasks` are user commands of `LuaScripts.QUICKFIX` that only send `eclipse_quickfix`;
`editor/QuickfixLists` reads the markers in a job (`findMarkers`, milliseconds for thousands) and,
on the UI thread, the open editors' annotation models (their problems, with columns, as
`DiagnosticsSync` reads them), then `util/QuickfixEntries` filters, sorts and flattens them, and
`setQuickfixAsync` runs `SET_QUICKFIX`, which reuses the list of that kind in the stack (found by
its `context`, never its title) and updates it with `setqflist()`'s action `'u'`, keeping the
entry the user is on. Neovim walks the list itself (`]q`, `:cnext`); a jump into a closed file
opens its editor through the project-files hook (section 6). While the list is current it follows
changes (marker deltas, a document's buffer coming or going, `DiagnosticsSync`'s sends), debounced
500 ms; Neovim has no event for a change of the current list, so the job asks first
(`QUICKFIX_CURRENT`). `:copen` reaches Eclipse through the `WinNew` safety net of section 6, which
calls the quickfix module to show the Problems or Tasks view (`eclipse_show_view`) or the list's
`:clist`.

### 8b. File paths: one rule on both sides

The same file reaches the plug-in in many spellings: `C:\WS\a.txt` and `c:/ws/A.TXT` on Windows,
`/var/...` and `/private/var/...` on macOS (and any case there), a Windows junction or 8.3 short name,
`..`. So paths are compared by one rule: absolute, links resolved (`realpath`; a file not there yet,
its nearest existing folder's, so a new folder under a link such as macOS's `/home` resolves too),
`/` separators, `.` and `..` resolved, lower case where file names ignore case (Windows, macOS).
Neovim's side is `LuaScripts.PATHS` (module `neovim4eclipse.paths`, run first; used by
`PROJECT_FILES`, `HOST_COMMANDS`, `DOCUMENT_BUFFER`, `LEAVE_FOLDER`, and the specs' `t.same_file`),
Eclipse's `util/FilePaths` (`:wq name`, shortened names). Both take the platform as
a parameter and pass one table of cases, `tests/unit/src/test/resources/path-cases.tsv`
(`paths_spec.lua`, `FilePathsTest`), so the Windows and macOS rules run on Linux and CI, and the two
sides cannot disagree. A path's workspace file has one lookup, `NewFiles.workspaceFileAt` (as given,
then with links resolved; Eclipse's own lookup resolves symbolic links and Windows case and short
names, the retry covers junctions; last, each project or linked-folder location Neovim was told
about, compared by the rule and looked up under its recorded spelling, for a location Eclipse
recorded through a junction and a path that is the real one). Found the hard way: before this, four Lua copies and two Java
lookups each had part of the rule, and each platform's fix landed in one of them.

### 9. Neovim's window is the editor's size and scroll position

Each editor has its own Neovim window (section 6, "A window per editor"); "Neovim's window" here is
the attached editor's, the current one. The UI has one grid, the attached editor's size: only the
current tab's window is resized at once, and an editor's window becomes current at its attach,
which sends the size. Its top line, `'wrap'` and saved `'linebreak'` values stay in the window
between switches; Neovim's top lines are ignored while an attach places Neovim's cursor, so the
editor does not scroll to the window's remembered view and back.

The plug-in draws nothing of Neovim's screen, but Neovim's screen commands (`H`, `L`, `Ctrl-D`,
`'scrolloff'`) count its rows, so they must be the editor's. `editor/ViewportFollower` measures the
attached editor's text area in cells (width over the font's average character width, height over
the line height) on every resize and paint, and sends a changed size with `setEditorSizeAsync`. The
session asks `LuaScripts.VIEWPORT_OVERHEAD` what Neovim draws besides the window (status line rows,
number and sign columns, the user's own settings), adds it (`util/ViewportSize`), and calls
`nvim_ui_try_resize`; an `OptionSet` on such an option (`:set number`) makes it resize again. With
no editor attached the last size stays. The same follower keeps `'wrap'` and Eclipse's word wrap
one setting (`SET_WRAP` one way, the `eclipse_wrap` notification the other, neither echoed), with
`'linebreak'` while wrapping. `vim.g.neovim4eclipse_viewport = 0` keeps the fixed 120 × 40 screen.

Neovim decides where its window is scrolled, and the editor follows. The `CursorMoved` report
(`eclipse_cursor`) carries `line('w0')`, which Neovim has already updated then, and a `WinScrolled`
autocmd sends `eclipse_topline` for scrolls that leave the cursor (`Ctrl-E`, `zt`, a resize).
`NotificationBridge` passes the top line on *before* the cursor of the same report, so the
controller's UI queue scrolls the editor (`ITextViewer.setTopIndex`, in document lines, a fold's
caption where Neovim's top line is hidden) and then places the caret, which is then on screen and
causes no reveal of its own. The other way round is `LuaScripts.SET_TOPLINE` (`winrestview`, which
keeps the cursor visible): on attach; and at Esc, after Eclipse scrolled alone while it typed, in
mode `show` (a cursor outside the view is dragged in), as a last step once Neovim's cursor is final
(`KeyInput`'s leave-insert view hook). While Esc is handled, Neovim's cursors and top lines are
ignored: the replay of the typed text runs in Neovim's old view, and its cursor would make Eclipse
reveal the old caret. When the editor scrolls by itself in normal or
visual mode (scroll bar, a reveal), the follower sees a top line other than the one last agreed at
the next paint and sends it with `scrollToAsync` (newest only); the same script then drags the
cursor into the view as `Ctrl-E` does. The vertical wheel does not scroll the editor at all in
those modes: `editor/NeovimWheel`, a display filter, swallows it and sends its lines
(`util/WheelScroll`, SWT's `count`) with `scrollLinesAsync` (added up) to `LuaScripts.SCROLL_LINES`,
which steps the top line as `Ctrl-E` does; the editor then follows Neovim's top line. A top line from Neovim becomes the agreed one once
applied, so it is not sent back. Design and decisions: [design/VIEWPORT.md](design/VIEWPORT.md).

### 10. Page editors: windows of the editing Neovim

Some of what Neovim shows has no Eclipse editor behind it: the `:messages` history, a
`:checkhealth` report, the help pages. A page editor shows each, an Eclipse editor in the editor
area with the code, as Vim shows them in a window of their own. Each is a window of the editing
Neovim itself, the one behind the code, on a buffer of its kind: the messages buffer the plug-in
fills, or the buffer Neovim made for the report or the help page. One Neovim, so registers,
search history, colors, mappings and the user's own help pages are the code's: `y` in a page and
`p` in the code just work, and `Ctrl-]` in help follows the user's plug-ins' pages, as in a
terminal.

```text
:messages ── msg_history_show ──▶ ViewText ──▶ PAGE_TEXT fills neovim4eclipse://messages ──▶ page window
:help, :checkhealth ── WinNew net takes the window ──▶ eclipse_view_page(kind, buf, line) ──▶ PageBuffer ──▶ show_page: page window
page window ── its grid (ext_multigrid) ──▶ util/Grids ──▶ GridSnapshot ──▶ editor/WindowPane
the page editor ── keys, mouse, size ──▶ NeovimSession (the editing Neovim's input thread) ──▶ the page window
```

- **What reaches it**, in the editing session's view-content listeners
  (`NeovimSession.addViewContentListener`, which `Activator` points at `editor/NeovimPageOpener`;
  `util/ViewContent`, sealed: `ViewText` or `PageBuffer`): `:messages`, from its own UI event
  (`msg_history_show`, see "Messages and output"), and the buffers the `WinNew` net of
  `LuaScripts.WINDOWS` takes from their windows. A `:checkhealth` report, however it opens (a new
  tab, a split with `:vert`, a focused float with `vim.g.health`'s style `'float'`), passes that one
  net with the report already in its buffer; a help window (`:help`, `:helpgrep`, `:cnext` through
  its list, `:vert` or `:tab help`, a plug-in's `vim.cmd.help`) passes it with `'buftype'` `help` set.
  The net keeps the buffer (`'bufhidden'` hide: a float's would be wiped; a report also unlisted, so
  `:ls` and `:bnext` never meet it, and renamed `health://<number>`, since Neovim's next
  `:checkhealth` wipes whatever is named `health://`, which would close the page's window), goes
  back to the window it came from, closes the new window, and sends `eclipse_view_page(kind, buf, line)`: the kind, the buffer, the line the cursor
  was on. `NotificationBridge` passes on the kinds `help` and `checkhealth` only, with a buffer and
  a line of 1 or more (`util/PageBuffer`).
- **The report is matched** by all three marks Neovim gives it: filetype `checkhealth`, a `nofile`
  buffer, the name `health://`; so a file the user set to that filetype is never taken. The net
  goes back to the window the report was opened from before it closes the report's window: the
  window last left (a `WinLeave` autocmd keeps it; by `WinNew` Neovim is already in the new one),
  kept per new window, since a check scheduled for an earlier window may be the one that finds it:
  closed while current, its tab would hand Neovim to the next tab, another editor's.
- **The page window** (`WINDOWS.open_page`, `show_page`): one Eclipse editor, one Neovim window,
  alone in a hidden tabpage as an editor's is, opened quietly with a page's options, and the IDE
  keys (`gd`, `K`, the `gr*` keys) Neovim's again in its buffer. A second command of a kind shows
  in the kind's editor used last: the messages buffer is filled again; a new help page or report
  goes into the same window (`show_page` changes its buffer, the topic's line at the top as `:help`
  puts it). An old report is wiped once no window shows it; old help pages stay, as in Vim, for
  `Ctrl-T` and `Ctrl-O`.
- **One check for what a page shows** (`WINDOWS`' `page_ok`), passed by every way into a page
  window (`open_page`, `show_page`, `follow_page`, the net's hand-over): a help page is a `'buftype'`
  `help` buffer, a report a `checkhealth` scratch buffer, and never a document's buffer (marked
  `b:neovim4eclipse_file`, with its hooks on: `WINDOWS.is_document`; a closed editor's buffer keeps
  the mark but not the hooks, so its help is a page again). `:help` or `Ctrl-]` on a help file open
  in an editor reuses the document's buffer and makes it read-only help; as a page it would take the
  page's `q`, keys and write hooks into the code's editor, and left as help Eclipse's edits would
  fail in Neovim and the texts drift apart. So it is never a page: the window closes, the buffer
  gets its document's settings back (`acwrite`, changeable, listed, and its own `'tabstop'` and
  `'iskeyword'`, which `:help` sets to help's; `SET_INDENTATION` and `DOCUMENT_BUFFER` keep them),
  again once the command is over (on the `Ctrl-]` route Neovim sets help's `'iskeyword'` after
  `BufEnter`), and a message says the help is open in its editor. `:help` reads nothing from disk
  for a loaded buffer: the document's text, undo history and marks stay.
- **Help follows help**: in a help page's window, `Ctrl-]`, `:help` typed there, `Ctrl-T` and
  `:cnext` through a `:helpgrep` list put another help buffer in the same window, as in Vim's help
  window, with no new window for the net. `SETUP`'s `BufEnter` asks `WINDOWS.follow_page`, which
  makes it the page's buffer (only a `buftype=help` buffer, only in a help page's window), and
  tells Eclipse in `eclipse_buf_enter` (`DocumentBuffers.onEntered`, which records it). Any other
  buffer in a page window is put back (`util/EnterDecision`, `restore_page`), and a file goes to an
  editor of its own: `gf` in help opens the file beside the code.
- **Nothing here writes a file**: the messages buffer is `nofile`; a report is `nofile` too; a help
  file is a real file, `'readonly'` and `'nomodifiable'`, but `:w!` or `:w x` would still write it.
  So every page buffer gets the same write hooks (`PAGE_TEXT`'s own for messages, `page_buffer` in
  `WINDOWS` for the others): `BufWriteCmd`, `FileWriteCmd` and `FileAppendCmd` answer Vim's E382
  and write nothing. Two deliberate commands still work, as they skip those hooks by design:
  `:noautocmd w x` and `:w !cmd`. `q` closes the page editor (`:Eclipse quit`), unless the user
  mapped `q` for the buffer.
- **The pane** (`editor/WindowPane`): it draws the page window's grid (`NeovimSession.drawPageWindow`);
  when it gets the keyboard the page takes the focus (`focusPageAsync`, Neovim's screen sized to
  the page with `setEditorSizeAsync`), and the focus events come to it (`core/FocusTarget`). Below
  its text the pane draws one row, as Neovim lays out its screen: the window's status line, the
  screen's (grid 1's) row below the page's window with its colors (`util/Grids.pageStatusIfChanged`).
  Under it the command line's row (`NeovimSession.commandLineRows`: 1, or 0 with a user's
  `'cmdheight'` 0), where ui2's line is drawn,
  as in code; the status row is a child of the canvas, placed above that row (`placeRows`), and the
  page window gets the canvas's rows less those two (`sendSize`). No widget
  takes the keyboard from the page's canvas, so every key is Neovim's and Neovim edits the line
  itself. Messages, output and floats are drawn over the canvas; the mode goes
  to Eclipse's status bar through one item for all page editors of a status bar
  (`editor/PageStatus`, id `PAGE_ID`), made by the first, fed by the one with the focus, removed by
  the last. Keys go through `util/KeyMapping`, the mouse through `editor/GridMouse` (shared with the
  `q:` panel), the size through `util/ScreenInput.cells`. `GridCanvas.Look` gives it the text font
  and the colors of the editor the content came from until Neovim's own arrive.
- **The attached text editor** stays attached meanwhile; while it does not have the focus its own
  size does not reach Neovim (its viewport's `sendSize`), and activated again without the focus it
  takes the focus back (`NeovimEditorController.takeFocusBack`, which sends its size again),
  decided on the router's target, which the UI thread itself sets. Every way back to the attached
  window (`DocumentBuffers.returnToAttached`: the attached document closed under the page, Neovim
  gone elsewhere by itself) enters the focused page's window again, and a page window Neovim closed
  by itself closes its editor (`NeovimSession.addPageClosedListener`). Entering the page gives its
  window the attached window's folder (`FOCUS_WINDOW`), so `:e b.txt` typed there means the code's
  `b.txt`. While a page has the focus, a host command from a floating window entered from it (a
  picker's) is dropped: its origin is not the page's window (`util/CommandOrigin`).
- **Commands typed in a page** are decided by `util/PageCommands` and carried out by
  `editor/PageCommandsRun`: `:split` opens a second editor of the kind (`NeovimPageInput.another`)
  with a window of its own on the same buffer (`WINDOWS.open_page_like`), window commands run with
  the page as their origin (`EditorSplits.Origin`), so `Ctrl-W k` from a page below the code
  reaches the code's editor, file commands as from the attached text editor. Ctrl+W itself would
  be Eclipse's Close Editor on Linux and Windows (M1+W in the window's context, dispatched before
  the canvas sees the key), so while the canvas has the keyboard the editor activates a key context
  of its own (`NeovimPageEditor.KEY_CONTEXT_ID`, a child of the window's context, so its binding
  wins), where Ctrl+W runs the plug-in's Ctrl-W command; `SendKeyHandler` sends it to the page
  editor when it is the active part.
- **The page editor** (`editor/NeovimPageEditor`, `plugin.xml`: `org.eclipse.ui.editors`, offered
  for no file; opened on anything but its own input it refuses) is an Eclipse editor that is no
  text editor, around its `WindowPane`: the editor gives it a parent, shows its title, and turns on
  the key context. One kind per editor (`util/PageKind`, `editor/NeovimPageInput`, equal by kind),
  so a second `:help` reuses the help editor. The plug-in attaches no Neovim to it
  (`EditorAttachManager.textEditorOf` is `null`), as to any editor that is no text editor, so going
  back to the code needs nothing. Restart Neovim closes every page editor
  (`NeovimPageEditor.closeEditingPages`): their windows were the old Neovim's.
- **Where it opens** (`editor/NeovimPageOpener`, the rule in `util/PagePlacement`): the kind's
  editor used last, or any open one; else a tab in another page editor's stack; else a new stack
  below the editor the content came from (`EditorSplits.intoNewStack`, the same model steps as
  `:split`). It takes the keyboard.
- **Not restored**: its input is never saved (`NeovimPageInput.getPersistable` is `null`), but
  Eclipse keeps its tab in the saved layout and brings it back as a placeholder that shows nothing
  (Linux, macOS, Windows). `NeovimPageEditor.closeLeftovers`, at the start
  (`EditorAttachManager.installWorkbenchListeners`), hides every model part tagged with the editor's
  id, the tag a placeholder keeps when its own id is Eclipse's empty-tab id. One place every start
  passes, so it holds after a crash too.
- **Not a `Ctrl-O` stop**: Eclipse records a Back entry for every editor activation; without a
  location of its own an editor's entry is `null`, which says nothing. So the page editor gives one
  (`NeovimPageEditor.PageLocation`, one entry per editor), and `util/HistorySteps` passes over the
  entries whose input is a `NeovimPageInput`, as over the plug-in's own (`NavigationHistorySteps`).
- **An editor on a help file** (a plug-in's repository with its `doc/myplugin.txt`): a help buffer
  of that name that no window shows is wiped first, so the document gets the file's name
  (`CREATE_DOCUMENT_BUFFER`); while the help editor shows that page, the document's buffer stays
  unnamed instead, as for any special buffer.
- **`:checkhealth neovim4eclipse`** (`core/HealthCheck`, `LuaScripts.HEALTH_CHECK`): `:checkhealth`
  finds checks only among runtime files, so a stub `lua/neovim4eclipse/health.lua` is written at
  every start into `<plug-in folder>/runtime`, which is appended to the editing Neovim's
  `'runtimepath'`; the check itself is sent from Java and put in `package.loaded`, where `require`
  finds it first. The folder also holds the plug-in's help page, `doc/neovim4eclipse.txt`, and its
  `doc/tags`, written by Java beside it (`core/PluginHelp`), so `:help neovim4eclipse` finds it;
  nothing else, so it adds no script that runs by itself. A folder whose name holds a comma is
  refused (`HealthCheck.fitsRuntimepath`): `'runtimepath'` would split it into two entries, the
  second relative.
- **What runs**: `:checkhealth` runs in the editing Neovim, with the user's plug-ins' checks, and
  blocks it while it runs (under a second for a small config), as in a terminal. A help page runs
  what Neovim runs for help there: the user's `ftplugin/help.*` and `FileType help` autocommands,
  a treesitter parser for a code block's language. That is the user's own config, which their
  terminal Neovim runs on the same pages.


## Not done

- **New-file buffer, shared editors.** A new file's editor is opened by location and switched to the
  workspace file at its first `:w`, which then releases the file buffer Eclipse leaves behind
  (`NewFiles.releaseLeftOverBuffer`, see [NEW_FILE.md](design/NEW_FILE.md)). The switch is skipped
  when two editors show the new file (*Window › Editor › Clone* before the first `:w`), so closing
  them leaves that buffer connected; after the file is deleted, `:e` of the same name then shows the
  old text in a clean editor (nothing is lost: a `:w` writes what is shown). The fix would release
  the buffer under the unnormalized location when an editor opened by location closes, its file now
  exists in the workspace and no other editor uses it (a hook in `EditorAttachManager`'s part-closed
  handling).

## Threading rules

| Thread | Allowed |
|--------|---------|
| SWT UI | Widget updates, `VerifyKeyListener`, the command-line box, all `IDocument` edits and reads (a push from the input thread takes its snapshot of the text with `syncExec`) |
| input executor | All blocking `await(...)` RPC |
| RPC reader (`RpcChannel`, one per session) | Completing the futures; dispatch to listeners; schedule UI work with `Display.asyncExec`; answer Neovim's requests (a clipboard paste waits for the UI thread at most 2 s, a save at most 30 s) |

Never call `await` on the UI thread for key handling.

Two bounded exceptions, each waiting on purpose:

- **Starting the editing Neovim.** The first editor's attach, and the next one after Neovim quit
  or was restarted, starts Neovim on the UI thread (`Activator.getOrCreateSession`, from
  `EditorAttachManager.attach`): the hash of the dedicated Neovim's files, the process, the
  handshake, the user's `init.lua` and the plug-in's setup scripts. Measured without an
  `init.lua`: about 0.2 s on Linux, 0.5 s on macOS, 0.5 to 0.6 s on Windows for the first start;
  0.05 to 0.3 s for a restart. The steps after the hash are bounded by the connection's limits:
  5 s for each request while opening, 10 s for the user's `init.lua` (`NeovimConnection`), then
  the RPC limit for each setup script. The hash has no limit of its own: it reads the dedicated
  Neovim's files once, so its time grows only with their size and the disk (the figures above
  include it). It blocks on purpose: while the UI
  thread waits, the keys typed meanwhile wait in the system's queue and reach Neovim in order once
  the editor is attached (`dd` typed while Neovim starts deletes a line). Keys typed before that,
  while Eclipse is still opening the editor, are Eclipse's own, as before any attach. A start in
  the background would leave the editor to Eclipse's own editing meanwhile, and a progress dialog
  would take the keys itself. Meanwhile the cursor is busy (`util/BusyStart`). A message in the
  status bar cannot be shown: GTK and Cocoa paint only from the event loop, which the start blocks
  (only Windows paints on `Display.update()`), and running the loop would deliver the keys.
- **Before a project delete or close**, `editor/ProjectFolderExit` waits at most 1 s for Neovim
  to leave the project's folder (see "Working folder" above).

`BufferSynchronizer`, `UnattachedDocumentSync` and `CaretSync` hand work to the UI thread through `core/UiExecutor`, not
`Display` directly: the editor passes `editor/SwtUiExecutor`, tests pass `UiExecutor.IMMEDIATE`
or a fake that holds the work back (for example, to close the editor while a Neovim change waits).

## Dependency stack

- **msgpack-core** (MessagePack), under the plug-in's own msgpack-rpc client in `core/rpc`
  (`docs/design/RPC_CLIENT.md` tells how it replaced neovim-java, Jackson and SLF4J). The only
  third-party jar the plug-in embeds.

Jars live under `plugins/.../lib/` and on `Bundle-ClassPath`. They are copied there by the build
(version-less names); their versions are defined only in the root `pom.xml`.


## Normal-mode movement and search

Keys are forwarded to Neovim with {@code nvim_input} on an ordered input thread.
Counts and multi-key sequences rely on Neovim typeahead across sequential RPCs.

| Category | Examples |
|----------|----------|
| Character | `h j k l` |
| Word | `w W e E b B` |
| Line | `0 ^ $` |
| File | `gg G` |
| Find on line | `f F t T` + char; `;` `,` repeat |
| Match | `%` |
| Search | `/` `?` command line → `/pat<CR>`; then `n` `N` |
| Word under cursor | `*` `#` |
| Viewport | `H M L` `Ctrl-d` `Ctrl-u` (when mapped as keys) |

Search options set at session start: `hlsearch`, `incsearch`, `ignorecase`, `smartcase`, `wrapscan`.

The matches Neovim highlights are painted in the editor too: `LuaScripts.SEARCH` finds them in the
window's visible lines at each redraw (a decoration provider's `on_win`) and sends
`eclipse_search` when they change; `SearchPort` hands them to the attached editor, whose
`editor/SearchHighlighter` merges a background into the viewer's text presentation, so the syntax
colors stay. Neovim's own highlights from the user's config, the flash after a yank and window
matches (`:match`), take the same way (`LuaScripts.HIGHLIGHTS`, `eclipse_highlights`,
`editor/NeovimHighlighter`), in their Neovim group's colors. Design:
[SEARCH_HIGHLIGHTING.md](design/SEARCH_HIGHLIGHTING.md).


## Status bar items

`ModeStatusContribution` shows the mode when the `statusMode` preference asks for it
(`util/StatusItem`), and `ECLIPSE` while Neovim mode is off: `editor/StatusBarLabels` owns the
label and sizes it to the mode's widest text as drawn. `util/ModeLabels` is one table of every
Neovim mode string with its label (`V-REPLACE`), its tooltip in Vim's wording
(`-- VREPLACE --`) and its group, which picks the color. Design and the mode table:
[design/STATUS_BAR.md](design/STATUS_BAR.md). A preference change reaches the item through
`Activator.applyStatusItems` and redraws via `EditorAttachManager.refreshStatusLine`.

The pending command (`showcmd`), the message text, the error flash and Neovim's `'statusline'`
left the status bar in 0.3.0: Neovim shows the first three on its own line (ui2, "Messages and
output"), and the status line is drawn under each editor's text ("The status line in the
editor"). Their preferences (`statusShowcmd`, `flashOnError`, `statusStatusline`,
`statusStatuslineWidth`, `statusMaxWidthPercent`) were removed with them: Eclipse keeps a stored
value of a key no code asks for, unread, so a user's old setting does nothing and fails nothing
(`SettingsNamesTest.REMOVED` keeps the code from reading one again).

## The status line in the editor

Each attached code editor draws its Neovim window's status line in a row under its text
(`editor/StatusLineRow`), as Neovim lays out its screen and as a page editor's pane does.

- **Where:** the `StyledText`'s bottom margin, one line higher while a line is shown, with a
  `GridCanvas` over it as the widget's child. A margin is not text: Eclipse draws and scrolls no
  text into it, and another editor's composite (rulers, overview ruler) is not touched.
  `ViewportFollower.cells` counts rows without the margin, so Neovim's window is exactly the rows
  shown and its status line is the row under them; `VIEWPORT_OVERHEAD` already counts that row in
  the screen's size. The widget's own margin is kept and restored at detach.
- **What:** `LuaScripts.STATUS_LINE`'s `line(win)`, `nvim_eval_statusline` with `winid`,
  `maxwidth` the window's width and `highlights`, turned into cells and the groups' colors
  (`nvim_get_hl` with `link = false`), read by `util/StatusLines` into a one-row `GridSnapshot`.
  The `'laststatus'` rule is Neovim's: 0 none, 1 only with another window in the tabpage (so none
  for an editor's window alone), 2 always, 3 the current window's only.
- **Why evaluated, not the screen's row (as the pages):** each editor's window is alone in a
  hidden tabpage, and Neovim redraws only the current tabpage. The screen (grid 1) holds the
  focused editor's line only, and a background editor's would stay in the colors of the window
  with the cursor. Evaluated for a window that is not current, Neovim uses `StatusLineNC`; the script
  sets `g:statusline_winid` around the call (Neovim's drawing sets it, the call does not), by which
  lualine and its like choose their inactive sections. One source for every editor also keeps
  them drawn alike.
- **When:** the current window's line with each key's `STATE_SNAPSHOT` (its first argument `"1"`);
  every window's as `eclipse_status_lines` on `WinEnter` (the window left turns `StatusLineNC`),
  `BufEnter`, `BufModifiedSet`, `BufWritePost`, `BufFilePost`, `ColorScheme`, `VimResized`,
  `WinResized`, `DirChanged`, `FileType`, `DiagnosticChanged` and `OptionSet` of `laststatus` or
  `statusline`, scheduled once per moment; on each attach (`NeovimSession.refreshStatusLinesAsync`);
  and when an editor's width in columns changes.
- **Width:** every tabpage shares Neovim's one screen, sized to the focused editor, so a background
  editor's window is as wide as that editor, not its own (and with the viewport off no window is
  its editor's width). Each row reports its editor's columns (`NeovimSession.setStatusLineWidthAsync`,
  Lua `set_width`), and every window's line is fitted to its editor's; to the window's own width
  until the editor reported.
- **Colors:** a group that leaves a color unset (`%#Identifier#`, a text color only) takes it from
  `StatusLine` or `StatusLineNC`, as Neovim draws it over the status line.
- **A modeline's line is not evaluated:** with `'modelineexpr'` on a modeline can set
  `'statusline'`, which Neovim then evaluates in its sandbox; `nvim_eval_statusline` cannot know
  where its string came from, so such a line (`last_set_sid` -1) is replaced by the default. `core/StatusTracker` passes a window's line
  on only when it changed, to every editor's listener; each row keeps its own window's.

## Messages and output

Neovim shows its messages itself. `LuaScripts.UI2` turns on Neovim's **ui2** (`vim._core.ui2`, a
Lua UI that ships with Neovim 0.12, experimental), which draws the command line and every message
into floating windows of its own, and the editor copies their cells as it does any float's
("Neovim's windows drawn from their grids"). The plug-in reads no `msg_show` event, routes
nothing, and keeps no message state of its own (`core/StatusTracker` holds only the windows' status
lines, the recording register and the pages' content).

Why the UI is still attached with `ext_cmdline` and `ext_messages` (`NeovimConnection.TERMINAL_UI`),
though nothing is drawn from those events: Neovim attaches the UI before it reads the user's
`init.lua`, and ui2 starts only after that. An error in between, with no UI taking messages, would
make Neovim wait at a "Press ENTER" prompt nobody sees, and the start would time out. Once ui2 is on,
its Lua UI takes these events and Eclipse's UI gets none. `ext_popupmenu` stays off, so Neovim draws
its popup menu itself ("Command line", "Completion: Neovim completes and draws its popup menu").

ui2 has four windows, each a float with its own buffer, whose `'filetype'` names it:

| Window | Shows | Example |
| --- | --- | --- |
| `cmd` | the command line being typed, and short messages after a command | `:e src/`, `3 lines yanked`, `E486: Pattern not found: x` |
| `msg` | messages while a line is typed, or ones ui2 shows apart from the line | a timer's `:echo` while `:` is open |
| `pager` | `g<` (the last command's output again) and long output; entered, so the cursor is in it | `:ls`, then `g<` |
| `dialog` | prompts that wait for an answer | `confirm()`, `:s/a/b/c`'s question, `input()` |

What the plug-in adds, all in `LuaScripts.UI2`, the one place it talks to ui2:

- **`'cmdheight'` 1**, set after ui2 is on. ui2 leaves it 0 and grows it while a `:` line is open;
  the window above then shrinks a row, and with the cursor on its last row Neovim scrolls the text
  (0.12) or moves the cursor (0.13). At 1 nothing moves: Neovim's screen is one row taller than the
  editor's window (decision 9, [design/VIEWPORT.md](design/VIEWPORT.md)), and ui2's line lives in
  that row, which the editor keeps under its status line (`editor/StatusLineRow`).
- **A user's `'cmdheight'` 0** (`init.lua`, or `:set ch=0` later, through `OptionSet`, or a 0 set
  without autocommands, seen at the next `SafeState`) is kept as a
  wish: Neovim goes back to 1, for the reason above, and Eclipse is told (`overlay`, the fifth value
  of `eclipse_ui2_windows`; `NeovimSession.commandLineRows` 0). The editor then keeps no row for the
  line (`StatusLineRow`, `WindowPane.rowsBelow`): one more text row, Neovim's window one row
  taller, and the screen's last row is not on screen. ui2's line window reaches that row, so it is
  drawn a row higher, on the status line's row at the editor's bottom edge, with what Neovim
  stacks on it (ui2's prompt and pager windows, the popup menu of a line being typed), so that none
  overlaps it; ui2's message window ends above the status line and stays (`util/Grids.ui2Windows`).
  The line is drawn only while the cursor is in it, a line being typed; between lines it would
  cover the status line with the last line typed. Messages, which would stay on that hidden row, go
  to ui2's `msg` window (`ui2.cfg.msg.target`), only when the target is ui2's default `'cmd'`, and
  back when the row returns: a config that chose `'msg'` keeps it. The floats lie over the status
  row (`FloatingWindows.keepUnder`), as Neovim draws a float over a status line. Why not pass 0
  through: ui2 grows the option to 1 while a line is open, the window shrinks, and the text moves
  (the bullet above); drawing over the status row is Neovim's own picture with 0, without the
  move.
- **Each window as wide as the screen.** ui2 opens them 10,000 cells wide and lets Neovim clip
  them; a multigrid UI is sent the full width, past the editor's limit on a float's cells.
- **The windows' ids** go to Eclipse (`eclipse_ui2_windows`) at start and whenever ui2 makes one
  again; `util/Grids.ui2Windows` draws those always ("Neovim's windows drawn from their grids").
- **ui2's own buffers left to it** (`ui2.bufs`): `SETUP`'s `BufEnter` sends Eclipse nothing for
  one, so entering the pager is not undone (`util/EnterDecision`), and `WINDOWS`' safety net never
  closes a window that shows one, also with the cursor in it. The buffer, not the window: a file
  opened in the pager (`gf`, `:e`) is announced like any other, and Eclipse sends Neovim back to
  the editor, as from any window it does not show (`util/EnterDecision`), so the file is never
  edited in a window Eclipse never heard of.
- **Windows ui2 made before the plug-in's script ran** (a user's `init.lua` that turns ui2 on) are
  fitted too, since no `FileType` comes for them.
- **A line inside a line** (`<C-r>=` on a `:` line): the types are kept as a stack, so when the
  inner one ends Eclipse hears the outer one's again, and Paste and the input method stay on it.
- **`:messages`** goes to its page editor, not to ui2's pager: ui2's history handler is wrapped, and
  the history is sent as `eclipse_messages`, each entry as `{kind, text}`, which `StatusTracker` makes
  a `util/ViewText` (errors marked `ErrorMsg`, warnings `WarningMsg`) for the page (decision 10).
  `g<` reaches the same handler with its `prev_cmd` flag and keeps ui2's pager.
- **The type of each command line** as it opens and closes (`eclipse_cmdline`: `:`, `/`, `?`, then
  `""`), for what the editor does while a line is open ("Command line"). While a search is typed,
  the editor follows its window's `win_viewport` only: each redraw brings one for ui2's windows too.

**The plug-in's own messages** (E37 from `:e` with changes, a refused write, `HostCommands.showError`)
go through Neovim too: `NeovimSession.echoAsync` runs `LuaScripts.ECHO`, `nvim_echo` with history, so
ui2 shows them like Neovim's own and `:messages` keeps them.

With ui2 Neovim never waits at a "Press ENTER" or "-- More --" prompt. Messages that come at any
time (a timer's, an autocommand's, one echoed later with `vim.schedule`, as the message `]d` echoes
after its jump) are drawn when they come.

**Why the cells, not a line of Eclipse's own.** Before 0.3.0 the plug-in drew the line itself, in
an SWT `Text` fed by `cmdline_show` events, with a message line and an output popup fed by
`msg_show`: each prompt, each kind of message, each way output grows, and each input method's way of
editing a text field was a case of its own to get right, and still differed from Neovim (no `:s///c`
prompt, no pager). ui2 is Neovim's own answer to all of them, and the editor already drew floats
from their grids, so copying ui2's cells makes the line exactly Neovim's for the cost of drawing a few
more floats. What it costs: the line is no text field, so macOS's press-and-hold accent menu does
not open there, and the input method needs the filter described under "Command line".

**No switch back.** There is no setting for the old `:` box and message line. The dedicated Neovim
is pinned to one release (docs/UPGRADING.md, "Neovim"), so ui2, experimental as it is, changes only
when the plug-in moves to a new Neovim, and each such move must pass the specs that pin every ui2
behavior the plug-in relies on (`tests/unit/src/test/lua/ui2_spec.lua`) and
`NeovimUi2IntegrationTest`, with a real UI attached, before it ships. A second, old path would need
its own tests and would drift. A Neovim of the user's own (`-Dneovim4eclipse.nvim`) without ui2
shows no command line; the session logs ui2's error.

**noice.nvim** refuses to run beside a UI that draws the command line itself, and turns itself off
with three lines at start; those stay filtered out of the start-up warning ("Command line",
start-up).

## Command line

Neovim draws its command line itself: ui2's `cmd` window, on the editor's command line row under
the status line ("Messages and output"). The editor's `StyledText` keeps the keyboard while the line is open, and every key goes
on to Neovim as in normal mode, so Neovim's history, completion, registers, mappings and
abbreviations all apply, and nothing in Eclipse edits a text of its own.

- **Keys** go to Neovim in its notation (`util/KeyMapping`), through the session's input thread, in
  order with every other key. The `Ctrl` letters ticked in the **Normal** column of the Ctrl Keys
  page go to the line (`<C-r>`, `<C-u>`, `<C-w>`), as in normal mode (`util/KeyContextRule.Column`).
- **Eclipse's commands** run before any widget sees a key: Cmd+V (or Shift+Insert) is Eclipse's
  Paste, whose editor handler would paste into the document. While a line is open,
  `editor/CommandLineEditHandlers` activates handlers for Paste, Copy, Cut and Select All in the
  editor site's handler service; their expression ranks as depending on the focus control, above the
  editor's own handlers, so they win. Paste sends the clipboard's text as `nvim_paste` into Neovim's
  line; the other three do nothing (the line's text is Neovim's; `<C-r>` is the Vim way to put a
  register into it).
- **Opening and closing** follow Neovim: whoever opens a line (`:` or `/` typed, a mapping, a count,
  `:` in visual mode, an operator's `d/`, `input()`, `confirm()`), `LuaScripts.UI2` reports it
  (`eclipse_cmdline`), and the controller turns the edit handlers and the input method on
  (`NeovimEditorController.onCmdlineType`). Neovim mode turned off sends `<C-c>` to a line still open
  (not in Ex mode, where Neovim ignores it).
- **The input method** (Japanese, Chinese, Korean) would compose into the `StyledText`, the widget
  with the keyboard, and so into the document. `editor/LineInputMethod` takes the widget's
  `ImeComposition` events first, with a display filter, while a line is open (a filter runs before
  any listener, and an event whose type it clears goes no further): the composing text is drawn in
  a small box at the line's cursor (`FloatingWindows.cursorCell`, from Neovim's cursor in ui2's
  grid), the editor's `Caret` is moved there while composing and put back when the line closes
  (every system opens the candidate list at the widget's caret, whatever `COMPOSITION_OFFSET`
  answers), macOS's selection request is answered (SWT 3.128's Cocoa side throws without one), and
  at the commit the committed text goes to Neovim's line as a paste. The filter clears the event's
  type but leaves `doit` true: on macOS a refused commit tells Cocoa no key input happened, and
  Cocoa then sends the Enter that ended the composition, which would run the line. Every system also delivers a commit as key presses (on macOS with
  Enter's key code, which would run the line), so a second filter drops the presses that repeat a
  commit. The decisions are `util/LineComposition`, plain and unit-tested:

  ```
  changed("k", 0)    composing "k"    send nothing
  changed("可", 0)   composing "可"
  changed("可", 1)   composing ""     send "可"
  key '可'                             dropped: already sent
  key 'x'                              kept: an ordinary key
  ```

  A click or the focus leaving ends a composition, unsent. On Linux and Windows the input method
  ends it itself; on macOS it does not, and SWT has no call for it, so Cocoa's `discardMarkedText`
  is called through SWT's internal classes, by reflection, guarded: a later SWT without them only
  leaves the composition open. macOS's press-and-hold accent menu needs a text field and does not
  open on the drawn line; dead keys (Option+E, E) are ordinary key presses and work. A real input
  method cannot be driven by a test: SWT's side is checked with synthetic events
  (`LineInputMethodTest`), and a person checks each system (docs/MANUAL_CHECKS.md, `ime-compose`,
  `ime-keys`).
- **Candidates** for Tab are Neovim's own popup menu (the UI is attached without `ext_popupmenu`),
  drawn by Neovim into a grid of its own, which the editor draws as a float over the text above the
  line ("Neovim's windows drawn from their grids").
- **Search preview:** while a `/` or `?` line is open, `win_viewport` carries Neovim's `'incsearch'`
  match (no `CursorMoved` fires then); the session dispatches it as the cursor, so the caret and view
  follow the match, and Esc or Enter then set the real cursor through the state read after that key.
- **`q:`** (the command-line window) is drawn over the bottom of the editor from its grid: see
  "Neovim's windows drawn from their grids" below.
- **Working folder:** the controller sets the window's folder (`:lcd`, silently) to the editor's project
  root, or the file's folder outside a project, so that `:e` completion and `:!` start there.
  A relative `:e`, `:w` or `:wq` name is then relative to that folder, as in Neovim: `HOST_COMMANDS`
  hands Eclipse the full path (`expandcmd()`, `fnamemodify(':p')`), and `util/EditPath` is only the
  fallback for a name that arrives relative.
  Neovim's process then works in that folder, and Windows refuses to delete a folder a process works
  in. So before Eclipse deletes or closes a project, `editor/ProjectFolderExit` (a workspace listener
  for `PRE_DELETE` and `PRE_CLOSE`) has Neovim move every working folder inside it, global, tab page's
  and window's, to the folder's parent (`LuaScripts.LEAVE_FOLDER`), and waits for the answer at most
  1 s, on whatever thread deletes the project, the UI thread included: the delete must not start
  before Neovim has moved, and the bound keeps a busy Neovim (one waiting for the character after `f`,
  say) from hanging Eclipse. Past it, the delete goes on and may fail on Windows, as before. The
  request goes straight to Neovim, not through the input thread, whose work can itself wait for the
  UI thread.
- **Host commands are parsed by Neovim** (`LuaScripts.HOST_COMMANDS`): Enter on a `:` line goes to Neovim
  like any key. Just before the line runs, a `CmdlineLeavePre` autocommand parses it with
  `nvim_parse_cmd()`; a command Eclipse carries out (`:q`, `:wq`, `:e`, `:tabn` ..., the table in
  `util/HostCommand`) is replaced with `setcmdline()` by `:Eclipse[!] {full name} {args}`, a user command
  that sends `eclipse_command` and puts the typed line back in the history. Example: `:tabn 3` runs as
  `:Eclipse tabnext 3`. Since this happens on Neovim's command line, a mapping's `:q<CR>`, a macro,
  `:normal` and `@:` go the same way. Replacing the line rather than cancelling it (`v:event.abort`) keeps
  `@:` working: a cancelled line is no "last command line" (E30), as an experiment on Neovim 0.12 showed.
- **Safety nets** for what Neovim runs itself (`<Cmd>bd`, `vim.cmd.quit()`, `:bufdo bd`, a command after
  the `|` of a command that keeps it, such as `:let x = 1 | q`): `BufDelete` / `BufWipeout` on a document buffer and `QuitPre` send `eclipse_buffer_closed`, and
  `EditorAttachManager` closes the editor showing that buffer (asking about changes). They cannot stop
  Neovim: on its last window a quit still ends it. Ignored while the session closes (its `:qa!`
  fires `QuitPre` too) and for a buffer no editor shows (a closed file's kept buffer). The buffer
  number is turned into its document as the notice is read, on the RPC reader thread
  (`DocumentBuffers.onClosed`), and the editor is found by that document, not by the number: a
  show queued before the notice may find a wiped buffer gone and make it anew under another
  number before the UI thread gets to the notice (a plug-in's `:bwipeout 5` just as that editor is
  activated). Neovim sends the notice during the wipe, before any later answer, so the show
  cannot have changed the number yet. A `:noautocmd bwipeout` sends no notice; there the editor
  stays, and its next show makes the buffer anew (`DocumentBuffers.show`).
- The notification becomes a `util/HostCommand` (`NotificationBridge`), reaches the editor with
  the focus (`core/FocusRouter`, `FocusTarget.onHostCommand`), is turned into IDE calls by `util/HostCommandRunner`
  (`:wq` saves, then closes only if the save worked; `:wq other.txt` writes a copy instead,
  `HostCommand.writeTarget`, then closes as `:q` does) and carried out by `editor/HostCommands`.
  `editor/NewFiles` handles files that do not exist yet: `:e new.txt` opens an empty editor, picked by
  the file's name, and a write creates the file through the document provider instead of Eclipse's
  save, which would open Save As (see [NEW_FILE.md](design/NEW_FILE.md)). The editor is opened by location
  even inside a project, because a text editor refuses to show a workspace file that does not exist;
  after the first write it is switched to the workspace file, keeping its Neovim buffer
  (`EditorAttachManager.replaceInputKeepingBuffer`, `core/EditorBuffers.move`).
- **Too old a Neovim:** `NeovimConnection.checkVersion` asks `vim.version()` before attaching and
  refuses anything below `util/NeovimVersion.MINIMUM` (`core/UnsupportedNeovimException`, shown by the
  "Neovim not started" notice). One minimum instead of per-feature checks, raised readily
  (docs/UPGRADING.md, "Neovim").
- **Start-up and the user's configuration:** under `--embed` Neovim runs `init.lua` only after
  `nvim_ui_attach`. An error there is a message event that arrives before Eclipse listens, and
  Neovim goes on (with `ext_messages` there is no hit-enter prompt to wait at; ui2 is turned on
  only after the config ran).
  `NeovimConnection.waitForStartup` therefore waits for an idle answer and keeps the errors
  (`:messages`) for a one-time warning
  (`editor/StartupErrorNotice`, `util/StartupErrorReport`). noice.nvim's three
  "can't work when the GUI has ext_cmdline enabled" lines are left out of it
  (`StartupErrorReport.withoutKnownNotices`, those exact sentences only) and logged: noice turns
  itself off and Eclipse works, so they are no error to fix.
- **Output:** a command runs in Neovim as typed, so the history, macros and `@:` see it, listings
  such as `:ls` too. What it prints Neovim shows itself, in ui2's windows (see "Messages and
  output"). The plug-in redirects nothing itself, so a user's own `:redir` works as in Vim.
  (`core/CommandRunner` still types whole lines for `commandAsync`, waiting until Neovim is idle, at most
  60 s, before refreshing Eclipse.)


### Neovim's windows drawn from their grids

Neovim draws each window into a grid of its own (`ext_multigrid`, `core/NeovimConnection.TERMINAL_UI`,
with `rgb` for 24-bit highlight colors). The editors show their documents themselves; the plug-in
draws the grids of the windows that have no document behind them: the command-line window (`q:`),
the floating windows that do not have the focus (below, "Floating windows without the focus"), and
Neovim's own floats, ui2's command line, messages, pager and prompts and the popup menu, drawn
always, with the cursor in them too (below, "Always drawn").

```
Neovim  ── redraw: grid_line, grid_scroll, win_pos, hl_attr_define, flush ──▶  RPC reader thread
  CmdwinEnter ── eclipse_cmdwin(win, type) ──▶ NotificationBridge
                                                ├─ util/GridEvent   parse the batch
                                                └─ NeovimSession.EventSink
                                                     util/Grids     every grid's cells; at flush the
                                                                    command-line window's, if changed
                                                     ── util/CommandWindowView (immutable) ──▶ listeners
NeovimEditorController ── UI thread ──▶ editor/NeovimWindowPanel (OverlayFrame over the bottom rows)
                                          └─ editor/GridCanvas   cells, colors, cursor
keys in the panel ── NeovimSession.inputAsync ──▶ Neovim (every mode; Eclipse never types there)
left button, wheel ── GridCanvas.cellAt ── commandWindowMouseAsync ── nvim_input_mouse(grid) ──▶ Neovim
focus elsewhere ── closeCommandWindowAsync ── <C-\><C-N><C-\><C-N> ──▶ Neovim (nothing runs)
```

- **Whose cells are kept** (`util/Grids.wantsLines`): the drawn window's grid's, and a new grid's
  until its window is known: Neovim sends a grid's `win_pos` at the end of a redraw, after its lines.
  An editor's window's lines are then left out unread (`GridEvent.fromRedraw`), not even parsed.
- **Its mode stays in the panel:** while the window is open, `NeovimEditorController.onModeChanged`
  hands Neovim's mode only to the panel's cursor (a block, a bar in insert mode); the editor keeps
  its caret and never types. The cursor and buffer reports are already filtered by window.
- **Its buffer sends no `eclipse_buf_enter`** (`LuaScripts.SETUP`): the session would take Neovim
  back to the editor's window, which Neovim refuses while the command-line window is open (E11).
- **The screen keeps its size** while it is open: `NeovimSession.resizeGrid` waits (`gridPending`),
  since Neovim's editor window is shorter by the window's rows and the overhead would count them.
- **`Ctrl-F` on the `:` line:** Neovim closes its line and opens the window; the panel takes the
  keyboard at `eclipse_cmdwin` (sent at `CmdwinEnter`). A `:` typed in the window opens ui2's line
  as anywhere; when that line ends, the controller gives the keyboard back to the panel
  (`onCmdlineType`).
- **Closing it from Eclipse** types `<C-\><C-N>` twice (`NeovimSession.CLOSE_COMMAND_WINDOW_KEYS`):
  it ends a half-typed command or Insert mode, and in the window's Normal mode closes the window,
  its line dropped. Keys, not a Lua call: while Neovim waits for a command's next key (`r`, `d`) it
  answers no call. Not `<C-c>`: in that window `Ctrl-C` goes back to the command line with the
  line under the cursor.
- **The mouse** (left button and wheel, every mode): `GridCanvas.cellAt` turns a point into the
  window's cell, and `NeovimSession.commandWindowMouseAsync` sends it with `nvim_input_mouse` on the
  window's own grid (`util/Grids.drawnGrid`, kept with each drawn view). Neovim counts double
  clicks and drags itself, as for a terminal; a drag is sent only when it reaches another cell. The
  right and middle buttons do nothing there. A click can reach Neovim after the window closed (an
  Enter typed just before it is handled first); it names a grid no window has, and Neovim drops it,
  since a new window always gets a new grid number (`NeovimSessionIntegrationTest`,
  `aClickOnTheGridOfAClosedCommandLineWindowDoesNothing`). The editor's mouse is another path
  (`LuaScripts.MOUSE` maps a document position to Neovim's screen, grid 0), since Eclipse draws the
  editor's text.
- **Colors:** a highlight's own colors where it sets them, the editor's text and background where it
  leaves them to the default (`util/GridSnapshot.colors`); `'background'` follows the editor's
  (`LuaScripts.SET_BACKGROUND`, sent at the start and at each theme switch).

**Floating windows without the focus** (a plug-in's panel of key hints, a note opened in the
background) take the same road, with a list of windows instead of one:

```
Neovim  ── redraw: grid_line ..., win_float_pos(grid, win, ..., zindex, screen_row, screen_col) ──▶
  NotificationBridge ── util/GridEvent.WinPos ──▶ NeovimSession.EventSink
      util/Grids.floatsIfChanged   at flush: every float not under the cursor, lowest zindex first
      ── List<util/FloatWindow> (immutable: cells, row and column over the editor's window) ──▶ listeners
NeovimEditorController ── UI thread ──▶ editor/FloatingWindows
                                          ├─ util/FloatPlacement   cells to pixels, edges
                                          └─ one editor/GridCanvas per window (SWT.NO_FOCUS), a child of the text
```

- **Which floats** (`util/Grids.floatsIfChanged`): those whose grid the cursor is not on. A float
  with the focus is not drawn; one that *opens* with the focus is closed by the `WinNew` net of
  `LuaScripts.WINDOWS`, as before. Keys are never sent to a float: they go to the editor's window,
  where the plug-in that opened the float reads them.
- **How many** (`Grids.MAX_FLOATS`, `MAX_FLOAT_CELLS`): at most 16 floats and 250,000 cells in
  all, taken highest `zindex` first, each one if it still fits; the rest are left out, and that
  is logged once. Neovim does not shrink a float a plug-in asks for (200 by 60 on a screen of 80 by
  24 comes as 200 by 60), and each changed redraw copies a drawn float's cells for the UI thread.
  One budget for all of them bounds that work, its memory and the number of canvases with one
  check; the cells are counted before they are copied. `GridCanvas.paint` also draws only the
  cells its size shows.
- **Where** (`util/FloatPlacement`): one rule. Neovim sends the screen cell it worked out for the
  float's top left (`screen_row`, `screen_col`: the anchor's corner, the window or float it hangs
  on and the cursor are settled in them); it is counted from the first cell of the editor's
  window and multiplied by Eclipse's cell. A float that reaches the window's last row or column
  sits on the editor's edge, since the editor's height is seldom a whole number of rows (unless
  it begins at the first row or column: it stays there, or the editor's text would show in the
  strip before it); one that would stick out is moved back in; one larger than the editor is
  cut off by the text widget.
  Not mapped through the text: where Eclipse's rows differ from Neovim's (a code mining, another
  wrap point), a float anchored to a text position can sit a row off.
- **The editor's window** (`Grids.host`) is the one layout window on screen: each editor's is
  alone in its tab page, and Neovim hides the others (`win_hide`). Not found through the cursor:
  Neovim reports the cursor's grid only when it changes. Its place comes once, in the redraws
  right after `nvim_ui_attach`, before the session listens; `NeovimConnection.Forwarder` keeps
  those `win_pos` entries, as it keeps the highlight definitions, and hands them on first.
- **Only with the viewport** (`NeovimSession.floatingWindowsDrawn`): a float's place is a cell of
  Neovim's screen, which is the editor's size only while the viewport is on. Off (or with
  `vim.g.neovim4eclipse_floating_windows = 0`), `Grids.drawFloats(false)` leaves plug-ins' floats'
  lines unread; the windows that are always drawn (next) are read all the same.
- **Always drawn** (`Grids.alwaysDrawn`): ui2's four windows (`Grids.ui2Windows`, the ids
  `LuaScripts.UI2` sends) and Neovim's popup menu, the one float without a window (win -1), in which
  the `:` line's Tab list and insert-mode completion show. Without them there is no command line to
  see, so neither switch hides them, and they are drawn with the cursor in them (the typed line, the
  pager after `g<`), which the focused-float rule above would leave out. They hang from the bottom of
  Neovim's screen, below the editor's window and its status line; the editor draws those rows too
  (`editor/StatusLineRow`, `WindowPane`), so its host counts them (`Grids.rowsBelow`, added to
  `FloatWindow.hostRows`), the line lies on the editor's last row, the command line's, and the
  popup menu hangs above it while a `:` line is open and stays by the text in insert mode. It is left out while Eclipse types an insert (`Grids.eclipseTypes`): Neovim's
  cursor is not where the user types then, so a list a config opens by itself would hang in the
  wrong place; a `Ctrl-N` is typed by Neovim, and drawn.
- **They only show** (`editor/FloatingWindows`): no focus, no mouse; among themselves in `zindex`
  order; cleared in Eclipse mode and shown again in Neovim mode. `FloatingWindows.cursorCell` tells
  the input method where Neovim's cursor is in ui2's line ("Command line").
- **Placed again at each paint of the editor**, not only at a resize: `StyledText` moves its
  children along when the text scrolls, and a float that does not hang on text must stay put; a
  scroll repaints, as a font change does. Only a canvas that is
  somewhere else is moved, and the order is set only when the windows change, so a paint causes
  no further paint.
- **A newly attached editor** asks the session for the floats as last reported
  (`NeovimSession.floatingWindows`): the report for its tab page may have come before it listened.

## Macros

Neovim records and plays macros natively; the plugin only has to make sure Neovim *receives everything as
keys* and to keep the editor in step with playback.

- **Recording state:** `STATE_SNAPSHOT` returns `reg_recording()`; `NeovimSession` fires a recording
  listener on change and the controller shows `rec @a` after the mode in the status bar (`ModeLabels.text`).
- **Insert while recording:** `isNeovimDrivenInsert()` is also set when `isRecording()`, so every key goes to
  Neovim and the recording is complete. (When *not* recording, Eclipse types and the text is replayed at Esc
  for `.`; replayed keys would also be recorded, but only exact keystrokes make a faithful macro.)
- **`:` commands:** `commandAsync` types `:<cmd><CR>` instead of calling `nvim_command`, which Neovim
  would neither record nor remember for `@:`.
- **Playback (`@x`):** `@` is a pending-character command (see "Pending commands"). The next key starts the
  playback: buffer events are suppressed (`suppressBufferEvents`), and when Neovim is idle the whole buffer is
  fetched once (timeout 60 s) and applied as one minimal edit (`syncBufferAfterMacro`).
- **Arguments vs prompts:** outside insert mode every key goes to Neovim, which alone decides what it
  means: `:` `/` `?` open a command line or are the argument of the command before them (`f:`, `r/`,
  `d/foo`); `q` starts or stops a recording or is part of `gqq`; `Z` starts `ZZ` or is the argument of
  `fZ`. A command line is Neovim's, drawn by Neovim (ui2), and the recording
  state comes from Neovim (`reg_recording()` in the snapshot). `ZZ` and `ZQ` are Neovim mappings to
  Eclipse's save and close (`IdeAction`, below), never Neovim's own, which would quit the embedded
  Neovim. Nothing on the UI thread guesses this from the keys typed so far (`KeyRouter` once did, with
  a `util/KeyGrammar`, because the `:` box of that time was its own and had to open before Neovim saw the key, and the
  guesses went wrong); see [design/KEY_ROUTING.md](design/KEY_ROUTING.md). Example: `qaihello<Esc>q`
  typed at full speed records the insert, because the `i` reaches Neovim before Eclipse can take
  over the typing, so Neovim types the insert and records it (next section).

## Handing typing over to Eclipse

Entering insert mode is followed by a full buffer sync from Neovim (`KeyInput`, decided by `util/ModeTransition`), which the editor applies
asynchronously on the UI thread. Text Eclipse typed before that sync landed would be overwritten by it. So
Eclipse types only once the sync has been applied: the sync's `BufferChange` carries an `afterApply` action,
run by `BufferSynchronizer` after applying it, which sets `NeovimSession.isEclipseTypingReady()`. Only a
sync read in this insert counts: `StateSync` counts each start and end of insert mode (and each pause at a
sync point), and a sync lets Eclipse type only if the count has not moved since it was read. Example: a sync
read in normal mode after a `:` command, applied only once `i` has been read, holds the text from before
the insert; had it let Eclipse type the next key, the insert's own sync, applied after that key, would have
taken it out again, in Eclipse and then, at Esc, in Neovim. A key that
arrives in insert mode before then makes the insert Neovim-driven for now (`driveInsertFromNeovim`):
Neovim types it, in order, and the keys after it, since mixing the two could let Neovim's update for
an earlier key overwrite a later one typed by Eclipse. Once Neovim has them all (no key queued for it),
the same handshake hands typing back to Eclipse (`KeyInput.handBackToEclipse`); it is called off when
the editor applies the sync if a key was queued meanwhile, and the next key tries again. An insert from
blockwise visual or while recording stays Neovim's to Esc. People rarely type that fast;
`EditorEndToEndTest` does. Keys sent before Neovim even reports insert mode simply reach Neovim in order
and are inserted there.

## Dot-repeat of text typed in insert mode

Eclipse types in insert mode, so Neovim's redo buffer would only hold the entry command (`A`) and `.` would
repeat an empty insert. At Esc `KeyInterceptor` asks the controller for a replay plan:

1. `InsertTracker` (an `IDocumentListener`) keeps the text of the lines the insert touches, from before its
   *first* change, and that change's offset. At that moment the text equals Neovim's buffer. It keeps only
   that window of whole lines, widened by each later change with lines no change touched, so a long
   document is never copied whole on the UI thread. Which changes start it is decided by
   `util/InsertReplay.tracksChange`: typing in insert mode only, not Neovim's own changes, replace mode or
   a Neovim-driven insert.
2. `util/InsertReplay.plan` checks that the window's text now is its text before plus one plain insertion
   at that offset, and splits the inserted text into small chunks of raw text (any line break as `\r`,
   Enter); the plan's lines are then moved down to the window's place in the document.
3. `NeovimSession.leaveInsertModeAsync(plan)` first stops Eclipse's typing, on the UI thread
   (`StateSync.pauseEclipseTyping`, as at a sync point): the mode is insert until Neovim reports Normal,
   and a key typed meanwhile (the `:` of a fast `<Esc>:w`) must go to Neovim behind the Esc, not into the
   document. From that moment, not only once the input thread starts, the editor ignores Neovim's cursor
   reports and top lines (`KeyInput.handOver`, `isLeavingInsertMode`): until then they are of
   Neovim's old text and view. Then, on the input thread, it moves Neovim's cursor to the insertion point,
   types the text with `nvim_feedkeys(text, 'n')` (raw text, no mapping or abbreviation applied; each chunk
   followed by a cheap request, which Neovim only answers after consuming the keys), sends `<Esc>`, then pushes
   the lines changed since the insert began (`util/ChangedLines`, `BufferSynchronizer.pushChangedLinesToNeovim`,
   not the whole document) of Eclipse's document (authoritative: it may
   differ in auto-indent or bracket completion) and puts the cursor where Vim leaves it after Esc.
4. While re-typing, Neovim's own buffer events are suppressed (`suppressBufferEvents`) because the document
   already contains the text.
5. Each chunk goes through `LuaScripts.TYPE_TEXT`, which types it only while Neovim is in Insert or Replace
   mode, checked in the same request. The text is whatever Eclipse typed (a paste from a web page, say),
   and in Normal mode it would be commands: a pasted `:!cmd` and a line break would run. If a timer or
   autocommand of the user's config ended the insert meanwhile (`stopinsert`), nothing more is typed and
   the old path runs (push, then Esc; `.` misses the text). At a sync point the key is not sent either.

Any non-replayable edit yields no plan and the old path (push, then Esc) runs. The user's insert-mode
mappings and abbreviations stay: with `nvim_feedkeys`' `n` flag, a mapping such as `jk` to Esc cannot end
the re-typing early, nor an abbreviation change it, so they need not be cleared at startup (and the
text need not go through `nvim_input` in key notation, `<` as `<lt>`).

### Sync points: a `Ctrl` key in the middle of the insert

A `Ctrl` letter ticked for insert mode (`Ctrl-W`, `Ctrl-U`, ...) does the same hand-over without
leaving insert mode (docs/design/INSERT_CTRL_KEYS.md, section 2). It arrives through the letter's key
binding (`SendKeyHandler`, then `KeyRouter.routeBound`) or, where Eclipse has no binding for it,
through `KeyInterceptor` (`KeyRouter.route` and the insert filter).

1. On the UI thread, `InsertTracker.consumeSyncPoint` builds `util/InsertReplay.SyncPoint`: the text
   typed since the insert began (or since the last sync point) and the caret. Eclipse stops typing at
   once (`StateSync.pauseEclipseTyping`), so a key that follows goes to Neovim, after this one.
2. On the input thread, `KeyInput.syncInsertThenInput` re-types the text into Neovim's open insert
   (no `<Esc>`), pushes the document, puts Neovim's cursor at the caret and sends the editor's view,
   with buffer events suppressed and cursor reports dropped (`isLeavingInsertMode`) meanwhile.
3. Then the key: Neovim's own `<C-w>` deletes the word, and its change reaches the document as a buffer
   event. While Neovim is still in insert mode, a full sync follows, after which Eclipse types again:
   the handshake of an insert's start. `InsertTracker` starts over, so at Esc only the text typed since
   is replayed, and `.` repeats the whole insert.
4. A key after which Neovim waits for another (`Ctrl-R`, `Ctrl-V`, `Ctrl-K`) leaves Neovim answering
   no request: Neovim drives the insert, as after a block-visual `I`, until it has that key and is
   in plain insert mode again. Then the handshake of step 3 hands typing back to Eclipse
   (`KeyInput.handBackToEclipse`), unless a key was still on its way to Neovim: it would arrive
   while Eclipse types. So the hand-back waits for the last queued key.

Two keys are not sync points: `Ctrl-X` `Ctrl-O` runs Eclipse's Content Assist
(`KeyRouter.Completion`, run by the controller), and `Ctrl-C` is `Esc`. `Ctrl-N`, `Ctrl-P` and
`Ctrl-X` with another letter are sync points that start Neovim's completion (the next section).

### Completion: Neovim completes and draws its popup menu

In insert mode `Ctrl-N`, `Ctrl-P` and the `Ctrl-X` modes are Neovim's own completion
(`:help ins-completion`), always (`NeovimSession.completesInNeovim`), with Neovim's popup menu drawn
at the word.

**The keys.** `KeyRouter` sends the key as a sync point (`NeovimSession.completeInNeovimAsync`), so
Neovim has the text Eclipse typed, and from that key on Neovim types the insert
(`KeyInput.completionQueued`). Why Neovim must type: the next key steers the completion (`Ctrl-N`
for the next match, `Ctrl-Y` to accept, `Ctrl-E` to cancel) or ends it, and only Neovim can tell
which. It is the hand-back of step 4 above with another condition: `KeyInput.mayHandBack` waits
until Neovim's mode is no longer a completing one (`ic`, `ix`; `VimModes.isCompleting`), instead of
until Neovim stops waiting. Example: `al`, `Ctrl-N` (`alpha`, mode `ic`), `Ctrl-N` (`alphabet`),
`(`: Neovim inserts the bracket, its mode is `i`, and the usual handshake lets Eclipse type the
next key. The cost: Eclipse's typing helpers do not see that one character.

- **The mode, not the list, says that Neovim completes.** With one match or none, and with a
  `'completeopt'` without `menu`, Neovim shows no list but still completes until the next key. So
  the hand-back reads the mode after each key (`nvim_get_mode`), on the input thread.
- **Every `Ctrl` letter is Neovim's meanwhile** (`KeyContextRule.toNeovim`): a user may have
  cleared `Ctrl-Y` and `Ctrl-E` for insert mode, and Eclipse would then run Redo and Quick Switch
  Editor. The state is
  counted from the moment the key is routed on the UI thread (`KeyInput.isCompleting`), since the
  key contexts must be right before the next key is pressed, which can be before Neovim has the first.
- **`Ctrl-X` stays in Java** until its second key (`KeyRouter.ctrlXPending`): `Ctrl-O` is Content
  Assist; another letter is sent together with it, as `<C-x><C-l>`. Sent at once, Neovim would be
  in its `Ctrl-X` mode, and Content Assist would have to wait for a hand-back.
- **An insert Neovim owns** (recording a macro, a block-visual `I`) is left alone: the list is
  drawn, but the plug-in sends no key of its own, which would land in the macro.
- **A change Eclipse makes by itself meanwhile** (a paste with the mouse, an accepted content-assist
  proposal) is pushed to Neovim as outside insert mode (`BufferSynchronizer.pushLocalChanges`, whose
  gate includes `isCompleting`). It is not typing, so no `Esc` would hand it over, and the text
  Neovim sends at the completion's end would take it away again. The push also ends the
  completion: Neovim's completion remembers the line as it was, and the next `Ctrl-N` or `Ctrl-E`
  would put that line back without the change.
- **A click into the text, or the editor losing the focus,** ends the completion with the text kept
  (`KeyInput.endCompletion`: `CTRL-X CTRL-Z`, sent only after the mode was read on the input
  thread, since in Normal mode `Ctrl-X` subtracts from a number).

**The list is Neovim's popup menu.** The UI is attached without `ext_popupmenu`
(`NeovimConnection.TERMINAL_UI`), so Neovim draws the list itself, into a grid of its own: a float
with no window (win -1), placed by Neovim at the word, in the colorscheme's `Pmenu` groups, with
its scrollbar, `'pumheight'`, `'pumblend'` and `'completeopt'` as in a terminal. The editor draws it
as Neovim's other floats ("Neovim's windows drawn from their grids", "Always drawn"), so its place
is Neovim's cell, mapped as any float's: where Eclipse's rows differ from Neovim's (a code mining,
another wrap point) it can sit a row off. It is drawn only in an insert Neovim types
(`Grids.eclipseTypes`): a list opened behind an insert Eclipse types (a config that completes as
text arrives, at the plug-in's own re-typing) would hang where the user is not typing.

The window Neovim opens beside the list for a match's documentation (`'completeopt'` `popup`) is a
float with a window, drawn as plug-ins' floats are.

`vim.g.neovim4eclipse_completion_menu`, which once turned the plug-in's own list off for Eclipse's
Word Completion, was removed in 0.3.0 (README, "Settings", Removed). The paths it chose are still
there, unreachable (`NeovimSession.completesInNeovim` is always true): `KeyRouter`'s Word
Completion branch and the `complete.n`/`complete.p` contexts of `plugin.xml`.

**Three `Ctrl-X` completions are refused in Neovim itself** (`LuaScripts.CTRL_X_GUARD`):
`Ctrl-X Ctrl-V` and its other name `Ctrl-X Ctrl-Q` complete the text before the cursor as a Vim
command line, and that expands `` `...` `` by running it in the shell; the text is the file's.
`Ctrl-X Ctrl-O` calls `'omnifunc'`, which for some file types evaluates the buffer. A `vim.on_key`
function drops the key (also when it comes with Shift or another modifier) when `complete_info()`
says `Ctrl-X` was just pressed, and feeds `Ctrl-Z` in its place, which ends the `Ctrl-X` and
changes no text. Not in the command-line window of `:` commands, whose lines are the user's own
commands and where Neovim maps `Tab` to `Ctrl-X Ctrl-V`. It is the one place every key passes,
typed, sent by the plug-in, mapped or run from a register, so no route to Neovim needs a check of
its own (`KeyRouter` keeps the key from starting a completion only to spare the round trip).

**No file's modeline applies** (`LuaScripts.NO_MODELINES` turns `'modeline'` off at start, for
every buffer). A modeline may set global options, and some change what a completion does: with
`` ` `` and the space added to `'isfname'`, `Ctrl-X Ctrl-F` takes a line's `` `...` `` for a file
name, and completing a file name runs that in the shell. Off as an option, not per read: Neovim
reads file text on many routes (the plug-in's own reads, the first match of `:vimgrep`, an
archive's listing, a help page), and the option is where all of them end.

**Neovim's readers of URLs and archives never load** (`util/NvimExecutable.READERS_OFF`, set by
the `--cmd` Neovim is started with, before any config): netrw, the `http://` reader, `zip.vim`
and `tar.vim` each check an "already loaded" variable in their first lines. With them, `gf` on a
URL in a file's text made a network request to the host the file names, and the fetched text got
filetype plug-ins; their buffers are no files, so no Eclipse editor could show them anyway.

**A buffer named `term://...` starts no program** (`LuaScripts.NO_TERMINAL_URLS`): Neovim's
defaults read such a name by running it in a terminal, so `gf` on `term://./build.sh` in a file's
text would run the repository's script. It is no plug-in with an "already loaded" variable; the
autocommand itself is removed at start. `:terminal` does not use it (and a typed one never reaches
Neovim: see the next paragraph).

**`:terminal` opens Eclipse's Terminal** (`util/EclipseTerminal`, `editor/EclipseTerminals`). A
terminal buffer has no Eclipse editor, so its program ran out of sight. A typed `:terminal` is a
host command (`HostCommand.Kind.TERMINAL`): Neovim's command line hands it to Eclipse before it
runs, so nothing starts in Neovim. Eclipse runs the Terminal's *Open local terminal* command by its
id, which opens a shell in the active editor's file's folder; no bundle of the Terminal is needed,
which matters because it was renamed in 2025-09 (`org.eclipse.tm.terminal.*` before,
`org.eclipse.terminal.*` after; 2025-09 has both, and the new id is tried first). A command after
`:terminal` is not run (the command opens a shell only), and a message says so. A terminal started
where the `:` line is not read (`<Cmd>`, `:execute`, Lua, a plug-in) has already started when
Neovim's `TermOpen` fires: `LuaScripts.TERMINALS` names it in a message and leaves it running, so
no program is stopped part way.

**`Ctrl-X Ctrl-O` is Eclipse's content assist wherever Eclipse gets the insert back.** In an
insert Eclipse types, `KeyRouter` keeps the `Ctrl-X` until the next key and runs content assist for
`Ctrl-O`. While Neovim completes it does the same (`KeyRouter.whileCompleting`): the completion is
ended with its text kept (`NeovimSession.endCompletionThenAsync`), Eclipse types again, and only
then content assist opens, since a proposal accepted while Neovim still typed would be an edit its
completion does not know of. In an insert Neovim types to its end (a recorded one) the keys go to
Neovim, where the guard drops the `Ctrl-O`: a proposal would be missing from the recording.

The viewport being off (`vim.g.neovim4eclipse_viewport = 0`) turns nothing of it off: the list
hangs on the cursor's line, which Neovim keeps on its own screen whatever the editor shows.

## Blockwise visual

- **Geometry:** Neovim's. In blockwise mode `STATE_SNAPSHOT` adds `getregionpos(getpos('v'), getpos('.'),
  { type = mode(), eol = true })` (Neovim 0.10): for each line, the first and last byte the block covers,
  with tabs, wide characters, combining marks and `$` worked out the way Neovim's own `d`, `y`, `I` act
  on them. `util/BlockSelection` only turns those bytes into the line's characters (a character partly
  inside is included whole; a line too short to reach the block is left out). Example: `Ctrl-V`, `j`,
  `l` from the start of `abc` and `def` arrives as `{1, 1, 2, 2, 1, 2}` (line, first, last byte).
  5000 lines take about 3 ms in Neovim. Neovim's answer replaces cell arithmetic in Java from the two
  corners, `curswant` and `'tabstop'`, which would have to redo Neovim's rules for tabs, wide characters and `$`.
- **Display:** SWT's block selection is pixel based, so the native selection is cleared and the rectangle
  is painted as an overlay by `editor/BlockOverlay` (visible lines only).
- **Tab width:** `NeovimEditorController` sets Neovim's `tabstop` to the editor's tab width on attach, so
  the rectangle Neovim measures is the one the editor shows.
- **Keys:** the workbench dispatches key bindings *before* widget key listeners, so `Ctrl-V` etc. would never
  reach `KeyInterceptor`. A context (`io.github.jm6cnddxbwftaks.neovim4eclipse.context.normal`) is activated
  workbench-wide while Neovim owns the editor's keys **and** its text widget (or `:` box) has focus, and
  deactivated in Eclipse mode and on focus loss (`util/KeyContextRule`). Below it is one context per letter
  (`...context.ctrl.a` ... `...ctrl.z`), each holding that letter's binding to `SendKeyHandler`; `VimKeyContexts`
  activates only the letters the *Ctrl Keys* preference page sends to Neovim in the current mode: its
  Insert choices in insert and replace mode, its Normal choices otherwise, switching only the
  difference at a mode change, in one batch. `KeyInterceptor` also leaves
  Ctrl letters given to Eclipse alone, for keys Eclipse has no binding for. Bindings use
  Eclipse's platform-neutral modifiers per platform (`M1` = Ctrl on Windows/Linux, `M4` = Control on macOS).
  `KeyContextDiagnostics` logs which command the workbench resolves for Ctrl-V, a moment after the
  contexts turn on; `util/CtrlVResolution` warns only when Ctrl-V should reach Neovim and does not
  (not when the editor lost focus before the check ran, nor where Ctrl-V goes to Eclipse by choice).
  **One parameterless command per key** (`...commands.ctrl.v`, `...ctrl.r`, ...; see `util/VimCtrlKeys`):
  a single command with a `key` parameter was tried first, but the parameter arrived as `null` on Linux.
  `PluginXmlConsistencyTest` checks that every letter has a context, a command, a handler and win32/gtk/cocoa
  bindings.
- **`I` / `A` / `c` from block mode:** Vim repeats the typed text on all lines at Esc, but only if Neovim saw
  the typing. `NeovimSession.isNeovimDrivenInsert()` is set when insert is entered from block visual;
  `KeyInterceptor` then forwards every key to Neovim, and a full re-sync follows the Esc.

## Pending commands (do not break this)

After `f F t T r`, the first `g` of `gg`, `z`, `m`, `'`, `Ctrl-W` and similar, Neovim is **blocked** waiting
for one more character. While blocked it answers only `nvim_get_mode` and `nvim_input`; every other API
call is deferred. The session therefore reads the mode first and returns immediately if `isBlocking()`.
Calling any other API (cursor, buffer, Lua) at that point would wait for a reply that can only come after
the next key, which is queued behind the same call, so each such command would cost the full RPC timeout.

Per-key cost is `nvim_input` + `nvim_get_mode` + one Lua snapshot (`LuaScripts.STATE_SNAPSHOT`) for
mode/cursor/visual selection. Keys slower than 250 ms are logged as warnings with their duration.
The mode published is the snapshot's own (`nvim_get_mode()` inside it), not the one `nvim_get_mode`
returned before: Neovim answers the snapshot only after the keys sent before it, so it cannot be
older than they are. (Publishing the earlier one after `:w` showed `-- COMMAND --` in normal mode.)

## Text synchronization details

- `core` publishes `BufferChange(firstLine, lastLine, newLines)`: lines `[first, last)` are replaced.
  `lastLine == -1` means "whole buffer".
- `util/LineRangeEdit` turns that into one `(offset, length, text)` replacement for an Eclipse
  document. It handles pure insertion (`first == last`), deletion, joins, and EOF edge cases, and is
  covered by randomized tests against a line-list model.
- `util/TextDiff` makes full re-syncs a minimal local edit rather than `IDocument.set()`, so
  annotations, folding and the caret survive.
- **Undo steps:** the changes of one Neovim command are one step in Eclipse's undo history
  (`sync/UndoSteps`): the first opens a compound change, and it is closed when the session says Neovim has
  handled the key (`BufferPort.addInputHandledListener`, fired after every piece of input-thread work, once
  Neovim's change notifications for it have been applied). So one Ctrl+Z undoes `J` (two line changes).
  Eclipse closes steps itself only on keystrokes in the widget, which Neovim's keys never reach; a step
  left open after Ctrl+Z kept a stale modification stamp, and the next Ctrl+Z back to the saved text left
  the editor marked as changed.
- **Final newline:** `text = join(lines) + (final newline ? delimiter : "")`. The flag is decided at the
  document's first push to Neovim and remembered per document (`sync/FinalNewlines`), like Vim's `'eol'`,
  so a file keeps (or lacks) its final newline exactly as it had it. It cannot be read from the text at
  each edit: after `o` on the last line of `"a"`, the text `"a\n"` is `["a", ""]` without a final
  newline, not `["a"]` with one (class comment on `util/LineTextUtil`). Later pushes send it that way too.
  With a final newline, Eclipse shows one line more than Neovim has, an empty one after it
  (`FinalNewlines.neovimLineCount`). A caret Eclipse puts there (a click below the text, `Ctrl+End`)
  becomes Neovim's cursor at the end of its last line (`editor/DocumentPositions.offsetToCursor`, used
  wherever an Eclipse position becomes Neovim's cursor), since Neovim refuses a cursor on a line it
  does not have; and text typed there is replayed at Esc as Enter and the text after that line's end,
  less a line break the text ends with, which takes the final newline's place
  (`InsertReplay.Plan.typedOnANewLineAfter`), and a cursor after Esc counted there ends at that end
  (`InsertTracker`). `offsetToCursor` is the only offset-to-position conversion the editor can call.
  Behind them, `NeovimSession.setCursor` has a safety net for a position computed some other way:
  when Neovim refuses a line past its last one, the cursor goes to the end of the last line (only a
  refusal costs the extra calls).
- **Line breaks inside a Neovim line:** Neovim keeps a carriage return inside a line (`Ctrl-V Ctrl-M`,
  `:s/x/\=nr2char(13)/`, a put of a register holding one); Eclipse's document takes it as a line
  break. So `"\ra"` is one line in Neovim and two in Eclipse, and every line below it would be
  numbered differently on each side: Neovim's next `dd` would delete the wrong line in Eclipse. The
  change is applied as Eclipse splits it (the `\r` stays in the document, so a save writes the same
  bytes), and Neovim then splits its line the same way (`LuaScripts.SPLIT_LINE_BREAKS`), so both count
  the same lines. Neovim does it, not a push of Eclipse's lines, because Neovim knows its cursor: it
  goes onto the part it was in, where the caret is. The split is queued behind the keys already sent;
  it is one step in Neovim between two keys, so it needs no pause in an insert. Until it runs,
  `sync/SplitLines` turns Neovim's line numbers into the document's, so each earlier key's line event
  still lands on the right lines. Example: `qa i Ctrl-V Ctrl-M b Esc q`, where Neovim types the whole
  insert. A handshake that lets Eclipse type (an insert's start, a hand-back after `Ctrl-R`) waits for
  the split, so Eclipse types at the right caret. The split's own line event changes no character of
  the document (the `\r` stays, not the delimiter), and the line delimiter for Neovim's new lines is
  not taken from a line that ends in such a `\r` (`BufferSynchronizer.lineDelimiter`). A NUL byte is no
  such case:
  Neovim stores it as `\n` inside a line, but its API sends it to an RPC client as `\0`, an ordinary
  character in Eclipse, and turns a pushed `\0` back into a NUL.
- **A push's echo is dropped:** Neovim sends every change as line events, Eclipse's own pushes too.
  The document has those lines already, and an echo applied again is not always harmless: one that
  removes lines removes them twice (Eclipse's Delete Line in an insert, then Esc, deleted the next
  line too). So every push brackets its change with two `eclipse_echo` notifications
  (`LuaScripts.SET_LINES_MARKED` for the attached buffer, `SET_DOCUMENT_LINES` for the others), and
  the session drops the line events between them.
- **Repairs are reported:** a push of a document of up to 20,000 lines compares Neovim's whole
  buffer with it and writes every difference (`ChangedLines.COMPARE_WHOLE_UP_TO_LINES`), so a drift
  between the two copies, whatever caused it, lasts only until the next `Esc` or Eclipse change. A
  drift still matters meanwhile: a Neovim command runs on its own copy, and `yy` then `p` can paste a
  line Eclipse never showed. So a push that finds a line no recorded change explains (one of the
  lines `util/ChangedLines` counts as unchanged since the buffer last equaled the document) reports
  it (`sync/TextRepairs`): the document's first repair as a WARNING in the Error Log, with no text
  of the file and not its name (`util/RepairReport`: which way, where, when, what kind of file, the
  versions), and the editor's status bar tells the user once per Neovim session to check the last
  change; later repairs of the same document at `FINE`. Example: Eclipse typed in line 1, Neovim's
  line 3 differs: a repair; only line 1 differs: an ordinary push.

  A drift is resolved either way, whichever comes first. A push writes Eclipse's lines into Neovim:
  at `Esc` and at a sync point, after Eclipse's own changes, after each save, and when an editor
  comes back to a document that did not change meanwhile (the last two up to
  `ChangedLines.CHECK_WHOLE_UP_TO_LINES`, 100,000 lines); these are checked and reported. A read of
  Neovim's whole buffer writes Neovim's lines into the document: at the start of an insert and after
  a `:` command (`StateSync.syncBuffer`). It is not checked: the read is also how some of Neovim's
  own changes arrive (a macro's replay, a script's `:edit!`), so a line it brings that no change
  explains is no drift there. Not checked either: the push at attach to a changed document, which
  carries what changed while Neovim was not followed. The UI
  tests fail on a repair, and also compare every open document with its buffer when a test ends
  (CONTRIBUTING.md, "Test helpers and fakes").
- **Paste, counts and `.`** are ordinary keys sent to Neovim; there is no Java-side emulation. Linewise
  paste arrives as a pure line insertion, which `LineRangeEdit` handles.
- **Columns:** Neovim cursor/visual columns are UTF-8 byte offsets. `editor/DocumentPositions` converts
  them with `util/Utf8Columns` in both directions.
- **Indentation:** buffers have no filetype, so `util/Indentation` decides the indent options per
  document (tabs or spaces and width detected from its lines, `cindent` for brace languages by file
  extension) and `NeovimSession.setIndentation` applies them at attach, together with `'tabstop'`.
- **Folding:** the widget of an editor with folding (the Java editor folds imports) leaves collapsed
  lines out, so a `StyledText` offset is not a document offset. `editor/WidgetOffsets` converts every
  caret and selection offset (`ITextViewerExtension5`).
- **Folds are mirrored** ([design/FOLDING.md](design/FOLDING.md)). `editor/FoldMirror` sends the
  viewer's projection annotations to Neovim as manual folds (`util/FoldRegions` decides which and in
  what order; `LuaScripts.FOLDS` makes them, with an extmark per fold to name it), after the
  document push, and again after every annotation change that did not come from Neovim. After each
  key the state snapshot lists the outermost closed folds, which the mirror collapses and expands in
  Eclipse (with a no-echo flag), before the caret moves: the session reports folds before the cursor,
  and both go through one UI queue. A cursor inside a closed fold puts the caret on the fold's
  caption line without unfolding (`StyledTextCaret` asks `FoldMirror.captionOffset`); an offset
  hidden by anything else is unfolded as before. Editors that cannot fold get an empty list, which
  sets `'nofoldenable'`. `vim.g.neovim4eclipse_folds = 0`, read once at start
  (`FoldsPort.foldsEnabled`), turns all of it off.

**Folding: the workarounds, and why.** Each of these looks odd on its own; each fixed a bug found by
hand or by an experiment ([design/FOLDING.md](design/FOLDING.md)). Keep them unless the
reason is gone.

| Workaround | Where | Why (and the test that holds it) |
|------------|-------|----------------------------------|
| Folds are created open, outer first, then the collapsed ones closed inner first, each on a *handle line* | `LuaScripts.FOLDS` (`set`, `handle_line`) | `:fold` makes a closed fold, and a range over a closed fold grows to cover it; `:foldclose` closes the innermost open fold on each line of its range (X3, X11, X13). `folds_spec.lua` |
| Regions that cross another's border are dropped | `util/FoldRegions.forNeovim` | Neovim widens such a fold to contain the other (X14), hiding lines Eclipse shows. `FoldRegionsTest` |
| Old folds are deleted with `:noautocmd normal! zE` | `LuaScripts.FOLDS` (`delete_all_folds`) | A plain `:normal` in insert mode passes through normal mode and fires `ModeChanged`; the plug-in took insert mode as ended and typed text went missing. `foldmethod=expr` and back does not work in insert mode (expression folds are not recomputed there). `folds_spec.lua` |
| `zf` `zF` `zd` `zD` `zE` are refused twice in mirrored buffers: by mappings of the two keys, and by a key watcher (`vim.on_key`) that drops the key when it is typed while Neovim's own pending command is `z` | `LuaScripts.FOLDS` (`refuse_keys`, `z_pending`) | Mappings of the two keys time out after `'timeoutlen'` (1000 ms by default, or the user's); a slowly typed `zE` was Neovim's and deleted every fold. `z` alone is not mapped, so the user's `z` mappings and a menu of `z` keys (which-key) work. `NeovimFoldsIntegrationTest` (`aSlowZEIsRefusedToo`, `aFoldKeyAfterNeovimsOwnPendingZ...`), `folds_spec.lua` |
| `'showcmd'` is kept on (turned on again when turned off) | `LuaScripts.FOLDS` (`OptionSet`), `LuaScripts.SETUP` | The key watcher reads Neovim's pending command through it; without it a slow `zE` would get through. `folds_spec.lua` |
| `:fold` is refused through a hook in `HOST_COMMANDS` | `LuaScripts.HOST_COMMANDS` (`refuse`) | A built-in command cannot be mapped. A refusal is an error, so it ends the rest of a `\|` line, as in Vim. `folds_spec.lua` |
| A resend during `zn` keeps Neovim's own states | `LuaScripts.FOLDS` (`states_kept`) | Eclipse shows every fold open during `zn`, so its states would make `zi` restore nothing. `folds_spec.lua` |
| Fold sends wait while Eclipse types in insert mode | `editor/FoldMirror` (`hold`, `resume`) | Neovim lacks the typed lines until `Esc`; folds sent then landed on the wrong lines. `JavaEditorEndToEndTest.aMethodTypedInInsertModeStaysAndBecomesAFold` |
| Neovim's cursor reports are dropped while Eclipse types | `editor/CaretSync.onNeovimCursor` | The caret sent to Neovim came back clamped to Neovim's old line (`CursorMovedI`). `CaretSyncTest`, `JavaEditorEndToEndTest.enterAfterABrace...` |
| Neovim's fold changes are applied as from Neovim | `NeovimEditorController.onFolds` (`CaretSync.runAsFromNeovim`) | Collapsing makes the widget move its caret below the fold; that move reached Neovim as the user's. `JavaEditorEndToEndTest.zcInTheBodyOfAMethodWithJavadocAndZoReopensIt` |
| The session reports folds before the cursor | `core/StateSync.publishState` | Both go through one UI queue: the folds must be applied before the caret lands. `StateSyncTest` |
| A caret offset still hidden after unfolding rebuilds Eclipse's hidden ranges | `editor/WidgetOffsets.toWidgetRevealing` (`ProjectionViewer.reinitializeProjection`) | JFace keeps text inserted where a hidden range starts hidden, even when the fold moves past it: `O` above a folded header comment whose `/*` line is hidden left the new line hidden and the caret on the caption. `WidgetOffsetsTest`, `JavaEditorEndToEndTest.oAboveAFoldedHeaderCommentTypesOnTheNewLine` |
| Not worked around: a `//` line typed above a folded `//` header | (Eclipse) | JDT's reconcile grows the collapsed header fold over the new comment line, hiding the line being typed; plain Eclipse does the same. |
| Not worked around: the caret is sometimes not drawn after a fold change | (Eclipse) | SWT's own behavior, also without the plug-in; noted in docs/USER_GUIDE.md, "Folding". |

## Where logic lives (and how it is tested)

| Concern | Class | Tests |
|---------|-------|-------|
| SWT event to Neovim key | `util/KeyMapping` | `KeyMappingTest` |
| Which key goes to Neovim or Eclipse | `core/KeyRouter` (SWT glue: `editor/KeyInterceptor`) | `KeyRouterTest` |
| The mouse in normal and visual mode | `editor/NeovimMouse` (which events, the character under the pointer), `core/MouseInput`, `util/LatestValue` (drags merged), `KeyInput.mouse`, `LuaScripts.MOUSE` (Neovim's screen cell) | `tests/swt` `NeovimMouseTest`, `MouseInputTest`, `LatestValueTest`, `KeyInputTest`, `NeovimSessionIntegrationTest` (real Neovim: click off its screen, word, line, drag, Shift+click, `'mouse'` off), UI `MouseEndToEndTest`, `EditorEndToEndTest` |
| Mode strings | `util/VimModes` | `VimModesTest` |
| Folding: which regions, the state diff | `util/FoldRegions`, `LuaScripts.FOLDS` / `SET_FOLDS`, the snapshot's fold element (`StateSnapshot.closedFolds`), `core/FoldsPort` | `FoldRegionsTest`, `folds_spec.lua`, `StateSnapshotTest`, `StateSyncTest`, `NeovimFoldsIntegrationTest` |
| Folding: Eclipse's annotations, the caption line | `editor/FoldMirror`, `StyledTextCaret.setFoldCaption`, `NeovimEditorController.sendFolds` | `tests/swt` `FoldMirrorTest`, UI `FoldingEndToEndTest` (test editor `TestFoldingEditor`), `JavaEditorEndToEndTest` (`-PuiHeavyJava`) |
| `:terminal` | `util/HostCommand` (`TERMINAL`), `util/EclipseTerminal` (which command id, the messages), `editor/EclipseTerminals` (runs it), `LuaScripts.TERMINALS` (a terminal started another way) | `EclipseTerminalTest`, `HostCommandRunnerTest`, `terminal_spec.lua`, UI `TerminalEndToEndTest` (no Terminal), manual card `tm-open` |
| Host `:` commands (`:w`, `:qa`, `:e`, `:tabn`, ...) | `LuaScripts.HOST_COMMANDS` (Neovim parses and hands over), `util/HostCommand` (which commands, by full name), `util/HostCommandRunner` (which IDE calls, through its `Ide` interface), `editor/HostCommands` (Eclipse's save, close, open, tabs), `editor/NewFiles` (`:e` of a new file, and the write that creates it) | `HostCommandTest`, `HostCommandRunnerTest` (fake `Ide`), `NeovimSessionIntegrationTest` (typed, mapping, macro, `@:`, arguments), `WrittenMessageTest`, UI `HostCommandsTest` (one real path per operation), `NewFileEndToEndTest` |
| Indent style per document | `util/Indentation` | `IndentationTest`, `NeovimSessionIntegrationTest`, `EditorEndToEndTest` |
| Tab arithmetic, `:e` / `:wq` file names | `util/TabNavigation`, `LuaScripts.HOST_COMMANDS` (Neovim expands the name), `util/EditPath` (fallback) | `TabNavigationTest`, `host_commands_spec.lua`, `EditPathTest` |
| The command-line window (`q:`) drawn from its grid | `util/GridEvent`, `util/Grids`, `util/GridSnapshot`, `util/CommandWindowView`, `editor/GridCanvas`, `editor/NeovimWindowPanel` | `GridEventTest`, `GridsTest`, `NotificationBridgeTest`, `NeovimSessionIntegrationTest` (real Neovim: drawn, Enter runs the line, closed in insert mode runs nothing), `tests/swt` `GridCanvasTest` (pixels: colors, cursor, text wider than its cell), `NeovimWindowPanelTest` (keys, focus), UI `CommandWindowEndToEndTest` |
| Floating windows without the focus, drawn over the text | `util/GridEvent` (`WinPos`), `util/Grids` (`floatsIfChanged`, `host`), `util/FloatWindow`, `util/FloatPlacement`, `core/NeovimConnection` (`Forwarder`: the first window's place), `editor/FloatingWindows`, `editor/GridCanvas` | `GridEventTest`, `GridsTest`, `FloatPlacementTest`, `NeovimSessionIntegrationTest` (real Neovim: reported, moves with its text, gone when closed, off by either setting), `tests/swt` `FloatingWindowsTest` (place, order, no focus, dispose), UI `FloatingWindowsEndToEndTest`, gallery picture `float` |
| Neovim's completion (`Ctrl-N`, `Ctrl-P`, `Ctrl-X` modes): the keys, the hand-back, the list | `core/KeyRouter` (`completeInNeovim`, `afterCtrlX`, `whileCompleting`), `LuaScripts.CTRL_X_GUARD` and `NO_MODELINES` (`ctrl_x_guard_spec.lua`), `core/KeyInput` (`isCompleting`, `syncInsertThenComplete`, `endCompletion`), `util/VimModes.isCompleting`, `util/KeyContextRule.toNeovim`; the list is Neovim's popup menu, drawn as a float (`util/Grids.alwaysDrawn`, `eclipseTypes`) | `KeyRouterTest`, `KeyInputTest`, `KeyContextRuleTest`, `VimModesTest`, `GridsTest`, `NotificationBridgeTest`, `NeovimCompletionIntegrationTest` |
| Neovim's command line, prompts, pager and messages drawn by ui2: turning it on, the windows' ids, `'cmdheight'`, `:messages` to its page | `LuaScripts.UI2`, `core/NotificationBridge` (`eclipse_ui2_windows`, `eclipse_cmdline`, `eclipse_messages`), `util/Grids` (`ui2Windows`, `alwaysDrawn`, `rowsBelow`), `NeovimSession.echoAsync` | `ui2_spec.lua`, `GridsTest`, `NotificationBridgeTest`, `NeovimUi2IntegrationTest` (a real UI attached: the typed line, the pager), `NeovimSessionIntegrationTest` (typing, history, Tab, prompts, search preview, `q:`, `:lcd`) |
| Eclipse's Paste, Copy, Cut and Select All while a line is open; an input method on the line | `editor/CommandLineEditHandlers`, `editor/LineInputMethod`, `util/LineComposition`, `editor/FloatingWindows.cursorCell` | `LineCompositionTest`, `tests/swt` `LineInputMethodTest` (synthetic `ImeComposition` events); a real input method: docs/MANUAL_CHECKS.md, `ime-compose`, `ime-keys` |
| File paths compared by one rule | `LuaScripts.PATHS` (module `neovim4eclipse.paths`), `util/FilePaths`, `NewFiles.workspaceFileAt` (the one workspace lookup) | `paths_spec.lua` and `FilePathsTest` (both run `tests/unit/src/test/resources/path-cases.tsv` on the Linux, macOS and Windows rules), `EditPathTest`, `t.same_file` in every spec, UI `FilePathsEndToEndTest` (`:e` and `:wq!` through a link; `:e` by the real path into a folder linked through a link), `ProjectFilesEndToEndTest` (a linked folder made and removed while running) |
| `gd`/`K`/`gr*`/`gO`/`gt`/`gT` handed to Eclipse | `core/IdeKeys` (the one table: keys, actions, how each command is found), `core/IdeAction`, `LuaScripts.IDE_KEYS` (the mappings, from the table), `IdeKeys.pick` (the one loop, over the port `IdeKeys.Editor`; `dryRun` for tests), `editor/IdeActions` (`EclipseEditor`, the port's Eclipse side), `editor/HyperlinkProbe` (links for `gd` and `grt`) | `IdeKeysTest`, `setup_spec.lua`, `NeovimSessionIntegrationTest`, `EditorEndToEndTest`, `IdeKeysEndToEndTest`, `JavaEditorEndToEndTest` (heavyJava: Java's commands, `gri`, `grt`) |
| `Ctrl-O`/`Ctrl-I` past Neovim's jumps: Back past the plug-in's own switches, Forward that retraces | `util/HistorySteps` (decisions), `util/LocalFiles` (which places count as gone), `editor/NavigationHistorySteps` (Eclipse glue), the trail of `Ctrl-O` steps in `LuaScripts.SETUP` | `HistoryStepsTest`, `LocalFilesTest`, `setup_spec.lua`, `EditorEndToEndTest` |
| Eclipse's problems as Neovim diagnostics (`]d`, `[d`) | `util/DiagnosticPositions` (offsets to lines and byte columns), `util/Diagnostic` (severity, Lua arguments), `sync/DiagnosticsSync` (annotation model to session, through `core/DiagnosticsPort`), `editor/ProblemAnnotations` (which annotations are problems), `LuaScripts.SET_DIAGNOSTICS` and `SETUP` (namespace, `on_jump` echo), `LuaScripts.PROBLEMS` (the opt-out `vim.g.neovim4eclipse_problems`, and the message `]d` gives then) | `DiagnosticPositionsTest`, `DiagnosticTest`, `tests/text` `DiagnosticsSyncTest` (real `AnnotationModel`: debounce, unchanged lists, close), `diagnostics_spec.lua` (real Neovim: `]d` with a count, Ctrl-O, the echo, the opt-out), `NeovimProblemsIntegrationTest` (the opt-out read at start), UI `DiagnosticsEndToEndTest` (a problem marker, `]d`, its message) |
| The workspace's problems and tasks in the quickfix list (`:Eproblems`, `:Etasks`, `:copen`) | `util/QuickfixEntries` (severity filter, task tags, order, Lua arguments), `editor/QuickfixLists` (markers and open editors, the job, the listener, the views), `core/QuickfixPort`, `LuaScripts.QUICKFIX`, `SET_QUICKFIX`, `QUICKFIX_CURRENT`, the `WinNew` net of `LuaScripts.WINDOWS` | `QuickfixEntriesTest`, `NotificationBridgeTest`, `NeovimQuickfixIntegrationTest` (held while Neovim waits for a key), `quickfix_spec.lua` (the commands, reuse, `'u'`, the column fix-up, `:copen`), UI `QuickfixEndToEndTest` (jumps, a closed file's column, updates, `:copen`, `:Etasks`) |
| Attach push: buffer switch, indent options, push, listening, cursor; failures; Neovim's cursor reports ignored until its cursor is placed | `sync/DocumentPush` (session through `DocumentPush.Neovim`, document through `DocumentPush.Mirror` = `BufferSynchronizer`, caret through `DocumentPush.Caret` = `CaretSync`) | `tests/text` `DocumentPushTest` (fakes: order, each failure path), `CaretSyncTest`, UI `RestartNeovimEndToEndTest` (no scroll) |
| A document that is not attached and its buffer in step (`:bufdo`, refactorings) | `sync/UnattachedDocumentSync` (session seen through `core/DocumentBufferPort`) | `tests/text` `UnattachedDocumentSyncTest` (fake port: both directions, coalescing, attach and detach, a change that no longer fits), `NeovimBufferIntegrationTest` (routing, echo), UI `EditorEndToEndTest` (`:bufdo` over three files) |
| Document and buffer in step | `sync/BufferSynchronizer` (session seen through `core/BufferPort`, UI thread through `core/UiExecutor`) | `tests/text` `BufferSynchronizerTest` (fake port, real `Document`: edit bursts, edits during a push, linked-mode mirrors, echoes, close mid-event) |
| Visual `v`/`V` selection: corner order, inclusive end, caret end | `util/VisualSelectionGeometry` (applied by `editor/VisualSelectionView` through `EditorCaret` and `BlockHighlight`) | `VisualSelectionGeometryTest`, `tests/text` `VisualSelectionViewTest` (`FakeCaret`, fake highlight) |
| Blockwise overlay painting | `editor/BlockOverlay` (the editor's `BlockHighlight`) | `tests/swt` `BlockOverlayTest` (paints into an image) |
| Caret and Neovim cursor in step | `editor/CaretSync` (caret seen through `editor/EditorCaret`, on the widget `StyledTextCaret`, which also sends the widget's selection event so the status bar position and Mark Occurrences follow) | `tests/text` `CaretSyncTest` (in-memory `FakeCaret`), `tests/swt` `StyledTextCaretTest` |
| Test text with caret and selection markers | `tests/text` helper `VimText` (`"al\|pha"`, `«selected»`, `VimText.SAMPLE`) | `VimTextTest`; used by `CaretSyncTest`, `DocumentPositionsTest` |
| Line/range/diff conversion | `util/LineTextUtil`, `LineRangeEdit`, `TextDiff` | matching `*Test` classes |
| RPC notification parsing | `core/NotificationBridge` | `NotificationBridgeTest` |
| Process and RPC stack | `core/NeovimConnection` | `NeovimSessionIntegrationTest` (needs nvim) |
| Command line that starts Neovim, with `vim.g.neovim4eclipse` for the user's config | `util/NvimExecutable` | `NvimExecutableTest`, `NeovimSessionIntegrationTest` |
| Which Neovim runs: the dedicated one if its files match the pinned tree hash, or one named program | `util/NvimExecutable`, `util/PluginNeovim.verified`, `util/InstallTree` | `NvimExecutableTest`, `PluginNeovimTest`, `InstallTreeTest`; CI's `releng/UpdateNeovimDownloads.java install` |
| Per-key state snapshot | `core/StateSnapshot` | `StateSnapshotTest` |
| A block's lines as document characters | `util/BlockSelection` | `BlockSelectionTest`, `NeovimSessionIntegrationTest` (needs nvim) |
| Key-input pipeline: pending keys, insert replay, macro playback, host `:` on Enter | `core/KeyInput` (Neovim seen through `KeyInput.Neovim`; state through `StateSync`) | `KeyInputTest` (scripted fake Neovim: request order, suppressed events), `NeovimSessionIntegrationTest` (needs nvim) |
| Re-sync and who types the insert, after each key | `util/ModeTransition` | `ModeTransitionTest` |
| One buffer per document | `core/DocumentBuffers` | `NeovimBufferIntegrationTest` (needs nvim) |
| A window per editor: which token holds which window, the travel swap | `core/EditorWindows`, `core/EditorWindow`; Neovim side `core/DocumentBuffers`, `LuaScripts.WINDOWS` | `EditorWindowsTest`, `windows_spec.lua`, `NeovimWindowsIntegrationTest` (needs nvim) |
| Status bar parts, messages and output, macro recording | `core/StatusTracker` | `StatusTrackerTest` |
| The status bar's labels: mode texts, widths, the cap, which parts show | `util/ModeLabels`, `util/StatusBarWidths`, `util/StatusItem`, `editor/StatusBarLabels`, `editor/ModeStatusContribution` | `ModeLabelsTest`, `StatusBarWidthsTest`, `StatusItemTest`, `tests/swt` `StatusBarLabelsTest`, `state_snapshot_spec.lua`, UI `StatusBarEndToEndTest` |
| Ordered RPC thread | `core/InputThread` | `InputThreadTest` |
| Typed `:` commands and searches, `:normal!` | `core/CommandRunner` (Neovim seen through `CommandRunner.Neovim`) | `CommandRunnerTest` (fake: order of calls, timeout) |
| Mode, cursor and typing state; reading it from Neovim and publishing it | `core/StateSync` (Neovim seen through `StateSync.Neovim`, listeners through `StateSync.Listeners`) | `StateSyncTest` (fake: blocked gate, unattached buffer, visual selection, when Eclipse may type, suppressed events) |
| Session listeners (mode, buffer change, cursor, selection, IDE action) | `core/SessionEvents` (on `core/ListenerList`) | `SessionEventsTest`, `ListenerListTest` |
| Session facade | `core/NeovimSession` | `NeovimSessionIntegrationTest` (needs nvim) |
| Overlay colors | `util/OverlayColors` | `OverlayColorsTest` |
| Ctrl-key ids and defaults | `util/VimCtrlKeys` | `VimCtrlKeysTest`, `PluginXmlConsistencyTest` |
| Eclipse glue | `editor/*`, `prefs/*` | `tests/swt` for widgets and the preference pages' bodies (`OverlayFrameTest`, `BlockOverlayTest`, `WidgetOffsetsTest`, `FloatingWindowsTest`, `CtrlKeysPageTest`); UI test fragment `tests/...neovim4eclipse.tests` for the workbench (`VimKeyContextsTest`, `MenuVisibilityTest`, ...) |
| Whole plug-in in a real editor | all of the above | `EditorEndToEndTest` (needs nvim and a display) |

When adding behavior, prefer putting the decision in a pure `util` class and leaving the Eclipse class
as thin glue; that is what makes it testable here.
