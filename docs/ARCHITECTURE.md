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
┌──────────────────────── nvim --embed (neovim-java) ─────────────────────┐
│  one buffer per document ◄── nvim_buf_set_lines / lines_event           │
│  ui_attach(TERMINAL) for input processing                               │
└─────────────────────────────────────────────────────────────────────────┘
```

## Key design decisions

### 1. Neovim owns the buffer (except while typing)

In normal, visual and operator-pending mode every key goes to Neovim and is swallowed; Neovim mutates
its buffer and Eclipse applies `nvim_buf_lines_event` as minimal edits (`LineRangeEdit`).

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

### 2. Ordered async input

`VerifyKeyListener` must return quickly. Keys are enqueued on a **single-thread executor** so sequences like `ciw` stay ordered without blocking SWT.

### 3. Visual selection

When mode is `v` / `V` / Ctrl-V, after each key the state snapshot reads `getpos('v')` and `getpos('.')`. Characterwise and linewise
selections are mapped onto `StyledText.setSelection`; a blockwise selection is painted as an overlay (see "Blockwise visual").

### 4. UI chrome

- **Mode status**: `ModeStatusContribution` on the editor status line.
- **Caret**: block vs thin via SWT `Caret` size (`CursorStyleController`).
- **Ex / search**: `:`, `/`, `?` open `CommandLineBox`, an inline `Text` child of the editor widget
  (see "Command line" below); submitted commands use `commandAsync` / `search`.

### 5. Auto-attach

`EditorAttachManager` + `EarlyStartup` register `IPartListener2` so opening/activating a text editor attaches Neovim. One controller is active at a time (switching editors detaches/reattaches).

### 6. One Neovim buffer per document

`EditorAttachManager` keeps a `DocumentBuffer` per open `IDocument` (two editors on one file share
it) and passes it to the controller, which calls `NeovimSession.showBuffer` on attach. Buffers are
listed, named after the file path, and `buftype=nofile` (Neovim never writes or swaps them, and no
filetype detection runs). Only the attached buffer's line events and cursor reports reach Eclipse.

A `BufEnter` autocmd reports buffer switches. Neovim's switch to another open document's buffer is
followed: its editor is activated and attached, keeping Neovim's cursor. A switch to any other
buffer is undone (`returnToAttachedBufferAsync`). When a document's last editor closes, its buffer
is wiped (`closeBufferAsync`).

## Ideas for future work

| Topic | Hint |
|-------|------|
| Content assist | Temporarily release keys or use Neovim LSP bridge |
| Highlights | `nvim_buf_get_extmarks` → Eclipse annotations |
| True block visual | Custom paint on `StyledText` or block selection API if available |
| Cmdline | Parse UI `cmdline_show` redraw events instead of the inline box |

## Threading rules

| Thread | Allowed |
|--------|---------|
| SWT UI | Widget updates, `VerifyKeyListener`, the command-line box, all `IDocument` edits |
| input executor | All neovim-java `await(...)` RPC |
| RPC listener | Dispatch to listeners; schedule UI work with `Display.asyncExec` |

Never call `await` on the UI thread for key handling.

## Dependency stack

- **neovim-java** (`neovim-api`, `core-rpc`, `reactive-core-rpc`, handlers; version in the root `pom.xml`)
- MessagePack + Jackson
- SLF4j simple (logging from the client library)

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
from the same single `STATE_SNAPSHOT` call, with three flags so that only enabled parts are evaluated:

- **statusline:** `nvim_eval_statusline(vim.o.statusline or a Vim-like default)`, so a user's `%!` expressions
  and plugins such as lualine are honored.
- **cmd:** `nvim_eval_statusline('%S')`. `%S` needs `showcmdloc=statusline` (set in `LuaScripts.SETUP`, Neovim
  0.9+). While Neovim is *blocked* waiting for a character no API call is possible, so the key just sent is
  appended to the last known text (`2` then `f` shows `2f`).
- **msg:** `v:statusmsg` / `v:errmsg`, *cleared after reading* so each message is seen once (an error wins). The
  session keeps the message until the next key.

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
  host commands are classified by `util/HostExCommand` and carried out by `editor/HostCommands`.
- **Output:** listing commands (`util/ListingCommand`) run through `nvim_exec2` with output capture and
  are not typed. Every other command is typed (`:silent cmd<CR>`, so macros and `@:` see it) between
  `redir => g:eclipse_neovim_output` and `redir END` (`LuaScripts.START/STOP_OUTPUT_CAPTURE`); a
  non-empty result that is not just the error message is shown in the `OutputPopup`. Full
  `ext_messages` support was considered and not chosen: it needs `ext_linegrid`, i.e. grid redraw
  traffic on every key, and a redraw-event parser.

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
- **Arguments vs prompts:** after `f F t T r @ m ' \` " q` the next key is an argument, so `KeyInterceptor`
  forwards it to Neovim instead of opening the `:` or `/` prompt (`NvimKeys.takesArgument`, plus Neovim's own
  `blocking` flag as reported by `nvim_get_mode`). `q` while recording stops it and takes no argument.

