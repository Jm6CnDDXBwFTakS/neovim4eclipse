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

**The mouse is Neovim's too** (normal and visual mode). `editor/NeovimMouse` puts a display filter in front of
the editor widget and swallows the left button's press, drag, release and double-click event (`event.type =
SWT.None`), so the widget neither moves its caret nor selects. Each becomes a `core/MouseInput` (the document
line and byte column of the character under the pointer, Shift and Alt as `S`/`A`), which the session hands to
`KeyInput.mouse`: `LuaScripts.MOUSE` finds the character on Neovim's screen with `screenpos()` and calls
`nvim_input_mouse`, then the state is read as after a key. Neovim does the rest: one click moves the cursor, two
select a word (from a bracket, up to its match), three the line (`V`), four a block, a drag selects, Shift+click
extends, `'mouse'`, `'mousetime'` and `<2-LeftMouse>` mappings apply, and a click while recording is recorded.

- *Neovim's screen is not the editor's.* Neovim's window (40 rows) does not scroll with the editor, so a
  character may be off its screen. The script then scrolls Neovim's window for a press; for a drag in visual mode
  it moves the cursor there (which extends the selection, as the drag would; scrolling first would lose the drag's
  start); a first drag goes to the nearest edge of Neovim's screen. Measured: about 0.2 ms per event, the state
  read included.
- *Drags are merged.* Mouse moves can come faster than Neovim answers; `util/LatestValue` keeps only the newest
  drag until the input thread sends it, and a release still comes after it.
- *Stays Eclipse's:* insert and replace mode and Eclipse mode (all of the mouse), Ctrl+click and Cmd+click
  (hyperlinks; on macOS also the context menu), the right and middle buttons, moves without a button (hovers).
- *Dropped:* an event while Neovim waits for the rest of a command (`d`, `f`): Neovim would not run the script
  until then.
- With `'mouse'` empty (or without `n`/`v`) a press only moves the cursor, as before the mouse was forwarded.

### 4. UI chrome

- **Mode status**: `ModeStatusContribution` on the editor status line.
- **Caret**: block vs thin via SWT `Caret` size (`CursorStyleController`).
- **Ex / search**: `CommandLineBox`, an inline `Text` child of the editor widget, shows Neovim's command
  line (see "Command line" below). Its keys and pastes go to the session through its small
  `CommandLineBox.Commands` interface, so the box itself needs only SWT and is tested in `tests/swt`.
  What a command prints comes back from Neovim as message events (see "Messages and output").

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
composition and whitespace cleaning in the tested `util/StatusLineFormat`). Besides the mode:

- **statusline:** from the per-key `STATE_SNAPSHOT` call, `nvim_eval_statusline(vim.o.statusline or a
  Vim-like default)`, so a user's `%!` expressions and plugins such as lualine are honored. A flag says
  whether to evaluate it at all.
- **cmd:** pushed by Neovim as a `msg_showcmd` event (see below), also while Neovim is *blocked* waiting
  for a character, when no API call is possible: `2` then `f` arrives as `2f`.

`NeovimSession` publishes a `StatusInfo` to listeners only when it changes. A preference change reaches the
session through `Activator.applyStatusItems` and redraws via `EditorAttachManager.refreshStatusLine`.

## Messages and output

The UI is attached with `ext_messages` (`NeovimConnection.TERMINAL_UI`), so Neovim sends every message as
a UI event inside `redraw` notifications instead of drawing it into a grid nobody sees: `msg_show` with a
*kind* (`emsg`, `echo`, `list_cmd`, `shell_out` ...), `msg_showcmd` for the pending command,
`msg_history_show` for `:messages`, and `flush` at the end of each redraw. `util/MessageEvent` reads them;
`NotificationBridge` hands them to `core/StatusTracker`, which collects the messages of one command (from a
key or a submitted line until the next) in a `util/MessageRoute` and passes on the result at each `flush`:

| What arrived | Where it goes | Example |
| --- | --- | --- |
| one message of one line | the message line; errors (`emsg`, `echoerr`, `lua_error`, `rpc_error`) in the error color | `3 lines yanked`, `E486: Pattern not found: x`, `:echo "hi"` |
| a listing or shell output (`list_cmd`, `shell_*`, `:messages`), even one line | the output popup, titled with the command line | `:ls`, `:set ts?`, `:!make` |
| more than one line, in one message or several | the output popup, as Vim shows it above "Press ENTER" | a failed save with its `Error in BufWriteCmd Autocommands` header, a Lua traceback |
| the echo of a search (`search_cmd`) or of a shell command (first line of `shell_cmd`), empty messages | nowhere | |

`replace_last` (a search's `[2/5]` count replacing its echo), `append` (shell output in chunks) and the
message id (`:w`'s `bufwrite` progress, sent twice) update what was collected; growing output reaches the
popup again, whole, and the open popup updates in place. The message line is `editor/MessageLine`: a
one-line `CLabel` in an `OverlayFrame` along the bottom of the editor, where the `:` box opens, in the
theme's error color (`JFacePreferences.ERROR_COLOR`, or `OverlayColors.errorText` outside a theme) for
errors. A message stays until the next key or click (the session says so at once, so the same error twice
shows twice), never takes the focus, and with the `flashOnError` preference an error tints the editor's
background for `FLASH_MS`. Host command errors (`HostCommands.showError`) go to the same line.

With `ext_messages` Neovim never waits at a "Press ENTER" or "-- More --" prompt (experiment X2 of the
cleanup plan): there is no message area to fill. So nothing in the plug-in dismisses one any more, and
messages that used to be lost arrive too: a timer's, an autocommand's, one echoed later with
`vim.schedule` (the diagnostics plan's jump message). `ext_messages` turns on `ext_linegrid`; its grid
events are ignored, and are fewer per key than the old cell-by-cell drawing.

## Command line

`editor/CommandLineBox` shows a single-line `Text` as a child of the editor's `StyledText`, placed near the
bottom edge (about half its height above it, clear of the scroll bar; width minus 3 px padding; editor font).
`editor/OverlayFrame` gives it, and the output popup, a 1-pixel border and a background tinted slightly
toward gray, both derived from the editor's colors (`util/OverlayColors`). The border is drawn by the
plug-in because native borders differ per platform (Windows 11 draws none). It is positioned by hand on resize
*and on every paint* of the host, so scrolling cannot leave it behind. It is non-modal: opening it moves focus
to the box. The message line moves up above the box while it is open (`MessageLine.makeRoomBelow`), so a
message that arrives meanwhile (a timer's `:echo`) does not cover it.

**The line is Neovim's** (step A2 of the cleanup plan; until 0.1.7 the box edited its own text and ran it
on Enter). The UI is attached with `ext_cmdline` and `ext_popupmenu`, so Neovim reports its command line as
events instead of drawing it: `cmdline_show` (text, byte cursor, type or prompt, level), `cmdline_pos`,
`cmdline_special_char` (`"` after `<C-r>`), `cmdline_hide`, and the completion menu `popupmenu_show` /
`popupmenu_select` / `popupmenu_hide` (grid -1 for the command line). `util/CmdlineEvent` reads them,
`util/CmdlineState` keeps the line (`View`: text, caret, candidates), and the session passes each changed
view to the controller, which shows it in the box. The box edits nothing:

