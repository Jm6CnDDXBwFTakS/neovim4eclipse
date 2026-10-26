# Architecture

What the plug-in does and why. For the Eclipse and Neovim concepts it is built on (bundles,
`plugin.xml`, editors, key bindings, msgpack-rpc, `nvim_input`, buffer events), read
[LEARNING.md](LEARNING.md) first.

## Goals

- Use **real Neovim** for modal editing (not a Java reimplementation of Vim).
- Keep Eclipse IDE services available on the same buffer (save, build, later: content assist coexistence).
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
"replace the name"; "replace the line" would end the rename.

In **insert/replace mode Eclipse inserts the characters itself** (no flicker, content assist and IME
keep working). On `Esc` the document is pushed to Neovim (`BufferSynchronizer.pushDocumentToNeovim`)
and *then* `<Esc>` is sent. A push replaces only the lines that differ (`LineDiff`), so it adds no
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

### 4. UI chrome

- **Mode status**: `ModeStatusContribution` on the editor status line.
- **Caret**: block vs thin via SWT `Caret` size (`CursorStyleController`).
- **Ex / search**: `:`, `/`, `?` open `CommandLineBox`, an inline `Text` child of the editor widget
  (see "Command line" below). A submitted line goes through its small `CommandLineBox.Commands`
  interface (the IDE for file and tab commands, else the session's `commandAsync`,
  `captureOutputAsync` or `search`, carried out by `core/CommandRunner`), so the box itself needs
  only SWT and is tested in `tests/swt`.

### 5. Auto-attach

`EditorAttachManager` + `EarlyStartup` register `IPartListener2` so opening/activating a text editor attaches Neovim. One controller is active at a time (switching editors detaches/reattaches).

### 6. One Neovim buffer per document

