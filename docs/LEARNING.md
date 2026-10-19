# Learning guide: Eclipse plug-ins and the Neovim API

This guide explains the ideas that the code depends on, in the order you need them. It is meant to be
read next to the source. [ARCHITECTURE.md](ARCHITECTURE.md) describes *what* the plug-in does and
why; this file explains the *Eclipse and Neovim machinery* it is built from.

Suggested reading order of the code, once you have read parts 1 and 2:

1. `META-INF/MANIFEST.MF`, `plugin.xml`, `build.properties` (the plug-in's "shape")
2. `Activator`, `EarlyStartup` (start-up)
3. `editor/EditorAttachManager` (which editor gets Neovim)
4. `editor/NeovimEditorController` (one attached editor), `editor/KeyInterceptor` and `core/KeyRouter` (keys)
5. `core/NeovimSession`, `core/LuaScripts`, `core/NotificationBridge` (talking to Neovim); the session
   is a facade that hands most work to smaller classes (`KeyInput`, `StateSync`, `CommandRunner`, listed
   in `core/package-info.java`), each of which is easier to read on its own
6. `sync/BufferSynchronizer` and the `util` classes it uses (keeping text in step)

---

## Part 1: Eclipse plug-in fundamentals

### 1.1 A plug-in is an OSGi bundle

Eclipse is built on [OSGi](https://www.osgi.org/), a module system for Java. Every plug-in is a
*bundle*: a jar (or folder) with a `META-INF/MANIFEST.MF` that declares its name, version and
dependencies. Each bundle gets its own class loader and sees only the packages it asks for.

The headers this plug-in uses:

| Header | Meaning here |
|--------|--------------|
| `Bundle-SymbolicName: ...eclipseneovim;singleton:=true` | The unique id. `singleton` is required for bundles that contribute to `plugin.xml` extension points. |
| `Bundle-Version: 0.1.0.qualifier` | `qualifier` is replaced by a build timestamp, so every build is a newer version. |
| `Bundle-Activator` | Class whose `start`/`stop` OSGi calls (`Activator`). |
| `Bundle-ActivationPolicy: lazy` | The bundle starts only when one of its classes is first loaded. |
| `Require-Bundle` | Other bundles whose exported packages this one uses (`org.eclipse.ui`, `org.eclipse.jface.text`, ...). |
| `Bundle-ClassPath: ., lib/neovim-api.jar, ...` | Jars *inside* the bundle that join its class path. This is how the plug-in embeds neovim-java and its dependencies. |
| `Bundle-RequiredExecutionEnvironment: JavaSE-21` | The minimum Java version. |

Because the embedded jars are listed by name in `Bundle-ClassPath`, the build copies them into `lib/`
without version numbers (see the plug-in's `pom.xml`). `BundleClassPathConsistencyTest` checks that the
manifest and the POM name the same jars.

`build.properties` says which files go into the built bundle (`bin.includes`). A file missing there
works when you launch from the IDE but is absent from the installed plug-in.

### 1.2 `plugin.xml`: extension points

Eclipse is extended declaratively. A plug-in that offers a hook defines an *extension point*; a
plug-in that uses the hook adds an *extension* to its `plugin.xml`. Eclipse reads these files
without loading any code. That is why menus and key bindings exist before the bundle starts.

Extension points used here, all defined by the Eclipse Platform:

| Extension point | What this plug-in contributes |
|-----------------|-------------------------------|
| `org.eclipse.ui.commands` | Abstract actions: "attach", "toggle mode", "send Ctrl-F", ... (an id and a name, no code). |
| `org.eclipse.ui.handlers` | The Java class that runs when a command executes (`AttachHandler`, `SendKeyHandler`, ...). |
| `org.eclipse.ui.contexts` | Named situations in which key bindings apply (`context.normal`, `context.ctrl.f`, ...). |
| `org.eclipse.ui.bindings` | Key sequences that trigger a command while a context is active. |
| `org.eclipse.ui.menus` | The *Neovim* menu and the *NVIM* toolbar button. |
| `org.eclipse.ui.startup` | `EarlyStartup`, run after the workbench starts. |
| `org.eclipse.ui.preferencePages` | The *Neovim* and *Ctrl Keys* pages. |
| `org.eclipse.core.runtime.preferences` | `PreferenceInitializer`, which supplies default values. |
| `org.eclipse.core.expressions.propertyTesters` | `NeovimPropertyTester`, which lets `plugin.xml` hide the toolbar button based on a preference. |

The split into command, handler, binding and context is the most important idea in this list:

```text
key binding:  Ctrl+F  ──(while context ...ctrl.f is active)──►  command ...commands.ctrl.f
handler:      command ...commands.ctrl.f  ──►  SendKeyHandler.execute()  ──►  Neovim gets <C-f>
```

A command is only a name. Menus, toolbar buttons and key bindings point at the command, and the
handler supplies the behavior. Several handlers can compete for one command; Eclipse picks the
active one.

### 1.3 The workbench: windows, pages, parts

The Eclipse UI is called the *workbench*. It has one or more *windows*; each window has a *page*;
the page holds *parts*, which are either *views* (Package Explorer, Console) or *editors*.

- `PlatformUI.getWorkbench()` is the entry point.
- `IPartListener2` reports parts opening, activating and closing. `EditorAttachManager` uses it to
  attach Neovim to whichever text editor becomes active.
- `IWindowListener` reports new windows, so the part listener can be added to each one.
- `IStartup` (`EarlyStartup`) runs once the workbench is up. The `Activator` alone would not do: with
  `lazy` activation the bundle might never start until something loads one of its classes.

Services are looked up from the workbench by interface: `getService(IContextService.class)`,
`getService(IHandlerService.class)`, and so on.

### 1.4 Text editors: four layers

A text editor is several objects stacked on each other. Knowing which layer to talk to is most of
the work in this plug-in.

```text
ITextEditor          the workbench part (tab, dirty flag, save)         org.eclipse.ui.texteditor
 ├─ IDocumentProvider  connects the editor input (a file) to a document
 │   └─ IDocument      the text model: get/replace, line info, listeners  org.eclipse.jface.text
 └─ ITextViewer        the "view" layer: selection, undo, key listeners    org.eclipse.jface.text
     └─ StyledText     the SWT widget that paints text and takes keys      org.eclipse.swt.custom
```

- Edits go to the **document**: `IDocument.replace(offset, length, text)`. The viewer and widget
  update themselves, and undo, dirty state and annotations keep working. `BufferSynchronizer` and
  `util/LineRangeEdit` produce such replacements.
- Keys are intercepted at the **viewer**: `ITextViewerExtension.prependVerifyKeyListener`. Being
  *prepended* puts the plug-in's listener in front of the editor's own listeners, so content assist
  never sees keys Neovim consumes.
- Caret, selection and painting use the **widget** (`StyledText`): `setCaretOffset`, `setSelection`,
  paint listeners for the blockwise-visual overlay.

How do you get from the editor to the viewer? Through *adapters*. `editor.getAdapter(ITextViewer.class)`
asks the editor for an object of that type, and the editor returns one if it can. Adapters are how
Eclipse lets objects offer other interfaces without hard dependencies (`NeovimEditorController.viewerExtensionOf`).

Multi-page editors (the POM editor, PDE's `MANIFEST.MF` editor) are not `ITextEditor`s. Their source
tab is a nested text editor, which `EditorAttachManager` digs out.

### 1.5 SWT: one UI thread

SWT is Eclipse's widget toolkit. It wraps the native widgets of each platform (GTK, Cocoa, Win32),
which is why the same code can look different per platform (see `editor/OverlayFrame`).

The rules this code follows everywhere:

- **Widgets may be touched only on the UI thread.** Other threads hand work over with
  `Display.asyncExec(runnable)` (queue it, return at once) or `syncExec` (wait for it; avoided here).
- **The UI thread must never block.** A slow call there freezes the whole IDE. That is why every
  Neovim request runs on the session's own input thread (part 2.6).
- **Widgets you create, you dispose.** A disposed widget throws if used; check `isDisposed()`.
  Since SWT 4.17, `Color` objects built from RGB values no longer need disposing.
- **Listeners**: `VerifyKeyListener` can veto a key (`event.doit = false`) before the widget acts;
  `KeyListener` only observes; `FocusListener`, `PaintListener`, `ControlListener` (resize) are used by
  the overlays.

### 1.6 How a key press travels

```text
OS key event
  └─► Display filter: the workbench's key-binding dispatcher
        matches a binding in an active context?  ──yes──►  run the command's handler; key consumed
        no
  └─► the focused widget's VerifyKeyListeners (KeyInterceptor first)  ──►  may set doit = false
  └─► StyledText inserts the character / moves the caret
```

The dispatcher runs **before** the editor sees the key. That is why `Ctrl+V` (Paste) never reaches
`KeyInterceptor` unless the plug-in claims it with its own binding. When two bindings use the same
key, the one in the more specific (deeper) context wins; see `util/VimCtrlKeys` for the context tree
and `editor/VimKeyContexts` for how contexts are switched on and off.

On macOS, `M1` in `plugin.xml` means Command and `M4` means Control. On Windows and Linux `M1` is Ctrl.

### 1.7 Preferences

- Values live in an `IPreferenceStore` (`Activator.getDefault().getPreferenceStore()`), saved per
  workspace.
- Defaults are set by an `AbstractPreferenceInitializer` in the `DefaultScope`. A stored value that
  equals its default is not written to disk.
- Pages are `PreferencePage` subclasses. `FieldEditorPreferencePage` builds a form from field
  editors (`NeovimPreferencePage`); a hand-written page builds its own widgets (`CtrlKeysPreferencePage`).
- Code that must react to a change adds an `IPropertyChangeListener` to the store (`Activator.start`).

### 1.8 Building and installing: Tycho, features, p2

Plain Maven does not understand bundles. [Tycho](https://github.com/eclipse-tycho/tycho) is a set of
Maven plug-ins that read `MANIFEST.MF`, `feature.xml` and `category.xml`, and resolve dependencies
from an Eclipse *p2 repository* (the `eclipse-repo.url` in the root `pom.xml`) instead of Maven Central.

| Module | Packaging | Produces |
|--------|-----------|----------|
| `plugins/...eclipseneovim` | `eclipse-plugin` | the bundle jar |
| `features/...feature` | `eclipse-feature` | a *feature*: the installable unit users see, grouping bundles |
| `releng/...updatesite` | `eclipse-repository` | a *p2 repository* (an update site) and its ZIP |
| `tests/unit` | `jar` | plain JUnit tests of the headless code |

p2 is Eclipse's installer. *Help > Install New Software* reads a p2 repository. p2 names a
feature's installable unit `<feature id>.feature.group`, which is why the installer shows
`...eclipseneovim.feature.feature.group`.

---

## Part 2: Neovim API fundamentals

### 2.1 `nvim --embed`

`nvim --embed` starts Neovim without a terminal user interface. It reads requests on standard input
and writes replies on standard output. The program that started it (here, Eclipse) becomes its UI
and its only client. `util/NvimExecutable.embedCommand` builds the command line;
`NeovimSession.start` launches it with `ProcessBuilder`.

### 2.2 msgpack-rpc

The bytes on that pipe are [MessagePack](https://msgpack.org/), a binary form of JSON. Neovim speaks
[msgpack-rpc](https://github.com/msgpack-rpc/msgpack-rpc/blob/master/spec.md) over it: three message
shapes, each a MessagePack array.

```text
request       [0, msgid, "nvim_input", ["dd"]]          client → Neovim, expects a response
response      [1, msgid, error, result]                  Neovim → client, matches msgid
notification  [2, "nvim_buf_lines_event", [args...]]     either direction, no response
```

Requests are answered in order of processing; the `msgid` pairs each answer with its question.
Notifications are fire-and-forget: Neovim uses them for events, and Lua code can send its own with
`vim.rpcnotify(0, name, ...)` (channel 0 means "every connected client").

Requests go the other way too. `vim.rpcrequest(channel, name, ...)` sends one to a client and
**Neovim waits** until the client answers; it needs a real channel number, not 0. The plug-in uses
this once: when Neovim pastes from the system clipboard (`"+p`), it asks Eclipse for the text
(`eclipse_clipboard_paste`, see `core/ClipboardRegisters`). Eclipse learns its own channel number by
calling `nvim_get_chan_info(0)` itself (0 is "the caller" in a direct API call) and passes it to the
setup Lua: inside Lua run by `nvim_exec_lua`, `nvim_get_chan_info(0)` does not know who called.

In Neovim, `:help api` lists every function and `:help rpc` describes the protocol. `nvim --api-info`
dumps the API as MessagePack.

### 2.3 neovim-java

This plug-in does not encode MessagePack itself. [neovim-java](https://github.com/esensar/neovim-java)
provides layers that `NeovimSession.start` stacks up:

```text
ProcessRpcConnection    the process's stdin/stdout (wrapped in core/QuittingConnection, see below)
RpcClient               encodes/decodes messages, matches responses to requests (Jackson + msgpack)
ReactiveRpcStreamer     turns each request into a CompletableFuture
NeovimStreamApi         typed methods: api.input("dd"), api.getMode(), buffer.setLines(...)
NeovimHandlerManager    routes notifications to @NeovimNotificationHandler methods (NotificationBridge)
```

neovim-java also has a `@NeovimRequestHandler` annotation for answering Neovim's requests, but in
version 0.5.0 it never fires: the manager stores those handlers in one map and looks them up in
another, so every request gets "No handler found". `NeovimConnection.setRequestHandler` answers
requests itself, straight from the `RpcClient`, and the manager is attached for notifications only.
A library's documented feature can be broken; a small integration test against the real program
(here `ClipboardIntegrationTest`) is what finds it.

Every API call returns a `CompletableFuture`. `Rpc.await` waits for it with a timeout (and
`Rpc.errorMessage` turns a failed call into Neovim's own `E…:` text).
Waiting is the blocking step that must never happen on the SWT thread.

msgpack-rpc has two kinds of message from client to server: a *request* carries an id and gets a
*response* with the same id; a *notification* has no id and gets no answer. Closing the session shows
why the difference matters: Neovim exits while it runs `:qa!`, so a `:qa!` sent as a request is never
answered, and a thread would wait for the answer until it times out or is interrupted (which
neovim-java prints as an error). `NeovimConnection.requestQuit` therefore sends `nvim_command("qa!")`
as a notification and waits for the *process* to exit instead. The exit then ends the stream that
neovim-java's reader thread reads, which it would also report as an error; `core/QuittingConnection`
wraps the stream so that, once the session is closing on purpose, that end is expected and silent.

### 2.4 Attaching as a UI

A Neovim instance with no UI attached does not process typed input reliably. `NeovimSession.start`
therefore calls `nvim_ui_attach(120, 40, ...)` even though Eclipse draws nothing that Neovim sends.
Neovim then sends `redraw` notifications describing its screen; this plug-in ignores them and reads
the state it needs directly instead. A UI that wanted Neovim's own command line and messages would
draw them from those events.

`nvim_set_client_info` tells Neovim who is connected (`Eclipse-Neovim` and the bundle version).
`:checkhealth` and `nvim_list_chans()` show it.

### 2.5 Sending keys: `nvim_input`

`nvim_input(keys)` appends keys to Neovim's typeahead, exactly as if typed. It returns at once,
before the keys are processed. Keys use Vim's notation: `<C-v>`, `<Esc>`, `<CR>`, and `<lt>` for a
literal `<`. `util/KeyMapping` converts SWT events to that notation.

Because `nvim_input` does not wait, the next request in the queue (for example "where is the cursor
now?") is what tells you the keys have been handled. Neovim answers requests in order.

### 2.6 Blocking: the one rule that must not be broken

After keys such as `f`, `r`, `m`, `"` or `@`, Neovim waits for one more character. While it waits it
answers only `nvim_get_mode` and `nvim_input`; any other request is held until the character arrives.
If the plug-in sent such a request and waited, it would wait for a key that is queued behind its own
call, and stall until the timeout.

So after every key the session first calls `nvim_get_mode`. If the answer says `blocking: true`, it
stops there: `StateSync.syncModeAndCursor` asks `isBlocked()` before it requests anything else (see
"Pending commands" in ARCHITECTURE.md). Example: after `f` the answer is `{mode = "n", blocking = true}`,
so no cursor is read; once the target character arrives, the next read publishes the new state. (After
`d` Neovim is in operator-pending mode, `{mode = "no", blocking = false}`: it still answers requests.) All requests also run on
one thread (`eclipse-neovim-input`), which keeps keys in order and keeps blocking off the UI thread.

A *hit-enter prompt* blocks the same way. `:bufdo %s/a/b/g` typed as keys prints one "N substitutions"
message per buffer, more than fits, so Neovim asks "Press ENTER" and answers nothing else until a key
comes. That is why the `:` box runs commands through `core/CommandRunner`, which types them with `:silent`
and collects the output itself (and why the UI tests use `session.commandAsync`, not typed keys).

### 2.7 Buffers and line events

- `nvim_create_buf(listed, scratch)` makes a buffer; `nvim_set_current_buf` shows it in the window.
  This plug-in gives every Eclipse document its own buffer (`DocumentBuffer`).
- `nvim_buf_set_lines(buf, start, end, strict, lines)` replaces lines `[start, end)`. Indexes are
  0-based and the end is exclusive; `-1` means "the end".
- `nvim_buf_attach(buf, ...)` subscribes to changes. Neovim then sends
  `nvim_buf_lines_event [buf, changedtick, firstline, lastline, linedata, more]` after every edit:
  "lines `[firstline, lastline)` now read `linedata`". `NotificationBridge` decodes it and
  `util/LineRangeEdit` turns it into one `IDocument.replace`.

Every document buffer is attached this way, not only the one in the window, so a change to any of
them arrives: `:bufdo` edits each buffer in turn. `NeovimSession` sends the attached buffer's events to
the editor and the others to their own document (`sync/UnattachedDocumentSync`). When Eclipse writes a
buffer itself, Neovim reports that write too, as a line event; since Neovim sends the event before the
reply to `nvim_buf_set_lines`, the session drops the events that arrive while it waits for that reply
(`setDocumentBufferLines`), instead of applying its own change a second time.

Neovim has no notion of a final newline: a buffer is a list of lines. `util/LineTextUtil` explains how
the plug-in keeps an Eclipse file's final newline.

### 2.8 Positions: bytes, not characters

`nvim_win_get_cursor` returns a 1-based line and a 0-based **byte** column in UTF-8. Eclipse offsets
count UTF-16 characters. On a line with `é` (2 bytes in UTF-8, 1 char in UTF-16) the two disagree.
Every column crossing the boundary goes through `util/Utf8Columns`.

### 2.9 Running Lua inside Neovim

`nvim_exec_lua(code, args)` runs a Lua chunk and returns its result; `...` holds the arguments.
`core/LuaScripts` keeps every chunk the plug-in sends, with comments. Two uses:

- **Setup**: options and *autocommands* (`ModeChanged`, `CursorMoved`, `BufEnter`, `TextYankPost`)
  whose callbacks call `vim.rpcnotify(0, 'eclipse_mode', ...)`. Those notifications arrive at the
  matching `@NeovimNotificationHandler` method in `NotificationBridge`. Setup also makes Eclipse
  Neovim's clipboard provider (`g:clipboard`, see `:help clipboard`), whose functions are Lua
  closures that notify or ask Eclipse.
- **Snapshots**: `STATE_SNAPSHOT` returns cursor, visual selection, mode, recording register and
  status texts in one round trip, instead of many separate requests per key.

`nvim_exec2(cmd, { output = true })` runs an Ex command and returns what it printed. The output
popup for `:ls` and similar uses it (`LuaScripts.CAPTURE_OUTPUT`).

---

## Part 3: Following the code

### 3.1 Start-up

1. Eclipse starts the workbench and calls `EarlyStartup.earlyStartup()`.
2. That loads a class from the bundle, so OSGi starts it: `Activator.start` installs the preference
   listener; `EditorAttachManager.installWorkbenchListeners` adds the part and window listeners.
3. A text editor becomes active, so `IPartListener2.partActivated` fires and `EditorAttachManager`
   attaches. The first attach calls `Activator.getOrCreateSession`, which runs `NeovimSession.start`:
   process, RPC stack, `nvim_set_client_info`, `nvim_ui_attach`, home buffer, notification handlers,
   `LuaScripts.SETUP`.
4. `NeovimEditorController.attach` prepends the key interceptor, registers listeners, shows the
   document's buffer (`showBuffer`) and pushes the document text into it.

### 3.2 One normal-mode key: `x`

1. `KeyInterceptor.verifyKey` (UI thread) maps the event to `"x"` and asks `KeyRouter.route`, which
   calls `session.inputAsync("x")` (it only queues work) and answers "consumed"; the interceptor then
   sets `event.doit = false` so the widget does nothing.
2. On the input thread (`KeyInput.input`): `nvim_input("x")`, then `nvim_get_mode`, then (if not
   blocking) the `STATE_SNAPSHOT` Lua call, made by `StateSync.publishState`.
3. Neovim deletes the character and sends `nvim_buf_lines_event` for that line. It travels
   `NotificationBridge` (decodes it) → `NeovimSession.EventSink` (drops it unless the buffer is the
   attached one) → `StateSync.dispatchBufferChange` (drops it while buffer events are suppressed) →
   `SessionEvents` (the listener lists) → `BufferSynchronizer`, which hops to the UI thread and applies
   the change to the `IDocument`.
4. The snapshot's cursor takes the same route from `StateSync` through `SessionEvents` to
   `NeovimEditorController`, which converts the byte column and moves the caret. The mode reaches `ModeStatusContribution` (status bar) and `CursorStyleController` (block
   caret).

### 3.3 Insert mode

`i` goes to Neovim like any key; its `ModeChanged` notification switches the plug-in to "typing".
From then on `KeyInterceptor` lets Eclipse insert characters itself, so content assist and auto
indent work. On `Esc`, the typed text is replayed into Neovim and the document is pushed, so `.`
can repeat it (see "Dot-repeat" in ARCHITECTURE.md).

### 3.4 Ports and fakes: testing without Eclipse or Neovim

Much of the plug-in's logic is a decision: "should this key go to Neovim?", "should this command's
output be captured?", "may Eclipse type yet?". Tested through the real thing, each decision needs a
running Eclipse (UI tests: a workbench that takes seconds to start, and a display) or a real `nvim`
(integration tests, a few seconds, but `nvim` must be installed). So the code that decides is kept apart from the code that talks to Eclipse or
Neovim, and the two meet at a *port*: a small interface listing only the calls the decision needs.

In production the port is implemented by a thin *adapter* over the real API; in a test by a *fake*,
a few lines that record calls and return canned answers. Example, from `core/CommandRunner`:

```java
// The port: what running a ':' command needs from Neovim, nothing more.
interface Neovim {
    void type(String keys) throws Exception;   // nvim_input
    boolean isBlocked();                       // nvim_get_mode().blocking
    void startCapture() throws Exception;      // :redir => ...
    CompletableFuture<Object> stopCapture();   // :redir END, answered when the command ends
    ...
}

// The adapter, inside NeovimSession: one line per call, no decisions.
public void type(String keys) throws Exception {
    await(api.input(keys));
}

// The fake, in CommandRunnerTest: records the call, so a test can check the order.
public void type(String keys) {
    calls.add("type " + keys);
}
```

A test then reads as a sentence about behavior, and runs in milliseconds:

```java
runner.run("echo 'hello'", outputs::add);
assertEquals(List.of("startCapture", "type :silent echo 'hello'<CR>", "stopCapture",
        "syncBuffer", "syncModeAndCursor"), nvim.calls);
```

Why an interface per user rather than one big "Neovim" interface: each port shows exactly what a class
depends on, and a fake only has to implement those few methods. The ports in this code base:

| Port | Used by | Implemented by |
|------|---------|----------------|
| `KeyRouter.Neovim` | `core/KeyRouter` | `NeovimSession` |
| `BufferPort` | `sync/BufferSynchronizer` | `NeovimSession` |
| `DocumentBufferPort` | `sync/UnattachedDocumentSync` | `NeovimSession` |
| `CursorPort` | `editor/CaretSync` | `NeovimSession` |
| `CommandRunner.Neovim` | `core/CommandRunner` | adapter inside `NeovimSession` |
| `KeyInput.Neovim` | `core/KeyInput` | adapter inside `NeovimSession` (`KeyInputTest` scripts it: "after `i` the mode is `i`") |
| `StateSync.Neovim`, `StateSync.Listeners` | `core/StateSync` | adapter inside `NeovimSession`; `SessionEvents` |
| `UiExecutor` | `BufferSynchronizer`, `CaretSync` | `editor/SwtUiExecutor` (`UiExecutor.IMMEDIATE` in tests) |
| `EditorCaret` | `CaretSync`, `VisualSelectionView` | `editor/StyledTextCaret` (`FakeCaret` in tests) |
| `HostCommandRunner.Ide` | `util/HostCommandRunner` | a record inside `editor/HostCommands` |
| `DocumentPush.Neovim`, `DocumentPush.Mirror` | `sync/DocumentPush` | a record in `NeovimEditorController`; `BufferSynchronizer` |
| `BlockHighlight` | `VisualSelectionView` | `editor/BlockOverlay` (a fake that keeps the ranges in tests) |
| `CommandLineBox.Commands` | `editor/CommandLineBox` | a record in `NeovimEditorController` (a fake that records "`:ls` captured, `:w` to the IDE" in tests) |
| `ClipboardPort` | `core/ClipboardRegisters` | `editor/ClipboardBridge` (`ClipboardPort.NONE`, or a fake that keeps one text per selection, in tests) |

A port is not the only way to keep Eclipse out of a class. When the class only *stores* Eclipse
objects and compares them, never calls them, type parameters are enough. `core/EditorBuffers<D, E>`
decides which editors share a document's buffer, which editor Eclipse activates when Neovim switches
buffers itself (the first one opened, so the choice is repeatable), and when a buffer is wiped (when
its last editor closes). In the plug-in it is an `EditorBuffers<IDocument, IEditorPart>`; in
`EditorBuffersTest` it is an `EditorBuffers<String, String>`:

```java
EditorBuffers<String, String> buffers = new EditorBuffers<>();
DocumentBuffer a = buffers.add("A.java", "editor 1", () -> "/src/A.java");
buffers.add("A.java", "editor 2", () -> "/src/A.java");      // same buffer, shared undo history
assertEquals(List.of(), buffers.remove("editor 1"));          // editor 2 still shows A.java
assertEquals(List.of(a), buffers.remove("editor 2"));         // last one closed: wipe it
```

When *not* to add one: glue code that only passes values through (an adapter, `KeyInterceptor`) has no
decision to test, and a port would only add a layer to read through. The integration and UI tests
still matter: a fake says what the code *asks for*, and only a real Neovim or Eclipse shows that
asking for it works. See CONTRIBUTING.md, "Test helpers and fakes", for where the fakes live.

---

## Part 4: Recipes

**Add a preference.** Add a key and default to `PreferenceConstants`; set the default in
`PreferenceInitializer`; add a field to a preference page; read it with
`Activator.getDefault().getPreferenceStore()`; if it must apply at once, handle it in the
`Activator`'s property-change listener.

**Get a new event from Neovim.** Add an autocommand to `LuaScripts.SETUP` that calls
`vim.rpcnotify(0, 'eclipse_<name>', ...)`; add a `@NeovimNotificationHandler("eclipse_<name>")` method to
`NotificationBridge` and a method to its `Sink`; implement it in `NeovimSession.EventSink`. If the
event changes state the session keeps (like mode or cursor), record it in `StateSync` and give it a
listener list in `SessionEvents`.

**Answer a question from Neovim.** In Lua, call `vim.rpcrequest(chan, 'eclipse_<name>', ...)`, where
`chan` is the channel number `LuaScripts.SETUP` receives as its argument (Neovim waits for the answer,
so keep the Java side quick and bounded); add a case to `NotificationBridge.onRequest` and a method to
its `Sink`; implement it in `NeovimSession.EventSink`. The value returned is the answer; Lua sees Java
lists as tables. `eclipse_clipboard_paste` is the example.

**Ask Neovim for more state per key.** Extend `LuaScripts.STATE_SNAPSHOT` (append at the end so the
existing indexes stay valid), add a reader for the new element to `StateSnapshot` (and a case to
`StateSnapshotTest`), and use it in `StateSync.publishState`.

**Handle an Ex command in Eclipse instead of Neovim.** Classify it in `util/HostExCommand` (unit-test
it); say what it does in `util/HostCommandRunner`, adding a method to its `Ide` interface if the IDE
must do something new (unit-test that in `HostCommandRunnerTest` with the fake `Ide`); then implement
the method in `editor/HostCommands` with Eclipse's API (`editor/EclipseFileCommands`, `EditorTabs`).

**Put a decision where it can be tested.** If logic does not need SWT or Eclipse, put it in `util`
(or `core` for Neovim logic) and test it in `tests/unit`. That module compiles `util` and `core`
without Eclipse jars, so an `org.eclipse` import there fails the build. If the logic needs to call
Eclipse or Neovim, give it a port (section 3.4) and test it with a fake.

---

## Part 5: Debugging

- **Launch a runtime Eclipse** from the plug-in project (*Run As > Eclipse Application*, see
  [HOWTO_ECLIPSE.md](HOWTO_ECLIPSE.md)). Breakpoints work in the plug-in code.
- **Logs**: the plug-in logs with `java.util.logging`, which writes to the console of the Eclipse
  process (the launching Eclipse's Console view, or the terminal Eclipse was started from).
  Warnings and errors also go to the Error Log view (*Window > Show View > Error Log*), through
  `ErrorLogHandler`. `KeyContextDiagnostics` reports which command the workbench resolves for
  `Ctrl+V`; the README lists what its messages mean.
- **Key bindings**: *Window > Preferences > General > Keys* shows each binding with its context
  ("When"). The *Ctrl Keys* page decides which of this plug-in's letter contexts are active.
- **Inside Neovim**: `:messages`, `:ls`, `:set option?` all work through the `:` box. `vim.g.eclipse`
  is `1` inside the embedded Neovim, so your `init.lua` can tell where it runs.
- **Neovim side**: start Eclipse with `NVIM_LOG_FILE=/tmp/nvim.log` set to collect Neovim's own log.
- **Headless reproduction**: the integration tests (`NeovimSessionIntegrationTest`) drive a real
  `nvim` through `NeovimSession` without Eclipse, which is the quickest way to study an RPC sequence.

---

## Further reading

- Eclipse: *Help > Help Contents > Platform Plug-in Developer Guide*; the
  [Eclipse Platform documentation](https://help.eclipse.org/latest/); Lars Vogel's
  [Eclipse tutorials](https://www.vogella.com/tutorials/eclipse.html) (commands, handlers, SWT, OSGi).
- Neovim: `:help api`, `:help rpc`, `:help ui`, `:help lua-guide`, `:help autocmd`.
- [neovim-java](https://github.com/esensar/neovim-java) source, for the RPC layers.