## Handing typing over to Eclipse

Entering insert mode is followed by a full buffer sync from Neovim (`KeyInput`), which the editor applies
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
   the insert. At that moment the text equals Neovim's buffer.
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
  is painted as an overlay by `editor/VisualSelectionView` (visible lines only).
- **Tab width:** `NeovimEditorController` sets Neovim's `tabstop` to the editor's tab width on attach, so
  Neovim's block commands and the overlay use the same columns.
- **Keys:** the workbench dispatches key bindings *before* widget key listeners, so `Ctrl-V` etc. would never
  reach `KeyInterceptor`. A context (`io.github.jm6cnddxbwftaks.eclipseneovim.context.normal`) is activated
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
- **Folding:** the widget of an editor with folding (the Java editor folds imports) leaves collapsed
  lines out, so a `StyledText` offset is not a document offset. `editor/WidgetOffsets` converts every
  caret and selection offset (`ITextViewerExtension5`) and unfolds code Neovim's cursor moves into.

## Where logic lives (and how it is tested)

| Concern | Class | Tests |
|---------|-------|-------|
| SWT event to Neovim key | `util/KeyMapping` | `KeyMappingTest` |
| Mode strings | `util/VimModes` | `VimModesTest` |
| Host `:` commands (`:w`, `:qa`, `:e`, `:tabn`, ...) | `util/HostExCommand`, `editor/HostCommands` | `HostExCommandTest`, UI `HostCommandsTest` |
| Tab arithmetic, `:e` path lookup | `util/TabNavigation`, `util/EditPath` | `TabNavigationTest`, `EditPathTest` |
| `gd`/`K`/`gt`/`gT` handed to Eclipse | `core/IdeAction`, `editor/IdeActions` | `NeovimSessionIntegrationTest`, `EditorEndToEndTest` |
| Line/range/diff conversion | `util/LineTextUtil`, `LineRangeEdit`, `TextDiff` | matching `*Test` classes |
| RPC notification parsing | `core/NotificationBridge` | `NotificationBridgeTest` |
| Process and RPC stack | `core/NeovimConnection` | `NeovimSessionIntegrationTest` (needs nvim) |
| Per-key state snapshot | `core/StateSnapshot` | `StateSnapshotTest` |
| Key-input pipeline | `core/KeyInput` | `NeovimSessionIntegrationTest` (needs nvim) |
| One buffer per document | `core/DocumentBuffers` | `NeovimBufferIntegrationTest` (needs nvim) |
| Status bar parts, macro recording | `core/StatusTracker` | `StatusTrackerTest` |
| Ordered RPC thread | `core/InputThread` | `InputThreadTest` |
| Session facade, state publishing | `core/NeovimSession` | `NeovimSessionIntegrationTest` (needs nvim) |
| Overlay colors | `util/OverlayColors` | `OverlayColorsTest` |
| Ctrl-key ids and defaults | `util/VimCtrlKeys` | `VimCtrlKeysTest`, `PluginXmlConsistencyTest` |
| Eclipse glue | `editor/*`, `prefs/*` | UI test fragment `tests/...eclipseneovim.tests` (`OverlayFrameTest`, `VisualSelectionViewTest`, `VimKeyContextsTest`, `CtrlKeysPreferencePageTest`, ...) |
| Whole plug-in in a real editor | all of the above | `EditorEndToEndTest` (needs nvim and a display) |

When adding behavior, prefer putting the decision in a pure `util` class and leaving the Eclipse class
as thin glue; that is what makes it testable here.