`EditorAttachManager` keeps a `DocumentBuffer` per open `IDocument` (two editors on one file share
it) in a `core/EditorBuffers` (the bookkeeping, unit-tested without Eclipse) and passes it to the controller, whose attach push (`sync/DocumentPush`) calls
`NeovimSession.showBuffer` first. If that fails, the document is not pushed (it would overwrite the
buffer Neovim is in, another file's), but the editor listens for Neovim's changes again. Buffers are
listed, named after the file path, without a swap file or filetype detection, and `buftype=acwrite`:
Neovim never writes them itself; a write (`:update` in `:bufdo`, `:wall`, a mapping's `:w`) runs their
`BufWriteCmd` hook (`LuaScripts.DOCUMENT_BUFFER`), which sends the `eclipse_write` request and waits:
`EditorAttachManager.saveForNeovim` saves the document on the UI thread (waiting at most 30 s) and
answers with nothing or an error message. An error fails the write as a real failed write does
(`nvim_echo` with `err = true`, which works like `:echoerr`): Neovim shows it, `:w | q` stops before
quitting, and `:bufdo update` stops at that buffer, which stays `'modified'`. A freshly loaded buffer is not
`'modified'`, so `:update` skips files nobody changed. Only the attached buffer's cursor reports reach Eclipse.

Neovim can change any of those buffers (`:bufdo %s/old/new/g`), and Eclipse can change a document
whose editor is in the background (a rename refactoring). Every document with a buffer therefore has a
`sync/UnattachedDocumentSync`, kept by `EditorAttachManager`, that carries changes both ways while the
document is not attached: `NeovimSession` routes the line events of a document buffer that is not
attached to that document's listener (`core/DocumentBufferPort`), which applies them with `asyncExec`
(the RPC thread must not wait, or `:bufdo`'s `BufEnter`s would queue up behind the changes); and each
change Eclipse makes to the document is pushed to its buffer (`setDocumentBufferLines`, which drops the
echo of its own write). While the document is attached, `BufferSynchronizer` does the pushing; on
detach, one push leaves the buffer equal to the document. Both sides changing the same document at once
is applied in arrival order; a Neovim change that no longer fits is logged and replaced by Eclipse's text.

A `BufEnter` autocmd reports buffer switches. Neovim's switch to another open document's buffer is
followed: its editor is activated and attached, keeping Neovim's cursor. When several editors show
that document, the first one opened is chosen. Commands such as `:bufdo`, `:argdo` and `:cdo` enter
every buffer in turn; `DocumentBuffers` remembers the buffer entered last, and a queued follow-up for a
buffer Neovim has left again is skipped, so Eclipse activates only the editor Vim ends in instead of
flicking through all of them. A switch to any other
buffer is undone (`returnToAttachedBufferAsync`). When a document's last editor closes, its buffer
is wiped (`closeBufferAsync`).

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

## Ideas for future work

| Topic | Hint |
|-------|------|
| Content assist | Temporarily release keys or use Neovim LSP bridge |
| Highlights | `nvim_buf_get_extmarks` → Eclipse annotations |
| True block visual | Custom paint on `StyledText` or block selection API if available |
| Cmdline | The box takes over command lines Neovim opens (`ext_cmdline`, see "Command lines Neovim opens"); a further step would make the box a view of Neovim's own line (history, `<C-r>`, command-line mappings), replacing `CommandRunner`'s typed commands |
| New-file buffer leak, shared editors | A new file's editor is opened by location and switched to the workspace file at its first `:w`, which then releases the file buffer Eclipse leaves behind (`NewFiles.releaseLeftOverBuffer`, see [NEW_FILE.md](design/NEW_FILE.md)). The switch is skipped when two editors show the new file (*Window › New Editor* before the first `:w`), so closing them leaves that buffer connected. Then, after the file is deleted, `:e` of the same name shows the old text in a clean editor (nothing is lost: a `:w` writes what is shown). Fix for every case: when an editor opened by location closes and its file now exists in the workspace, release the buffer under the unnormalized location if no other editor uses it (a hook in `EditorAttachManager`'s part-closed handling, plus a UI test) |

## Threading rules

| Thread | Allowed |
|--------|---------|
| SWT UI | Widget updates, `VerifyKeyListener`, the command-line box, all `IDocument` edits and reads (a push from the input thread takes its snapshot of the text with `syncExec`) |
| input executor | All blocking `await(...)` RPC |
| RPC reader (`RpcChannel`, one per session) | Completing the futures; dispatch to listeners; schedule UI work with `Display.asyncExec`; answer Neovim's requests (a clipboard paste waits for the UI thread at most 2 s, a save at most 30 s) |

Never call `await` on the UI thread for key handling.

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


## Status bar items

`ModeStatusContribution` shows the parts selected by the `statusItem*` preferences (`util/StatusItem`;
composition and whitespace cleaning in the tested `util/StatusLineFormat`). Besides the mode, the parts come
from the same single `STATE_SNAPSHOT` call, with two flags so that only enabled parts are evaluated:

- **statusline:** `nvim_eval_statusline(vim.o.statusline or a Vim-like default)`, so a user's `%!` expressions
  and plugins such as lualine are honored.
- **cmd:** `nvim_eval_statusline('%S')`. `%S` needs `showcmdloc=statusline` (set in `LuaScripts.SETUP`).
  While Neovim is *blocked* waiting for a character no API call is possible, so the key just sent is
  appended to the last known text (`2` then `f` shows `2f`).

The same call always reads `v:statusmsg` and `v:errmsg`, *cleared after reading* so each message is seen
once (an error wins, and `StatusInfo.error` says which it was). The session keeps the message until the next
key (`core/StatusTracker`), and says so at once when that key clears it, so the same error twice shows twice.
The message does not go to the status bar but to `editor/MessageLine`: a one-line `CLabel` in an
`OverlayFrame` along the bottom of the editor, where the `:` box opens, in the theme's error color
(`JFacePreferences.ERROR_COLOR`, or `OverlayColors.errorText` outside a theme) for errors. It hides on the
next key or click, never takes the focus, and with the `flashOnError` preference tints the editor's
background for `FLASH_MS` on an error. Host command errors (`HostCommands.showError`) go to the same line.

`NeovimSession` publishes a `StatusInfo` to listeners only when it changes. A preference change reaches the
session through `Activator.applyStatusItems` and redraws via `EditorAttachManager.refreshStatusLine`.

## Command line

`editor/CommandLineBox` shows a single-line `Text` as a child of the editor's `StyledText`, placed near the
bottom edge (about half its height above it, clear of the scroll bar; width minus 3 px padding; editor font).
`editor/OverlayFrame` gives it, and the output popup, a 1-pixel border and a background tinted slightly
toward gray, both derived from the editor's colors (`util/OverlayColors`). The border is drawn by the
plug-in because native borders differ per platform (Windows 11 draws none). It is positioned by hand on resize
*and on every paint* of the host, so scrolling cannot leave it behind. It is non-modal: opening it moves focus
to the box, which (via the focus listener in the controller) also deactivates the Vim key context.

- The prefix (`:`/`/`/`?`) is the first character of the text and is protected by a `VerifyListener`.
- **Click-away cancel:** a focus listener on the box closes it when it loses focus (a click in the editor, another view,
  switching windows). Pressing `:` again while it is still open only refocuses it, keeping its text.
- It is opened with `asyncExec`: on some platforms the key's character event follows the key-down after
  focus moved and would land in the box, so a one-character echo of the opening key is ignored for 150 ms.
- `:` in visual mode sends `<Esc>` and pre-fills `:'<,'>` (also required because typing `:` in visual mode
  makes Neovim insert its own range, which would precede `:silent` and be rejected).
- Pure logic is in `util/CommandLine` (kinds, prefill, parsing) and `util/CommandHistory`, both unit tested;
  host commands are classified by `util/HostExCommand`, turned into IDE calls by `util/HostCommandRunner`
  (`:wq` saves, then closes only if the save worked) and carried out by `editor/HostCommands`.
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
  `nvim_ui_attach`. An error there leaves it at a hit-enter prompt that no one sees, answering only
  `nvim_get_mode`. `NeovimConnection.waitForStartup` therefore waits for an idle answer, dismisses
  such a prompt with Esc meanwhile, and keeps the errors (`:messages`) for a one-time warning
  (`editor/StartupErrorNotice`, `util/StartupErrorReport`).
- **Command lines Neovim opens:** the UI is attached with `ext_cmdline` (and nothing else of the
  UI protocol), so Neovim reports each command line it opens as a `cmdline_show` redraw event
  (`util/CmdlineEvent`, read in `NotificationBridge`). `util/CmdlineWatcher` drops the plug-in's own
  (each typed `:silent cmd<CR>` and `/pattern<CR>` is announced first) and reports the user's: a
  mapping (`nnoremap ; :`), a count before `:`, `!` with a motion. The controller then opens the box
  with Neovim's text. A `:` line is closed in Neovim at once (`<C-c>`) and the box runs its command as
  usual; a search line stays open, since it may belong to an operator (`d/`), and Enter answers it
  (`<C-u>pattern<CR>`, `CommandRunner.answerSearch`), Esc closes it. See
  [design/KEY_ROUTING.md](design/KEY_ROUTING.md), phase 2.
- **Output:** listing commands (`util/ListingCommand`) run through `nvim_exec2` with output capture and
  are not typed. Every other command is typed (`:silent cmd<CR>`, so macros and `@:` see it) between
  `redir => g:neovim4eclipse_output` and `redir END` (`LuaScripts.START/STOP_OUTPUT_CAPTURE`); a
  non-empty result that is not just the message the message line shows (`v:errmsg`, or `v:statusmsg` such
  as "7 substitutions on 4 lines") is shown in the `OutputPopup`. Ending the
  redirection waits for the command; meanwhile a prompt is dismissed with Esc, and after 60 s the
  output is given up (`core/CommandRunner`, durations set in its constructor). Full
  `ext_messages` support was considered and not chosen: it needs `ext_linegrid`, i.e. grid redraw
  traffic on every key, and a redraw-event parser.
- **Tab completion** (`:` box only): each Tab sends the line as it was before the first Tab plus the keys
  pressed so far (`util/CommandCompletion`) to `NeovimSession.completeAsync`, whose one Lua call
  (`LuaScripts.COMPLETE_CMDLINE`) types them into Neovim's command line, reads the result and cancels.
  So Neovim never stays in command-line mode, and the completion is exactly Neovim's. The answer
  (`util/Completion`) is applied only if nothing was typed meanwhile; the candidates are shown in a
  one-line `StyledText` above the box. Details and the experiments behind them: `docs/design/TAB_COMPLETION.md`.

## Macros

Neovim records and plays macros natively; the plugin only has to make sure Neovim *receives everything as
keys* and to keep the editor in step with playback.

- **Recording state:** `STATE_SNAPSHOT` returns `reg_recording()`; `NeovimSession` fires a recording
  listener on change and the controller shows `recording @a` in the status line (`VimModes.statusText`).
- **Insert while recording:** `isNeovimDrivenInsert()` is also set when `isRecording()`, so every key goes to
  Neovim and the recording is complete. (When *not* recording, Eclipse types and the text is replayed at Esc
  for `.`; replayed keys would also be recorded, but only exact keystrokes make a faithful macro.)
- **`:` commands:** `commandAsync` types `:silent <cmd><CR>` instead of calling `nvim_command`, which Neovim
  would neither record nor remember for `@:`. A "press Enter" prompt is dismissed with Esc.
- **Playback (`@x`):** `@` is a pending-character command (see "Pending commands"). The next key starts the
  playback: buffer events are suppressed (`suppressBufferEvents`), and when Neovim is idle the whole buffer is
  fetched once (timeout 60 s) and applied as one minimal edit (`syncBufferAfterMacro`).
- **Arguments vs prompts:** a `:` `/` `?` opens the editor's prompt only where Vim would start a command
  line: not after `f F t T r @ m ' \` "`, a prefix (`g z [ ] Z <C-w>`), `i`/`a` in an operator, or a
  half-typed mapping, where it is that command's argument and goes to Neovim; and `g??` is the `g?`
  operator doubled. `KeyRouter` takes Neovim's answer (`nvim_get_mode`: `blocking`, operator-pending)
  when Neovim has processed every key sent, and `util/KeyGrammar`'s reading of the keys while some are
  still in flight. A `q` starts a recording only where it is a command (not in `gqq` or `<C-w>q`), and
  stops one while recording. `KeyGrammarNeovimTest` (integration) compares the grammar with a real
  Neovim over a list of sequences; see [design/KEY_ROUTING.md](design/KEY_ROUTING.md).

## Handing typing over to Eclipse

Entering insert mode is followed by a full buffer sync from Neovim (`KeyInput`, decided by `util/ModeTransition`), which the editor applies
asynchronously on the UI thread. Text Eclipse typed before that sync landed would be overwritten by it. So
Eclipse types only once the sync has been applied: the sync's `BufferChange` carries an `afterApply` action,
run by `BufferSynchronizer` after applying it, which sets `NeovimSession.isEclipseTypingReady()`. A key that
arrives in insert mode before then makes the rest of that insert Neovim-driven (`driveInsertFromNeovim`):
Neovim types it, in order, and keeps typing until Esc, since mixing the two could let Neovim's update for
an earlier key overwrite a later one typed by Eclipse. People rarely type that fast; `EditorEndToEndTest`
does. Keys sent before Neovim even reports insert mode simply reach Neovim in order and are inserted there.

## Dot-repeat of text typed in insert mode

Eclipse types in insert mode, so Neovim's redo buffer would only hold the entry command (`A`) and `.` would
repeat an empty insert. At Esc `KeyInterceptor` asks the controller for a replay plan:

1. `InsertTracker` (an `IDocumentListener`) snapshots the document text and offset at the *first* change of
   the insert. At that moment the text equals Neovim's buffer. Which changes count is decided by
   `util/InsertReplay.tracksChange`: typing in insert mode only, not Neovim's own changes, replace mode or
   a Neovim-driven insert.
2. `util/InsertReplay.plan` checks that the final text is that snapshot plus one plain insertion at that
   offset, and converts the inserted text to Neovim keys (`<` as `<lt>`, newline as `<CR>`, ...).
3. `NeovimSession.leaveInsertModeAsync(plan)` (input thread) moves Neovim's cursor to the insertion point,
   types the text with `nvim_input` in small chunks (each followed by a cheap request, which Neovim only
   answers after consuming the keys), sends `<Esc>`, then pushes Eclipse's document (authoritative: it may
   differ in auto-indent or bracket completion) and puts the cursor where Vim leaves it after Esc.
4. While re-typing, Neovim's own buffer events are suppressed (`suppressBufferEvents`) because the document
   already contains the text.

Any non-replayable edit yields no plan and the old path (push, then Esc) runs. Insert-mode mappings and
abbreviations are cleared at startup (`LuaScripts.SETUP`), otherwise a mapping such as `jk` to Esc would end
the replay early.

## Blockwise visual

- **Geometry:** `util/BlockSelection` converts Neovim's two corners (line + UTF-8 byte column) into per-line
  character ranges using virtual columns (tabs, wide characters, `$`). Tested without Eclipse.
- **Display:** SWT's block selection is pixel based, so the native selection is cleared and the rectangle
  is painted as an overlay by `editor/BlockOverlay` (visible lines only).
- **Tab width:** `NeovimEditorController` sets Neovim's `tabstop` to the editor's tab width on attach, so
  Neovim's block commands and the overlay use the same columns.
- **Keys:** the workbench dispatches key bindings *before* widget key listeners, so `Ctrl-V` etc. would never
  reach `KeyInterceptor`. A context (`io.github.jm6cnddxbwftaks.neovim4eclipse.context.normal`) is activated
  workbench-wide while Neovim owns normal/visual keys **and** the editor's text widget has focus, and
  deactivated in insert mode, in Eclipse mode and on focus loss. Below it is one context per letter
  (`...context.ctrl.a` ... `...ctrl.z`), each holding that letter's binding to `SendKeyHandler`; `VimKeyContexts`
  activates only the letters the *Ctrl Keys* preference page sends to Neovim. `KeyInterceptor` also leaves
  Ctrl letters given to Eclipse alone, for keys Eclipse has no binding for. Bindings use
  Eclipse's platform-neutral modifiers per platform (`M1` = Ctrl on Windows/Linux, `M4` = Control on macOS).
  `KeyContextDiagnostics` logs which command the workbench resolves for Ctrl-V.
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

## Text synchronization details

- `core` publishes `BufferChange(firstLine, lastLine, newLines)`: lines `[first, last)` are replaced.
  `lastLine == -1` means "whole buffer".
- `util/LineRangeEdit` turns that into one `(offset, length, text)` replacement for an Eclipse
  document. It handles pure insertion (`first == last`), deletion, joins, and EOF edge cases, and is
  covered by randomized tests against a line-list model.
- `util/TextDiff` makes full re-syncs a minimal local edit rather than `IDocument.set()`, so
  annotations, folding and the caret survive.
- **Final newline:** `text = join(lines) + (document ends with a line break ? delimiter : "")`. The flag is
  read from the current document on every edit, never stored, so a file keeps (or lacks) its final newline
  exactly as it had it. See the class comment on `util/LineTextUtil` for the one inherent ambiguity.
- **Paste, counts and `.`** are ordinary keys sent to Neovim; there is no Java-side emulation. Linewise
  paste arrives as a pure line insertion, which `LineRangeEdit` handles.
- **Columns:** Neovim cursor/visual columns are UTF-8 byte offsets. `editor/DocumentPositions` converts
  them with `util/Utf8Columns` in both directions.
- **Indentation:** buffers have no filetype, so `util/Indentation` decides the indent options per
  document (tabs or spaces and width detected from its lines, `cindent` for brace languages by file
  extension) and `NeovimSession.setIndentation` applies them at attach, together with `'tabstop'`.
- **Folding:** the widget of an editor with folding (the Java editor folds imports) leaves collapsed
  lines out, so a `StyledText` offset is not a document offset. `editor/WidgetOffsets` converts every
  caret and selection offset (`ITextViewerExtension5`) and unfolds code Neovim's cursor moves into.

## Where logic lives (and how it is tested)

| Concern | Class | Tests |
|---------|-------|-------|
| SWT event to Neovim key | `util/KeyMapping` | `KeyMappingTest` |
| Which key goes to Neovim or Eclipse | `core/KeyRouter` (SWT glue: `editor/KeyInterceptor`) | `KeyRouterTest` |
| Mode strings | `util/VimModes` | `VimModesTest` |
| Host `:` commands (`:w`, `:qa`, `:e`, `:tabn`, ...) | `util/HostExCommand` (which command), `util/HostCommandRunner` (which IDE calls, through its `Ide` interface), `editor/HostCommands` (Eclipse's save, close, open, tabs), `editor/NewFiles` (`:e` of a new file, and the write that creates it) | `HostExCommandTest`, `HostCommandRunnerTest` (fake `Ide`), `WrittenMessageTest`, UI `HostCommandsTest` (one real path per operation), `NewFileEndToEndTest` |
| Indent style per document | `util/Indentation` | `IndentationTest`, `NeovimSessionIntegrationTest`, `EditorEndToEndTest` |
| Tab arithmetic, `:e` path lookup | `util/TabNavigation`, `util/EditPath` | `TabNavigationTest`, `EditPathTest` |
| Tab completion in the `:` box | `util/CommandCompletion` (cycle, stale answers, list line), `util/Completion` (the answer), `core/LuaScripts.COMPLETE_CMDLINE`, `editor/CommandLineBox` | `CommandCompletionTest`, `CompletionTest`, `CommandCompletionIntegrationTest` (real Neovim), `tests/swt` `CommandLineBoxTest`, UI `EditorEndToEndTest` |
| `gd`/`K`/`gt`/`gT` handed to Eclipse | `core/IdeAction`, `editor/IdeActions` | `NeovimSessionIntegrationTest`, `EditorEndToEndTest` |
| Attach push: buffer switch, indent options, push, listening, cursor; failures | `sync/DocumentPush` (session through `DocumentPush.Neovim`, document through `DocumentPush.Mirror` = `BufferSynchronizer`) | `tests/text` `DocumentPushTest` (fakes: order, each failure path) |
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
| Per-key state snapshot | `core/StateSnapshot` | `StateSnapshotTest` |
| Key-input pipeline: pending keys, insert replay, macro playback, host `:` on Enter | `core/KeyInput` (Neovim seen through `KeyInput.Neovim`; state through `StateSync`) | `KeyInputTest` (scripted fake Neovim: request order, suppressed events), `NeovimSessionIntegrationTest` (needs nvim) |
| Re-sync and who types the insert, after each key | `util/ModeTransition` | `ModeTransitionTest` |
| One buffer per document | `core/DocumentBuffers` | `NeovimBufferIntegrationTest` (needs nvim) |
| Status bar parts, macro recording | `core/StatusTracker` | `StatusTrackerTest` |
| Messages and errors in the editor, error flash | `editor/MessageLine` (colors: `util/OverlayColors`) | `tests/swt` `MessageLineTest`, `OverlayColorsTest`, UI `MessageLineEndToEndTest` |
| Ordered RPC thread | `core/InputThread` | `InputThreadTest` |
| `:` commands with output capture, listings, `:normal!`, search | `core/CommandRunner` (Neovim seen through `CommandRunner.Neovim`) | `CommandRunnerTest` (fake: capture around typing, user `:redir`, prompt Esc, timeout) |
| Mode, cursor and typing state; reading it from Neovim and publishing it | `core/StateSync` (Neovim seen through `StateSync.Neovim`, listeners through `StateSync.Listeners`) | `StateSyncTest` (fake: blocked gate, unattached buffer, visual selection, when Eclipse may type, suppressed events) |
| Session listeners (mode, buffer change, cursor, selection, IDE action) | `core/SessionEvents` (on `core/ListenerList`) | `SessionEventsTest`, `ListenerListTest` |
| Session facade | `core/NeovimSession` | `NeovimSessionIntegrationTest` (needs nvim) |
| Overlay colors | `util/OverlayColors` | `OverlayColorsTest` |
| Ctrl-key ids and defaults | `util/VimCtrlKeys` | `VimCtrlKeysTest`, `PluginXmlConsistencyTest` |
| Eclipse glue | `editor/*`, `prefs/*` | `tests/swt` for widgets (`OverlayFrameTest`, `BlockOverlayTest`, `WidgetOffsetsTest`, `OutputPopupTest`); UI test fragment `tests/...neovim4eclipse.tests` for the workbench (`VimKeyContextsTest`, `CtrlKeysPreferencePageTest`, ...) |
| Whole plug-in in a real editor | all of the above | `EditorEndToEndTest` (needs nvim and a display) |

When adding behavior, prefer putting the decision in a pure `util` class and leaving the Eclipse class
as thin glue; that is what makes it testable here.