- **Keys** go to Neovim in its notation (`util/KeyMapping`; Tab and Shift+Tab from the traversal event),
  through the session's input thread, in order with the editor's own keys. Up/Down are Neovim's history,
  Tab its completion (`'wildmode'`), `<C-r>` its registers; command-line mappings and abbreviations apply.
- **Text that is no key** (Ctrl+V or Cmd+V paste, Shift+Insert, an input method's committed text, a drop)
  reaches the box as an edit, which the `VerifyListener` refuses and sends as `nvim_paste`. Ctrl+C with a
  selection copies; without one it is Neovim's `<C-c>`.
- **Eclipse's key bindings** run before the box sees a key, and the box is inside the editor, so Paste
  would paste into the file and Ctrl+W close the editor. While the box has the focus the Vim Ctrl-key
  contexts stay active (`EditorKeyContext`), and `SendKeyHandler` hands those keys to
  `CommandLineBox.ctrlKey`: Neovim's line gets them (`<C-r>`, `<C-u>`, `<C-n>`), except Ctrl+V (paste)
  and Ctrl+C with a selection (copy) where Ctrl is the platform's own modifier for them. While the box is
  open, `editor/CommandLineEditHandlers` activates handlers for Copy, Paste, Cut and Select All in the
  editor site's handler service; their expression ranks as depending on the focus control, above the
  editor's own handlers, so Cmd+V and Shift+Insert paste into the box.
- **Opening and closing** follow Neovim: the box opens on `cmdline_show`, whoever opened the line (`:` or
  `/` typed in the editor, which now simply goes to Neovim; a mapping; a count; `:` in visual mode, where
  Neovim inserts `'<,'>` itself; an operator's `d/`; `input()`, `confirm()`), and closes on `cmdline_hide`.
- **Click-away:** when another control of Eclipse's takes the focus (a display `FocusIn` filter, not the
  box's own focus-lost event) the box sends `<C-c>` and closes; views of that line still on their way are
  ignored until Neovim reports it closed. Focus that leaves Eclipse altogether (another application, an
  input method's switcher window such as Super+Space on Linux) keeps the line, and the box has the focus
  again when Eclipse's window does.
- **Candidates** are fitted into one line above the box around the selected one (`util/CandidateLine`).
- **Search preview:** while a `/` or `?` line is open, `win_viewport` carries Neovim's `'incsearch'`
  match (no `CursorMoved` fires then); the session dispatches it as the cursor, so the caret and view
  follow the match, and Esc or Enter then set the real cursor through the state read after that key.
- **The plug-in's own lines** (`commandAsync` types `:cmd<CR>`) are not shown: `util/CmdlineWatcher`
  is told before each one and drops its events.
- **`q:`** (the command-line window) is closed as soon as it opens, by a `CmdwinEnter` autocommand in
  `LuaScripts.SETUP`, with a message: Eclipse cannot show a second window.
- **Working folder:** the controller sets the window's folder (`:lcd`, silently) to the editor's project
  root, or the file's folder outside a project, so that `:e` completion and `:!` start there (decision D5).
  Where `:e` looks for a name is still the plug-in's rule, the file's folder first (`util/EditPath`).
- **Host commands:** on Enter, `KeyInput.tryHostCommandLine` reads Neovim's line (`getcmdline()`) and
  offers it to the editor; a command Eclipse runs (`:w`, `:q`, `:e`, `:tabn` ...) is closed in Neovim with
  Esc, which keeps it in the history.
- Host commands are classified by `util/HostExCommand`, turned into IDE calls by `util/HostCommandRunner`
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
  `nvim_ui_attach`. An error there is a message event that arrives before Eclipse listens, and
  Neovim goes on (with `ext_messages` there is no hit-enter prompt to wait at).
  `NeovimConnection.waitForStartup` therefore waits for an idle answer and keeps the errors
  (`:messages`) for a one-time warning
  (`editor/StartupErrorNotice`, `util/StartupErrorReport`).
- **Output:** a command runs in Neovim as typed, so the history, macros and `@:` see it, listings
  such as `:ls` too. What it prints arrives as message events and goes to the message line or the
  `OutputPopup` (see "Messages and output"); output that arrives while a line is open does not take the
  box's focus. The plug-in redirects nothing itself, so a user's own `:redir` works as in Vim.
  (`core/CommandRunner` still types whole lines for `commandAsync`, waiting until Neovim is idle, at most
  60 s, before refreshing Eclipse.)

## Macros

Neovim records and plays macros natively; the plugin only has to make sure Neovim *receives everything as
keys* and to keep the editor in step with playback.

- **Recording state:** `STATE_SNAPSHOT` returns `reg_recording()`; `NeovimSession` fires a recording
  listener on change and the controller shows `recording @a` in the status line (`VimModes.statusText`).
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
  `fZ`. The box opens only when Neovim reports its command line (`cmdline_show`), and the recording
  state comes from Neovim (`reg_recording()` in the snapshot). `ZZ` and `ZQ` are Neovim mappings to
  Eclipse's save and close (`IdeAction`, below), never Neovim's own, which would quit the embedded
  Neovim. Until 0.1.7, `KeyRouter` guessed all of this on the UI thread from the keys typed so far
  (`util/KeyGrammar`, removed in step B1 of the cleanup plan), because the box was its own and had to
  open before Neovim saw the key; see [design/KEY_ROUTING.md](design/KEY_ROUTING.md). Example: `qaihello<Esc>q`
  typed at full speed records the insert, because the `i` reaches Neovim before Eclipse can take
  over the typing, so Neovim types the insert and records it (next section).

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

- **Geometry:** Neovim's. In blockwise mode `STATE_SNAPSHOT` adds `getregionpos(getpos('v'), getpos('.'),
  { type = mode(), eol = true })` (Neovim 0.10): for each line, the first and last byte the block covers,
  with tabs, wide characters, combining marks and `$` worked out the way Neovim's own `d`, `y`, `I` act
  on them. `util/BlockSelection` only turns those bytes into the line's characters (a character partly
  inside is included whole; a line too short to reach the block is left out). Example: `Ctrl-V`, `j`,
  `l` from the start of `abc` and `def` arrives as `{1, 1, 2, 2, 1, 2}` (line, first, last byte).
  5000 lines take about 3 ms in Neovim (experiment X5). Until 0.1.7 the cell arithmetic was Java's, from
  the two corners, `curswant` and `'tabstop'`.
- **Display:** SWT's block selection is pixel based, so the native selection is cleared and the rectangle
  is painted as an overlay by `editor/BlockOverlay` (visible lines only).
- **Tab width:** `NeovimEditorController` sets Neovim's `tabstop` to the editor's tab width on attach, so
  the rectangle Neovim measures is the one the editor shows.
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
| The mouse in normal and visual mode | `editor/NeovimMouse` (which events, the character under the pointer), `core/MouseInput`, `util/LatestValue` (drags merged), `KeyInput.mouse`, `LuaScripts.MOUSE` (Neovim's screen cell) | `tests/swt` `NeovimMouseTest`, `MouseInputTest`, `LatestValueTest`, `KeyInputTest`, `NeovimSessionIntegrationTest` (real Neovim: click off its screen, word, line, drag, Shift+click, `'mouse'` off), UI `MouseEndToEndTest`, `EditorEndToEndTest` |
| Mode strings | `util/VimModes` | `VimModesTest` |
| Host `:` commands (`:w`, `:qa`, `:e`, `:tabn`, ...) | `util/HostExCommand` (which command), `util/HostCommandRunner` (which IDE calls, through its `Ide` interface), `editor/HostCommands` (Eclipse's save, close, open, tabs), `editor/NewFiles` (`:e` of a new file, and the write that creates it) | `HostExCommandTest`, `HostCommandRunnerTest` (fake `Ide`), `WrittenMessageTest`, UI `HostCommandsTest` (one real path per operation), `NewFileEndToEndTest` |
| Indent style per document | `util/Indentation` | `IndentationTest`, `NeovimSessionIntegrationTest`, `EditorEndToEndTest` |
| Tab arithmetic, `:e` path lookup | `util/TabNavigation`, `util/EditPath` | `TabNavigationTest`, `EditPathTest` |
| The `:` box as a view of Neovim's command line: events, state, candidates, own lines | `util/CmdlineEvent`, `util/CmdlineState`, `util/CandidateLine`, `util/CmdlineWatcher`, `editor/CommandLineBox`, `editor/CommandLineEditHandlers` | `CmdlineEventTest`, `CmdlineStateTest`, `CandidateLineTest`, `CmdlineWatcherTest`, `NeovimSessionIntegrationTest` (real Neovim: typing, history, Tab, mappings, prompts, paste, search preview, `q:`, `:lcd`), `tests/swt` `CommandLineBoxTest` (fake: keys and pastes sent, views shown, focus, message line above), UI `CommandBoxEndToEndTest` (Paste and Ctrl keys through the workbench) |
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
| A block's lines as document characters | `util/BlockSelection` | `BlockSelectionTest`, `NeovimSessionIntegrationTest` (needs nvim) |
| Key-input pipeline: pending keys, insert replay, macro playback, host `:` on Enter | `core/KeyInput` (Neovim seen through `KeyInput.Neovim`; state through `StateSync`) | `KeyInputTest` (scripted fake Neovim: request order, suppressed events), `NeovimSessionIntegrationTest` (needs nvim) |
| Re-sync and who types the insert, after each key | `util/ModeTransition` | `ModeTransitionTest` |
| One buffer per document | `core/DocumentBuffers` | `NeovimBufferIntegrationTest` (needs nvim) |
| Status bar parts, messages and output, macro recording | `core/StatusTracker` | `StatusTrackerTest` |
| Message events from `redraw`; where each message goes | `util/MessageEvent`, `util/MessageRoute` | `MessageEventTest`, `MessageRouteTest`, `NeovimSessionIntegrationTest` (needs nvim) |
| Messages and errors in the editor, error flash | `editor/MessageLine` (colors: `util/OverlayColors`) | `tests/swt` `MessageLineTest`, `OverlayColorsTest`, UI `MessageLineEndToEndTest` |
| Ordered RPC thread | `core/InputThread` | `InputThreadTest` |
| Typed `:` commands and searches, `:normal!` | `core/CommandRunner` (Neovim seen through `CommandRunner.Neovim`) | `CommandRunnerTest` (fake: order of calls, timeout) |
| Mode, cursor and typing state; reading it from Neovim and publishing it | `core/StateSync` (Neovim seen through `StateSync.Neovim`, listeners through `StateSync.Listeners`) | `StateSyncTest` (fake: blocked gate, unattached buffer, visual selection, when Eclipse may type, suppressed events) |
| Session listeners (mode, buffer change, cursor, selection, IDE action) | `core/SessionEvents` (on `core/ListenerList`) | `SessionEventsTest`, `ListenerListTest` |
| Session facade | `core/NeovimSession` | `NeovimSessionIntegrationTest` (needs nvim) |
| Overlay colors | `util/OverlayColors` | `OverlayColorsTest` |
| Ctrl-key ids and defaults | `util/VimCtrlKeys` | `VimCtrlKeysTest`, `PluginXmlConsistencyTest` |
| Eclipse glue | `editor/*`, `prefs/*` | `tests/swt` for widgets (`OverlayFrameTest`, `BlockOverlayTest`, `WidgetOffsetsTest`, `OutputPopupTest`); UI test fragment `tests/...neovim4eclipse.tests` for the workbench (`VimKeyContextsTest`, `CtrlKeysPreferencePageTest`, ...) |
| Whole plug-in in a real editor | all of the above | `EditorEndToEndTest` (needs nvim and a display) |

When adding behavior, prefer putting the decision in a pure `util` class and leaving the Eclipse class
as thin glue; that is what makes it testable here.
