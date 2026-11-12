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
| `Bundle-SymbolicName: ...neovim4eclipse;singleton:=true` | The unique id. `singleton` is required for bundles that contribute to `plugin.xml` extension points. |
| `Bundle-Version: 0.1.0.qualifier` | `qualifier` is replaced by a build timestamp, so every build is a newer version. |
| `Bundle-Activator` | Class whose `start`/`stop` OSGi calls (`Activator`). |
| `Bundle-ActivationPolicy: lazy` | The bundle starts only when one of its classes is first loaded. |
| `Require-Bundle` | Other bundles whose exported packages this one uses (`org.eclipse.ui`, `org.eclipse.jface.text`, ...). |
| `Bundle-ClassPath: ., lib/msgpack-core.jar` | Jars *inside* the bundle that join its class path. This is how the plug-in embeds msgpack-core, the one library under its RPC client. |
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
| `org.eclipse.ui.menus` | The *Neovim* menu and the Neovim toolbar button (its icon, `icons/n4e-mode-on.png`, is the logo). |
| `org.eclipse.ui.startup` | `EarlyStartup`, run after the workbench starts. |
| `org.eclipse.ui.preferencePages` | The *Neovim* and *Ctrl Keys* pages. |
| `org.eclipse.core.runtime.preferences` | `PreferenceInitializer`, which supplies default values. |
| `org.eclipse.core.expressions.propertyTesters` | `NeovimPropertyTester`, which lets `plugin.xml` hide the toolbar button and the *Neovim* menu based on preferences. |

The split into command, handler, binding and context is the most important idea in this list:

```text
key binding:  Ctrl+F  ──(while context ...ctrl.f is active)──►  command ...commands.ctrl.f
handler:      command ...commands.ctrl.f  ──►  SendKeyHandler.execute()  ──►  Neovim gets <C-f>
```

A command is only a name. Menus, toolbar buttons and key bindings point at the command, and the
handler supplies the behavior. Several handlers can compete for one command; Eclipse picks the
active one whose *activation expression* depends on the most specific variable (the focus control
beats the active part, which beats the active window; see `ISources`). Example: Paste
(`org.eclipse.ui.edit.paste`, Ctrl+V) normally runs the editor's handler, which pastes into the
document. While the `:` box is open, `editor/CommandLineEditHandlers` activates a handler of its own
whose expression claims to depend on the focus control, so Paste pastes into the box instead.

A handler can also drive how its menu entries and toolbar buttons look. The toggle button is a
`style="toggle"` item: Eclipse draws it pressed while it is *checked*. `ToggleNeovimModeHandler`
implements `IElementUpdater`, whose `updateElement(UIElement, ...)` Eclipse calls for every item of
the command when someone calls `ICommandService.refreshElements(commandId, null)`; it sets
`setChecked`, `setTooltip` and, for the toolbar button only, `setIcon` there (a grey logo in
Eclipse mode, since macOS barely shows a pressed button). The `parameters` it gets are the item's
command parameters: the button passes `showsIcon=true` in `plugin.xml`, the menu entry nothing,
which is how one handler tells the two apart. Its *enablement* (`setBaseEnabled(false)`) greys out every item
and disables the key binding at once. Eclipse never polls either, so `ToggleButtonRefresh` asks for
both whenever Neovim attaches, detaches or changes mode:

```text
controller.setNeovimModeActive(false)
  └─ ToggleButtonRefresh.request()  ──asyncExec──►  handler.setBaseEnabled(attached)   (greyed out?)
                                                    send REQUEST_ENABLEMENT_UPDATE_TOPIC
                                                      └─ (100 ms later) toolbar asks the handler again
                                                    refreshElements(toggleMode)
                                                      └─ handler.updateElement(button) (pressed? tooltip)
```

A catch in the middle line: an Eclipse 4 toolbar item keeps its own copy of "enabled". The
handler's change makes the button redraw from that copy, and the copy is refreshed (by asking the
handler) only on the event `UIEvents.REQUEST_ENABLEMENT_UPDATE_TOPIC`, sent through the
`IEventBroker`, or when the window's active contexts, selection or shell change. So
`ToggleButtonRefresh` sends that event itself: without it the button greys out only if such an
unrelated change happens to come afterwards, which a busy machine sometimes reverses.

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

**The application model.** Since Eclipse 4 the window is also described by a model of plain
objects, which the renderer draws: an editor is an `MPart`, a row of editor tabs an `MPartStack`,
and the editor area (`MArea`) a sash container whose children sit side by side or one above the
other, each with a weight (`containerData`). An editor's model part is a service of its site
(`editor.getSite().getService(MPart.class)`). Change the model and the screen follows:
`EModelService.insert(stack, beside, EModelService.RIGHT_OF, 0.5f)` puts a new stack beside
another, `move(part, stack)` moves an editor into it, as dragging its tab does, and new weights move
the sashes. `EditorTabs` (`gt`) and `EditorSplits` (`:split`, `Ctrl-W l`) work this way; see
[SPLITS.md](design/SPLITS.md).

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

**The annotation model** is a fifth object next to the document: the marks drawn over the text and
in the rulers (problems, tasks, bookmarks, search results, occurrences), each an `Annotation` with a
*type* and a `Position` (offset and length) that moves as the document is edited. The document
provider gives it: `provider.getAnnotationModel(input)`. Problems come in two kinds: *markers* a
build saved on the file (`IMarker.PROBLEM`, shown as `MarkerAnnotation`s) and, in a Java editor,
the problems JDT finds while you type, which are annotations only. Reading the model gets both.
Annotation types form a hierarchy (`plugin.xml`, `org.eclipse.ui.editors.annotationTypes`): JDT's
`org.eclipse.jdt.ui.error` is a subtype of the platform's `org.eclipse.ui.workbench.texteditor.error`,
so `IAnnotationAccessExtension.isSubtype` asks "an error of any kind?" without knowing every plug-in
(`editor/ProblemAnnotations`). Models change on other threads too (JDT's reconciler), so a listener
only schedules work for the UI thread (`sync/DiagnosticsSync`).

**Markers** are the workspace's own record of problems and tasks, kept on each resource
(`IResource.createMarker`, `findMarkers`), saved with the workspace, and shown by the Problems and
Tasks views whether a file is open or not. A marker is a type (`IMarker.PROBLEM`, `IMarker.TASK`,
and subtypes such as JDT's `org.eclipse.jdt.core.problem`) and attributes (`MESSAGE`, `SEVERITY`,
`LINE_NUMBER`, `CHAR_START`). `IWorkspaceRoot.findMarkers(IMarker.PROBLEM, true, DEPTH_INFINITE)`
gives every problem of every file, subtypes included, from memory (milliseconds for thousands).
A change of markers arrives as a resource change whose delta lists them
(`IResourceChangeEvent.findMarkerDeltas`). An open editor shows each marker as a
`MarkerAnnotation` whose position follows unsaved edits (`AbstractMarkerAnnotationModel.getMarkerPosition`),
while the marker itself keeps the saved position until the next build: so for an open file the
editor's model is the fresher source (`editor/QuickfixLists`).

Multi-page editors (the POM editor, PDE's `MANIFEST.MF` editor) are not `ITextEditor`s. Their source
tab is a nested text editor, which `EditorAttachManager` digs out. A `MultiPageEditorPart` keeps
its page list protected, but two public methods are enough to turn it to a file's text page:
`findEditors(input)` gives the nested editors for one input, and `setActiveEditor(part)` shows one.
The plug-in does that when Neovim moves into a file whose editor shows a form page (`editor/TextPages`):
the manifest editor, for one, opens `plugin.xml` on the page it showed last. One editor may have
several text pages, each for its own file (the manifest editor shows `MANIFEST.MF`, `plugin.xml`
and `build.properties`), which is why the input picks the page, not the first text page found.

**The editor input says which file, and how.** The same file can be opened through two kinds of
input, and the document provider treats them differently:

- `FileEditorInput` (an `IFileEditorInput`): a *workspace* file, an `IFile` in a project. Reads and
  writes go through the workspace, so the Project Explorer, builders and problem markers see them.
  The provider refuses a file that does not exist: the editor shows "File ... does not exist."
  instead of its text.
- `FileStoreEditorInput`: a file *by location* (a URI), as File › Open File uses for files outside the
  workspace. Reads and writes go straight to disk; the workspace does not hear of them. A file that
  does not exist opens as an empty document.

So `:e new.txt` inside a project opens by location (an empty, editable editor) and, once the first
`:w` has created the file, switches the editor to the workspace file with
`IReusableEditor.setInput` (`NewFiles.becomeWorkspaceFileEditor`). A new input means a new
`IDocument`, which is why the plug-in moves the Neovim buffer to it
(`EditorAttachManager.replaceInputKeepingBuffer`) instead of starting a new one. Details:
[NEW_FILE.md](design/NEW_FILE.md).

Eclipse makes such a switch by itself too: rename an open file (Refactor › Rename, or F2 in the
Project Explorer) and its editor gets a new input, so a new `IDocument`, without being closed.
Anything that holds on to the old document (a listener, a sync) is then cut off from the editor. The
editor fires the property `IEditorPart.PROP_INPUT` (a part listener hears it as
`IPartListener2.partInputChanged`, but only for the workbench part: a multi-page editor's page
changes its input without its part's changing, so the plug-in listens on the text editor itself).
The plug-in then binds the editor to its new document and renames its Neovim buffer after the file,
keeping the undo history (`EditorAttachManager.onInputChanged`, `NeovimSession.renameBufferAsync`).
The new input alone does not tell why it came: File › Save As and an editor reused for another file
look alike (the old file is still there, and two empty files even have the same text). The plug-in
asks Eclipse instead: an `IExecutionListener` on the workbench's `ICommandService` hears every
command start and end, and an input that comes while `org.eclipse.ui.file.saveAs` runs is the file
saved under its new name (`editor/SaveAsWatch`). Not every Save As runs that command: the prompt for
a file deleted while open calls the editor's `performSaveAs` itself, and so does Save of a deleted
file. Those all set the new input inside `doSaveAs` or `performSaveAs`, and `setInput` fires
`PROP_INPUT` at once, so the listener also looks at its own call stack (`StackWalker`, in
`util/SaveAsFrames`): one of those methods there means a Save As. A reused editor's input never
comes from inside them.

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

Every activation or deactivation of a context makes the workbench work out again which bindings
apply. Code that switches many contexts at once wraps them in `IContextService.deferUpdates(true)`
... `deferUpdates(false)`, in a `finally`, so that this happens once. Example: at `Esc` the editor
turns on up to 27 contexts (the normal-mode context and one per `Ctrl` letter); one at a time this
took 5 to 9 ms, as one batch 0.3 ms.

On macOS, `M1` in `plugin.xml` means Command and `M4` means Control. On Windows and Linux `M1` is Ctrl.

**Filters for your own code.** `Display.addFilter(type, listener)` puts a listener in front of every
widget for one event type, the same place the key-binding dispatcher sits. A filter can take an event
away from the widget by setting `event.type = SWT.None`: SWT then delivers it to no listener. Example:
`editor/NeovimMouse` filters the editor widget's mouse events and swallows the left button's, so
`StyledText` neither moves its caret nor selects, and hands them to Neovim's own mouse instead. A
filter sees the events of *every* widget, so it checks `event.widget` first, and it must be removed
again (`removeFilter`) when it is no longer needed.

### 1.7 Preferences

- Values live in an `IPreferenceStore` (`Activator.getDefault().getPreferenceStore()`), saved per
  workspace.
- Defaults are set by an `AbstractPreferenceInitializer` in the `DefaultScope`. A stored value that
  equals its default is not written to disk. The values themselves are `prefs/PreferenceDefaults`'s,
  which the SWT tests also write into a store of their own.
- Pages are `PreferencePage` subclasses. `FieldEditorPreferencePage` builds a form from field
  editors (`NeovimPage`); a hand-written page builds its own widgets (`CtrlKeysPage`).
- Each page is two classes. The body (`NeovimPage`, `StatusBarPage`, `CtrlKeysPage`) uses JFace
  alone and takes its store through its constructor. The class `plugin.xml` names
  (`NeovimPreferencePage`, ...) is a subclass a few lines long: it adds `IWorkbenchPreferencePage`,
  which the extension point wants and which lives in the workbench jar, and passes the plug-in's
  store. So `tests/swt` builds a body without a workbench, on an in-memory store:
  `new StatusBarPage(store).createControl(shell)` (`prefs/package-info`, "Two classes per page").
- A page can also hold plain controls next to its field editors: the *Neovim* group of
  `NeovimPage` is `prefs/NeovimStatusSection`, a note, a button and a link with no
  preference behind them (the program to run is a startup option, `util/NeovimOption`, below).
  What the button and links do comes in as `NeovimStatusSection.Actions`: in Eclipse
  `WorkbenchSectionActions` (Get Neovim, the external browser), in `NeovimPageTest` a recorder.
  The note's work (hashing the dedicated Neovim, running `nvim --version`) happens on a background
  thread; `Display.asyncExec` brings the answer back to the UI thread, and a counter drops answers
  older than the newest probe.
- Why a startup option rather than a preference, for the one setting that names a program to run: a
  preference lives in the workspace and can be imported from a `.epf` file someone else wrote
  (*File → Import → Preferences*); a Java system property (`-D...` after `-vmargs`, on the command line
  or in `eclipse.ini`) is set only by whoever starts Eclipse. `System.getProperty` reads it; it is
  fixed for the run, so changing it means a restart. The UI tests get theirs from Tycho's
  `<systemProperties>`, before the plug-in starts.
- Field editors can sit in a `Group` box (`NeovimPage`: *Neovim* and *Workbench*):
  create each one on the group instead of `getFieldEditorParent()`. The catch: a field editor sets a
  fresh `GridLayout` on its parent (no margins), and the page adjusts columns only on its own parent,
  so the page overrides `adjustGridLayout` to give each group its columns and margins back. A text
  that changes height later (the note) calls `requestLayout()`, which lays out every ancestor, so the
  group grows with it.
- Code that must react to a change adds an `IPropertyChangeListener` to the store (`Activator.start`).
- Preferences live in **scopes**, from the most specific: `InstanceScope` (the workspace; the
  preference store's), `ConfigurationScope` (the Eclipse installation's `configuration/` folder,
  shared by every workspace started from it) and `DefaultScope` (defaults, never saved). Choose by
  what the value is about: the welcome note (`editor/WelcomeNotice`) records that it was shown in
  `ConfigurationScope`, because it greets an installation, not a workspace.
- A `plugin_customization.ini` changes *defaults* without code, one `<bundle id>/<key>=<value>` per
  line: a product ships one, and `-pluginCustomization <file>` passes one at start-up. Eclipse applies
  it after the `PreferenceInitializer`, so it wins. The UI tests use one to turn the welcome off
  (`tests/...tests/test-customization.ini`).

### 1.8 Building and installing: Tycho, features, p2

Plain Maven does not understand bundles. [Tycho](https://github.com/eclipse-tycho/tycho) is a set of
Maven plug-ins that read `MANIFEST.MF`, `feature.xml` and `category.xml`, and resolve dependencies
from an Eclipse *p2 repository* (the `eclipse-repo.url` in the root `pom.xml`) instead of Maven Central.

| Module | Packaging | Produces |
|--------|-----------|----------|
| `plugins/...neovim4eclipse` | `eclipse-plugin` | the bundle jar |
| `features/...feature` | `eclipse-feature` | a *feature*: the installable unit users see, grouping bundles |
| `releng/...updatesite` | `eclipse-repository` | a *p2 repository* (an update site) and its ZIP |
| `tests/unit` | `jar` | plain JUnit tests of the headless code |

p2 is Eclipse's installer. *Help > Install New Software* reads a p2 repository. p2 names a
feature's installable unit `<feature id>.feature.group`, which is why the installer shows
`...neovim4eclipse.feature.feature.group`.

---

## Part 2: Neovim API fundamentals

### 2.1 `nvim --embed`

`nvim --embed` starts Neovim without a terminal user interface. It reads requests on standard input
and writes replies on standard output. The program that started it (here, Eclipse) becomes its UI
and its only client. `util/NvimExecutable.resolve` turns the startup option `-Dneovim4eclipse.nvim`
(`util/NeovimOption`: unset for the dedicated Neovim, checked against a pinned hash by
`util/PluginNeovim.verified`, or one program's path) into the program to run, `util/NvimExecutable.embedCommand` builds the command line, and
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
this twice: when Neovim pastes from the system clipboard (`"+p`), it asks Eclipse for the text
(`eclipse_clipboard_paste`, see `core/ClipboardRegisters`); when Neovim writes a document's buffer
(`:bufdo update`), it asks Eclipse to save the file and waits to hear whether that worked
(`eclipse_write`, see `LuaScripts.DOCUMENT_BUFFER`). Eclipse learns its own channel number by
calling `nvim_get_chan_info(0)` itself (0 is "the caller" in a direct API call) and passes it to the
setup Lua: inside Lua run by `nvim_exec_lua`, `nvim_get_chan_info(0)` does not know who called.

**Showing an error from Lua.** `nvim_echo({ { msg } }, true, { err = true })` (Neovim 0.11) shows
`msg` the way Neovim shows its own errors: it sets `v:errmsg`, uses the `ErrorMsg` highlight, and a UI
that reads message kinds sees `echoerr`. Like `:echoerr`, it also *fails the command it runs in*:
inside the write hook's `BufWriteCmd` autocommand, a save Eclipse refused makes the `:write` fail, so
`:bufdo update` stops there, exactly as it stops at `E212: Can't open file for writing`. Echoing the
text with the `ErrorMsg` highlight instead would only look like an error (kind `echomsg`), and the
write would count as done. Through the API (`nvim_command("write")`) the failure comes back as the
request's error rather than as a message.

**What a write hook is told.** In a `BufWriteCmd` autocommand, `ev.match` (`<amatch>`) is the name
being written, not the buffer's: `:w other.txt` runs the hook of buffer `A.txt` with `other.txt`. The
plug-in's hook compares it with the editor's file (kept in `b:neovim4eclipse_file`) to tell a save from
a copy (`editor/OtherFileWrites`). Neovim does its usual checks before any hook runs, so the hook never
sees what Vim would refuse: an existing file without `!` (E13), a file another buffer has (E139).
Example: `:w other.txt` in a buffer with unsaved changes writes the copy and leaves `'modified'` set,
as Vim does, because the buffer's own file is still unsaved. A write of some lines runs a different
event, `FileWriteCmd` (`:5,10w part.txt`), and `>>` runs `FileAppendCmd`; Neovim sets the marks `'[`
and `']` to the lines, which the hook reads with `line("'[")`. A buffer with `buftype=acwrite` and no
such hook refuses them (E140 without `!`, E676 with it).
`:saveas new.txt` renames the buffer first (`BufFilePre`, `BufFilePost`), then writes it, so its
`BufWriteCmd` sees the buffer's new name as `ev.match`: that is how the hook tells it from a copy.

In Neovim, `:help api` lists every function and `:help rpc` describes the protocol. `nvim --api-info`
dumps the API as MessagePack.

Which Java value stands for which MessagePack value is decided in `core/rpc/MsgpackValues` (a
table in its class comment). Two things there are easy to trip over: every integer decodes as
`Long`, so read numbers through `Number`, since `Long.valueOf(1).equals(1)` is false; and buffers,
windows and tab pages are MessagePack *extension* values, a type byte plus the number. Buffer 9
is the three bytes `d4 00 09`. `MsgpackValuesTest` writes such bytes out in hex, which is a quick
way to see what really travels on the pipe.

### 2.3 The RPC client

The plug-in speaks msgpack-rpc with code of its own in `core/rpc`, on the msgpack-core library
alone. Three layers, which `NeovimConnection.open` builds on the process:

```text
RpcChannel          the messages on stdin/stdout: request ids, responses matched to their requests,
                    one reader thread that hands notifications and requests to core/rpc/Incoming
MsgpackValues       Java values to MessagePack and back (integers as Long, handles as Handle, ...)
MsgpackNeovimApi    the typed API, core/rpc/NeovimApi: api.input("dd"), api.getMode(),
                    api.bufSetLines(buffer, ...), one method per Neovim function
```

Where each of the message shapes from 2.2 is handled, in `RpcChannel`: a *request* we send gets a
msgid and a `CompletableFuture`, stored before the bytes are written so that even an instant answer
finds it; the *response* with that msgid completes the future (with the result, or with
`NeovimErrorException` carrying Neovim's `[type, message]`); a *notification* or a *request* from
Neovim goes to `Incoming` (`NotificationBridge`, which picks the handler by name with a `switch`),
and a request gets exactly one answer, an error answer included, because Neovim waits for it.

`NeovimApi` names its methods after Neovim's functions (`bufSetLines` is `nvim_buf_set_lines`)
and passes buffers and windows as plain `Handle(kind, id)` records. It used to be implemented with
the neovim-java library; the plug-in's own client replaced it by the *strangler* pattern: put your
own interface in front of the library, move every caller to it, write a second implementation, check
both against the real program with the same tests (a *contract test*), then swap.
`docs/design/RPC_CLIENT.md` tells the story step by step, including the library's bugs that made it worth
it: its request handlers never fired (they were stored in one map and looked up in another), and
it logged every normal shutdown as an error. A library's documented feature can be broken; a small
integration test against the real program (here `ClipboardIntegrationTest`) is what finds it.

Every API call returns a `CompletableFuture`. `Rpc.await` waits for it with a timeout (and
`Rpc.errorMessage` turns a failed call into Neovim's own `E…:` text).
Waiting is the blocking step that must never happen on the SWT thread. The futures are completed
on `RpcChannel`'s reader thread, so nothing chained onto them may block either.

msgpack-rpc has two kinds of message from client to server: a *request* carries an id and gets a
*response* with the same id; a *notification* has no id and gets no answer. Closing the session shows
why the difference matters: Neovim exits while it runs `:qa!`, so a `:qa!` sent as a request is never
answered, and a thread would wait for the answer until it times out. `NeovimConnection.requestQuit`
therefore sends `nvim_command("qa!")` as a notification and waits for the *process* to exit
instead. It tells the channel first (`RpcChannel.closing()`), so the end of Neovim's output that
follows is expected and logged at FINE; the same end without it, Neovim crashing, is a WARNING.

To watch the traffic, set the logger `io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.RpcChannel`
to FINEST: every message in and out is logged.

### 2.4 Attaching as a UI

A Neovim instance with no UI attached does not process typed input reliably. `NeovimSession.start`
therefore calls `nvim_ui_attach(120, 40, ...)` even though Eclipse draws nothing of Neovim's screen.
The size is only a start: once an editor attaches, `nvim_ui_try_resize` makes the screen the
editor's size, so that Neovim's screen commands count the lines you see (3.2b).
Neovim then sends `redraw` notifications describing that screen. The options passed to
`nvim_ui_attach` choose parts that come as *events* instead of drawn cells (`:help ui-ext-options`);
the plug-in asks for three (`NeovimConnection.TERMINAL_UI`) and ignores the rest of the screen:

- `ext_cmdline` and `ext_popupmenu`: Neovim's command line, as events. Typing `:set tabs` sends
  `cmdline_show` with the text `set tabs`, the cursor as a byte offset (8) and the type `:`; Left
  sends `cmdline_pos 7`; Tab on `:e ` sends `popupmenu_show` with the file names on grid -1 (the
  command line); Esc sends `cmdline_hide`. The editor's `:` box draws exactly that
  (`util/CmdlineState`) and sends every key back with `nvim_input`: the command line, its history and
  its completion are Neovim's own, the box only shows them.
- `ext_messages`: each message, as `msg_show` with a *kind* that says what it is. Example, `/nope`
  then Enter sends two:

  ```
  ["msg_show", "search_cmd", [[0, "/nope", 0]], false, false, false, 3, "typed_cmd"]
  ["msg_show", "emsg", [[25, "E486: Pattern not found: nope", 6]], false, true, false, 4, "typed_cmd"]
  ```

  The fields are kind, content (chunks of `[attribute, text, highlight]`), replace_last, history,
  append, id and trigger. `util/MessageEvent` reads them, `util/MessageRoute` decides where each goes:
  the echo of the search nowhere, the error to the message line in red; `:ls` (kind `list_cmd`) to
  the output popup when it lists more than one line, to the message line otherwise. Without `ext_messages`, a UI that draws no grid sees no message at all, and Neovim
  stops at a "Press ENTER" prompt whenever its message area overflows (2.6).

A UI that takes over parts of the screen draws them itself, with Neovim's data. Example, the
search preview: while `/foo` is typed with `'incsearch'`, Neovim moves its view to the match but no
`CursorMoved` fires; the only report is `win_viewport` (part of `ext_linegrid`), whose cursor the
session hands to the editor's caret.

`nvim_set_client_info` tells Neovim who is connected (`neovim4eclipse` and the bundle version).
`:checkhealth` and `nvim_list_chans()` show it.

Next, still before `nvim_ui_attach`, the plug-in asks `vim.version()` and refuses a Neovim older
than `util/NeovimVersion.MINIMUM`. Neovim answers requests before a UI attaches (only `init.lua`
waits for that), so an old Neovim is turned away before it runs the user's configuration. Why a
hard minimum instead of feature checks: the plug-in prefers to let Neovim do the work with its
newest APIs, and an old Neovim would fail later in a confusing way (a Lua error in a script, a
timeout). The "Neovim not started" notice then says `Neovim 0.12.4 is too old: Neovim for Eclipse
needs 0.12.6 or newer.` (docs/UPGRADING.md, "Neovim", says when the minimum goes up).

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
one thread (`neovim4eclipse-input`), which keeps keys in order and keeps blocking off the UI thread.

A *hit-enter prompt* would block the same way: in a terminal, `:bufdo %s/a/b/g` prints one
"N substitutions" message per buffer, more than fits, so Neovim asks "Press ENTER" and answers nothing
else until a key comes. A UI attached with `ext_messages` never sees one, because Neovim sends the
messages as events and has no message area of its own to fill (2.4). (Without it, a UI has to type
every command as `:silent cmd`, collect the output with `:redir` and send Esc whenever Neovim stops
at a prompt; the plug-in once did, and dropped it for `ext_messages`.) The `:` box still runs commands through `core/CommandRunner`,
which waits until Neovim is idle before refreshing Eclipse: a long `:!make` holds every request until
it ends.

When the plug-in must wait for Neovim where it cannot queue, the wait is bounded. Example: Eclipse
announces a project delete (`IResourceChangeEvent.PRE_DELETE`) and deletes the files as soon as the
listeners return, so `editor/ProjectFolderExit` cannot just queue "leave this folder" for later; it
waits for Neovim's answer, at most 1 s. If Neovim is waiting for the character after `f`, the request
is held, the wait ends after 1 s, and Eclipse goes on with the delete.

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
buffer itself, Neovim reports that write too, as a line event, which must not come back to the
document: it has those lines already, and an echo applied again can remove lines twice. So the write
is one Lua script that reads the buffer, sets only the lines that differ, and sends a notification
before and after (`eclipse_echo`): `LuaScripts.SET_DOCUMENT_LINES` for a buffer in the background,
`SET_LINES_MARKED` for the attached one (all of it, or the part typed in insert mode). Neovim sends
notifications and line events on one channel in the order they happen, and runs one script at a time,
so the session drops exactly the events between the two (`setDocumentBufferLines`). A change a plug-in
makes just before or after it still reaches the document. Example: a timer edits line 1 as Eclipse
pushes line 2; line 1's event comes outside the pair, line 2's inside.

**Unload, wipe, keep.** Vim's `:bd` *unloads* a buffer (its text and undo history go) and unlists it,
but keeps the buffer itself: its number, marks and last cursor position (`'"`). `:bw` *wipes* it,
and the number is gone. The plug-in does what `:bd` does when a file's last editor closes, so the
file's next editor gets buffer 3 back, not a new number (docs/design/BUFFER_NUMBERS.md).
`nvim_buf_attach` ends when a buffer unloads. A script's `:edit!` unloads and reads in one command,
so the plug-in keeps the text across it: `BufUnload` copies the lines and marks, the `BufReadCmd`
that replaces the read puts them back (`LuaScripts.DOCUMENT_BUFFER`), and Eclipse attaches again
with `send_buffer` true. Neovim then sends the whole buffer first, in the same stream as every later
change, so a key handled between the unload and the new attach is in that whole buffer and reaches
the document as a minimal edit. Example: a plug-in runs `:edit!` and you type `x` at that moment;
the `x` reaches Eclipse. (Not while Eclipse itself types in insert mode: the document is ahead of
the buffer until Esc, and a plain attach loses nothing then; `NeovimSession`, "re-attach after a
reload".)

Neovim has no notion of a final newline: a buffer is a list of lines. `util/LineTextUtil` explains how
the plug-in keeps an Eclipse file's final newline.

### 2.7a Windows and tabpages

A *buffer* is text; a *window* is a view on one buffer, with its own cursor, scroll position, folds,
jump history (`Ctrl-O`), location list, window-local options (`:setlocal list`) and folder
(`:lcd`); a *tabpage* is a layout of windows, and Neovim shows one at a time. Two windows on one
buffer share its text, undo history and marks. In the API they are handles like buffers:
`nvim_get_current_win()` (ids start at 1000), `nvim_set_current_win(win)`,
`nvim_win_set_buf(win, buf)`, `nvim_win_close(win, force)`, and `nvim_open_tabpage(buf, enter, {})`
for a new tab with one window. `nvim_win_call(win, fn)` runs `fn` as if `win` were current, without
entering it (and without autocommands), which is how a command such as `:clearjumps` or `:lcd`
reaches a window that is not current.

The plug-in gives each Eclipse editor a window of its own, alone in a hidden tabpage
(`'showtabline'` 0), so that the window fills Neovim's screen, which is the editor's size, and keeps
the editor's view while another editor is active ([design/WINDOWS.md](design/WINDOWS.md)). The
editor's token is `core/EditorWindow`; the Neovim side is the Lua module `LuaScripts.WINDOWS`. Two
habits from it are worth copying:

- **Autocommands off for what the user did not do.** Putting a buffer in a window of another tab
  enters that window for a moment and fires `BufEnter`, which Eclipse would take for a jump. The
  module sets `'eventignore'` to `all` around it (and adds `F` to `'shortmess'`, against the file
  info message).
- **Every report says which window.** `CursorMoved` sends `nvim_get_current_win()` along, and the
  session keeps only the attached window's: with two editors on one file, the buffer alone cannot
  tell them apart.

### 2.8 Positions: bytes, not characters

`nvim_win_get_cursor` returns a 1-based line and a 0-based **byte** column in UTF-8. Eclipse offsets
count UTF-16 characters. On a line with `é` (2 bytes in UTF-8, 1 char in UTF-16) the two disagree.
Every column crossing the boundary goes through `util/Utf8Columns`.

### 2.9 Running Lua inside Neovim

`nvim_exec_lua(code, args)` runs a Lua chunk and returns its result; `...` holds the arguments.
`core/LuaScripts` keeps every chunk the plug-in sends, with comments. Two uses:

- **Setup**: options and *autocommands* (`ModeChanged`, `CursorMoved`, `BufEnter`, `TextYankPost`)
  whose callbacks call `vim.rpcnotify(0, 'eclipse_mode', ...)`. Those notifications arrive at the
  matching `case` of `NotificationBridge.notification`. Setup also makes Eclipse
  Neovim's clipboard provider (`g:clipboard`, see `:help clipboard`), whose functions are Lua
  closures that notify or ask Eclipse.
- **Snapshots**: `STATE_SNAPSHOT` returns cursor, visual selection, mode, recording register and
  statusline in one round trip, instead of many separate requests per key. (Messages and the pending
  command are not in it: they arrive as UI events, see 2.4.)

**Testing a chunk.** A script is tested in Lua, inside a headless Neovim, not through the session
(`tests/unit/src/test/lua/*_spec.lua`, run by `LuaScriptsInNeovimTest` under `-Pintegration`). The
harness replaces `vim.rpcnotify` with a recorder and `vim.rpcrequest` with answers the test gives,
then types keys with `nvim_feedkeys(keys, 'xt')`: `x` runs them to the end before the call returns,
`t` makes them count as typed (mappings, the command line and its autocommands apply). So there is
nothing to wait for:

```lua
t.test(':wa goes to Eclipse', function()
  t.setup()                                   -- LuaScripts.SETUP, as the session runs it first
  t.run('HOST_COMMANDS', t.host_rules)
  t.type(':wa<CR>')
  t.eq({ { 'wall', false, {} } }, t.sent('eclipse_command'))
end)
```

When the keys run out, `x` ends a pending mode as Esc would; `t.during(keys, check)` runs a check
from a mapping while visual mode or the command line is still on. `src/test/lua/harness.lua` has
the details.

`nvim_exec2(cmd, { output = true })` runs an Ex command and returns what it printed, instead of
showing it. The plug-in uses it once, at start-up, to read `:messages` when the user's configuration
reported errors (`NeovimConnection.waitForStartup`).

---

## Part 3: Following the code

### 3.1 Start-up

1. Eclipse starts the workbench and calls `EarlyStartup.earlyStartup()`.
2. That loads a class from the bundle, so OSGi starts it: `Activator.start` installs the preference
   listener; `EditorAttachManager.installWorkbenchListeners` adds the part and window listeners.
3. A text editor becomes active, so `IPartListener2.partActivated` fires and `EditorAttachManager`
   attaches. The first attach calls `Activator.getOrCreateSession`, which runs `NeovimSession.start`:
   process, RPC stack, `nvim_set_client_info`, version check, `nvim_ui_attach`, home buffer, notification handlers,
   `LuaScripts.SETUP`. Once that succeeded, `WelcomeNotice.afterStart` queues the welcome check
   (`util/WelcomeNote` decides; the dialog opens after the attach, at most once per run).
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

### 3.2a Folding: `zc` on a method

Eclipse folds through *projection*: the viewer is a `ProjectionViewer`, and each fold is a
`ProjectionAnnotation` in its `ProjectionAnnotationModel`, with a `Position` over the fold's lines.
Collapsing the annotation makes the widget leave those lines out (hence `WidgetOffsets`), except its
*caption line*: for a Java member with Javadoc the position is an `IProjectionPosition`, whose
`computeCaptionOffset` points at the signature. Neovim has the same idea, manual folds
(`:{first},{last}fold`). The plug-in copies Eclipse's into Neovim:

1. At attach, `NeovimEditorController.sendFolds` asks `FoldMirror.send`, which reads every annotation's
   lines, lets `util/FoldRegions.forNeovim` sort and filter them, and queues `LuaScripts.SET_FOLDS`
   after the document push. The fold module (`LuaScripts.FOLDS`) creates the folds and puts an
   extmark on each, whose id is the fold's index: that is how Neovim names a fold to Eclipse later.
2. `zc` goes to Neovim like `x` (3.2). Neovim closes the method's fold.
3. The state snapshot after the key also returns the outermost closed folds' indexes (the module's
   `poll`). `StateSync` passes them on as a `FoldState`, before the cursor.
4. `FoldMirror.applyNeovimState` compares them with the annotations (`FoldRegions.stateChange`) and
   collapses the method's annotation, with a flag set so that its own model listener does not send
   the change back.
5. The cursor arrives next. It is inside a fold that is collapsed, so `StyledTextCaret` asks
   `FoldMirror.captionOffset` and puts the caret on the signature line instead of unfolding.

The other way, a fold collapsed with the ruler changes the annotation model; the mirror's listener
sends every fold again (one send per UI turn), and Neovim closes it. Design: [FOLDING.md](design/FOLDING.md).

### 3.2b Scrolling: `Ctrl-E`, and the mouse wheel

Neovim decides where its window is scrolled, and the editor copies it. Before that can mean
anything, Neovim's window must be as tall as the editor: `editor/ViewportFollower` measures the text
area in cells (its height over the line height: 520 pixels of 13-pixel lines are 40 rows), the
session adds the rows Neovim has besides the window (its status line, read with
`LuaScripts.VIEWPORT_OVERHEAD`) and calls `nvim_ui_try_resize`. Then, with the editor showing lines
1 to 40:

1. `Ctrl-E` goes to Neovim like `x` (3.2). Neovim scrolls its window one line; its cursor, on line
   10, stays.
2. Neovim's main loop notices the scroll and fires `WinScrolled`. The viewport module's autocmd
   (`LuaScripts.VIEWPORT`) sends `eclipse_topline(buf, 2)`.
3. `NotificationBridge` passes it to the session as a top line, which `SessionEvents` hands to
   `NeovimEditorController.onNeovimTopLine`: on the UI thread, `ViewportFollower.onNeovimTopLine`
   calls `ITextViewer.setTopIndex(1)` (0-based, in document lines; the viewer converts where code is
   folded). The editor shows lines 2 to 41.
4. A key that moves the cursor and scrolls (`50G`) fires `CursorMoved` first, whose report
   (`eclipse_cursor`) carries `line('w0')`, already the new top line. The bridge passes the top line
   on before the cursor, so the editor scrolls, then the caret moves onto a line it shows and
   Eclipse's own reveal does nothing.

The mouse wheel takes the same road the other way first: `editor/NeovimWheel`, a display filter,
swallows the wheel event, `util/WheelScroll` turns SWT's `count` into lines (−3 is three lines down
on Linux), and `LuaScripts.SCROLL_LINES` steps Neovim's top line as `Ctrl-E` would, dragging the
cursor into the view. From step 2 on it is `Ctrl-E`. The editor never scrolls by itself, so the two
cannot disagree. When it does scroll by itself (the scroll bar, a search hit), the next paint sends
its top line to Neovim, which drags its cursor the same way. Design: [VIEWPORT.md](design/VIEWPORT.md).

### 3.2c `gf` onto a file that has no editor

`a.txt` is open and says `see b.txt`; `b.txt` is in the same project, with no editor. The cursor is on
`b.txt`, and the user types `gf`.

1. Neovim makes a buffer for `b.txt`. Its `BufNew` reaches `LuaScripts.PROJECT_FILES`, which
   resolves the file's path (`fs_realpath`) and finds it inside a project folder that
   `editor/ProjectFolders` sent at the first attach. The buffer gets a buffer-local `BufReadCmd`.
   (`:vimgrep` makes its matches' buffers without `BufNew`; for those, the `BufAdd` of the jump
   into one does this step. A buffer `BufNew` checked is not resolved again on `BufAdd`.)
2. Neovim loads the buffer: that `BufReadCmd` reads it instead of Neovim (`:read ++edit` without
   autocommands, `'swapfile'` off first). No filetype is detected, so no filetype plug-in and no
   `FileType` autocommand of the user's runs: a language server started from one would be a second
   one beside Eclipse's.
3. `BufEnter` sends `eclipse_buf_enter` with the file's name as its fourth value
   (`NotificationBridge`, `DocumentBuffers.onEntered`). A background job finds the workspace file
   (`ProjectFileEditors.workspaceFile`), refreshing it in case it was made outside Eclipse: a
   refresh waits while a build holds the workspace, which on the UI thread would freeze Eclipse.
   Then `EditorAttachManager.followNeovimIntoFile` runs on the UI thread.
4. `ProjectFileEditors.open` checks that Eclipse's editor for the file is one of its own (a `.zip`
   would start another program) and opens it without activating it. `NavigationHistorySteps.activateForNeovim` activates
   it, so that Eclipse's Back history notes the switch as the plug-in's.
5. The window that jumped travels into the new editor (`travel`), and the attach takes Neovim's
   cursor (`attach(..., true)`). `CREATE_DOCUMENT_BUFFER` finds `b.txt`'s buffer by name and makes it
   the document's buffer: same number. Eclipse's text is pushed into it.
6. `Ctrl-O` in `b.txt` goes back to `a.txt`: the window's jump history has the jump, and the
   window travels back the same way.

Anything else Neovim enters (`:help`, a file outside the projects) is still undone. Design and the
probes behind each step: [BUFFER_NUMBERS.md](design/BUFFER_NUMBERS.md), 1.1 and 2.3. Tests:
`project_files_spec.lua`, `ProjectFilesEndToEndTest`.

### 3.2d Get Neovim: a download in a background Job

The user presses *Get Neovim 0.12.6…* in the "Neovim not started" notice and chooses *Download*.

1. `prefs/GetNeovim.open` reads `util/neovim-downloads.properties` through `util/NeovimRelease`: the
   version, and for this machine's platform code (`util/NeovimPlatform`, decided by the real
   processor in `util/ProcessorProbe`) the archive's URL, size and SHA-256. Code holds none of these:
   `releng/UpdateNeovimDownloads.java` rewrites the file for a new Neovim, after comparing each
   archive with GitHub's published digest. The confirmation shows them: its words are `util/GetNeovimText`'s, laid out by
   `dialogs/HeadedMessageDialog`, the welcome's layout (headed points, values with *Copy*, links).
2. A **Job** (`org.eclipse.core.runtime.jobs.Job`) does the rest. A Job is Eclipse's unit of
   background work: it runs on a worker thread, shows in the status bar and the Progress view, and
   its `IProgressMonitor` carries the progress and the user's Cancel. `setUser(true)` first shows a
   progress dialog that the user can send to the background. Nothing here may run on the UI thread
   (section 1.5): a 12 MB download would freeze Eclipse. The pure steps report through
   `util/InstallProgress`; `GetNeovim.MonitorProgress` adapts the monitor to it, one `SubMonitor`
   share per step.
3. `util/NeovimDownload` uses Java's own `java.net.http.HttpClient`, built with the redirect policy
   (GitHub answers with a redirect to `release-assets.githubusercontent.com`) and a `ProxySelector`
   from `prefs/EclipseProxies`, which asks Eclipse's proxy service (`IProxyService.select`, from
   `org.eclipse.core.net`) for each address, as Eclipse's own updater does, and lets
   `util/ProxyChoice` pick from the answer (HTTP proxies only; SOCKS only means stop, not "direct").
   That split is the project's usual one: the Eclipse class only fetches, and the decision is a pure
   class with its own unit test (`ProxyChoiceTest`). An **OSGi service** is
   counted against the bundle that took it (`BundleContext.getService`) until it is given back
   (`ungetService`), so `EclipseProxies` is `AutoCloseable` and held in a try-with-resources for one
   download. A Job's Cancel only sets a flag (it never interrupts the thread), so a thread stuck in a
   blocking read could not see it: the body is written by the client's own threads
   (`sendAsync` with `BodySubscribers.ofFile`), and the Job's thread only watches, every 250 ms, for
   Cancel, a stalled connection (60 s without a byte) and a body larger than the pinned size.
   The stall is counted on a clock passed in (`System::nanoTime` in the product), so the test can
   move a fake clock itself instead of waiting for real seconds; see "Tests on a loaded machine" in
   CONTRIBUTING.md.
4. `util/NeovimInstaller` locks `neovim/.install.lock` (a `FileChannel` lock: no two Eclipses at once),
   copies the file while hashing it (`MessageDigest`), and refuses anything but the pinned archive.
   The copy is what is unpacked: checking a file and then opening it again is a **time-of-check to
   time-of-use** gap, in which the file can change. `ArchiveExtractor`
   unpacks it into `neovim/la.new/`, refusing any entry whose name would land outside that folder:
   the **zip slip** check (`../../.bashrc` in an archive). It runs `bin/nvim --version`, then renames
   `la.new` to `la`, unless a Neovim runs from `la` right now (Windows locks a running program); then
   `Activator.getOrCreateSession` swaps it in before the next Neovim starts. "Right now" is asked at
   the rename, through a `SwapGate` that `Activator.swapUnlessRunningFrom` answers while holding the
   lock a session start takes, so a Neovim started during the download counts and none starts
   during the rename.
5. Back on the UI thread (`Display.asyncExec`), the done note offers what is still needed, or
   attaches the active editor. The menu entry's `visibleWhen` test (`prefs/GetNeovimOffer`) is
   worked out again in the background and re-evaluated with `IEvaluationService.requestEvaluation`.

Design, the proxy and platform experiments and the decisions: [DOWNLOAD_NEOVIM.md](design/DOWNLOAD_NEOVIM.md).
Tests: `ArchiveExtractorTest` (archives written byte by byte), `NeovimInstallerTest`,
`NeovimDownloadTest` (a local `com.sun.net.httpserver.HttpServer`, so no test touches GitHub).

### 3.2e Search highlighting: `/foo`, and the matches painted

The editor shows the text `a foo b foo`, and the user types `/foo` and Enter.

1. The keys go to Neovim like `x` (3.2); `/` opens Neovim's command line, shown in the `:` box.
   Neovim jumps to the first match and redraws its window with every match highlighted.
2. The redraw calls the decoration provider's `on_win` (`LuaScripts.SEARCH`). It changes nothing
   itself (Neovim forbids that while it draws), only schedules `M.update`, which runs right after.
3. `M.update` takes the pattern (the typed text while the box is open, else register `/`), adds
   `\c` or `\C` for `'smartcase'`, and calls `matchbufline()` over the visible lines: matches at
   bytes 2 to 5 and 8 to 11 of line 1. They differ from what was sent last, so it sends
   `eclipse_search(buf, {1, 2, 1, 5, 1, 8, 1, 11}, win)`.
4. `NotificationBridge` turns the numbers into `util/SearchMatch` records; the session passes them
   on for the attached window only (`SearchPort`); `NeovimEditorController.onSearchMatches` hops to
   the UI thread.
5. `editor/SearchHighlighter` turns byte columns into document offsets (2.8, through
   `editor/SearchRegions`, which needs only the text model) and asks the viewer to
   rebuild the presentation of that region (`invalidateTextPresentation`). The viewer runs the
   syntax coloring again for it, then calls the highlighter's `applyTextPresentation`, which merges
   a background into the ranges of the matches. The text keeps its colors. A paint listener then
   draws a box around the match under the caret.

`n` moves Neovim's cursor to the next match. Neovim redraws, `M.update` finds the same matches and
sends nothing; the caret moves (`eclipse_cursor`, as for any motion), and the highlighter's caret
listener moves the box with it. `:noh` sends an empty list, which clears.

Where the background color comes from (`editor/SearchColors`): a theme color the plug-in declares.
A `colorDefinition` in `plugin.xml` (`org.eclipse.ui.themes`) gives it an id, a label and a default
(255,214,102), and Eclipse lists it under *Colors and Fonts*, where users change it. Eclipse keeps
its value in the current theme's `ColorRegistry`, in layers: the default from `plugin.xml`, then the
theme's value, then the user's choice, each hiding the one before. The theme's value comes from
CSS: a stylesheet the plug-in adds to Eclipse's dark theme (`org.eclipse.e4.ui.css.swt.theme`)
sets `ColorDefinition#io-github-jm6cnddxbwftaks-neovim4eclipse-searchMatch { color: #6E5818; }`
(the id with dashes for dots, as CSS ids cannot hold dots). The code only reads the registry, and
listens to the theme manager for a change. Example: a user on the dark theme who picked green in
*Colors and Fonts* gets green; one who picked nothing gets the stylesheet's dark amber. Where the
registry still holds a default, the editor's own background has the last word
(`OverlayColors.searchMatch`), for dark editors outside Eclipse's dark theme. Design:
[SEARCH_HIGHLIGHTING.md](design/SEARCH_HIGHLIGHTING.md).

### 3.2f `:vsplit`: a Vim split as an editor stack

`test.txt` is open and attached; the user types `:vsplit` and Enter.

1. Neovim's `CmdlineLeavePre` parses the line (`nvim_parse_cmd()`: `cmd = 'vsplit'`). Its
   `translate` hook, from `LuaScripts.WINDOW_COMMANDS`, reads `'splitright'` (off: the left) and
   sees no file, so the line becomes `:Eclipse window split left same`, which sends
   `eclipse_command` with those words. No Neovim window opens.
2. `HostCommand.of` makes it a `WINDOW` command, `util/WindowCommand` reads the words, and
   `HostCommands` hands it to `EditorSplits.run` on the UI thread.
3. `same` means a clone: *Window › Editor › Clone* opens a second editor on the file, in the same
   stack, with the caret and scroll position copied. With the screen's redraw held, a new
   `MPartStack` goes to the left of the old one (`EModelService.insert`), the clone moves into it,
   and the old stack shows `test.txt` in front again.
4. The clone is activated. The part listener attaches Neovim to it, with a window of its own on
   the same buffer, its cursor at the copied caret (section 2.7a).
5. `Ctrl-W l` (`Ctrl+W` goes to Neovim by default) sends `window focus l 0`; `EditorSplits` draws
   the area as a `util/WindowLayout` tree, which finds the editor to the right, level with the
   caret, and activates it. `:q` in the clone closes it, and its emptied stack goes away by itself.

Design: [SPLITS.md](design/SPLITS.md). Tests: `windows_spec.lua`, `WindowLayoutTest`,
`SplitsEndToEndTest`.

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
    void type(String keys) throws Exception;            // nvim_input
    void awaitIdle(Duration timeout) throws Exception;  // any request: answered once Neovim is idle
    void clearMessages();                               // a new command starts
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
runner.run("echo 'hello'");
assertEquals(List.of("clearMessages", "type :echo 'hello'<CR>", "awaitIdle 5000", "syncBuffer",
        "syncModeAndCursor"), nvim.calls);
```

Why an interface per user rather than one big "Neovim" interface: each port shows exactly what a class
depends on, and a fake only has to implement those few methods. The ports in this code base:

| Port | Used by | Implemented by |
|------|---------|----------------|
| `KeyRouter.Neovim` | `core/KeyRouter` | `NeovimSession` |
| `BufferPort` | `sync/BufferSynchronizer` | `NeovimSession` |
| `DocumentBufferPort` | `sync/UnattachedDocumentSync` | `NeovimSession` |
| `DiagnosticsPort` | `sync/DiagnosticsSync` | `NeovimSession` (a fake that records each list, in `DiagnosticsSyncTest`) |
| `QuickfixPort` | `editor/QuickfixLists` | `NeovimSession` |
| `IdeKeys.Editor` | `core/IdeKeys.pick` (which IDE command a key runs, and the fallbacks) | `editor/IdeActions.EclipseEditor` (`FakeEditor` in `IdeKeysTest`) |
| `CursorPort` | `editor/CaretSync` | `NeovimSession` |
| `CommandRunner.Neovim` | `core/CommandRunner` | adapter inside `NeovimSession` |
| `KeyInput.Neovim` | `core/KeyInput` | adapter inside `NeovimSession` (`KeyInputTest` scripts it: "after `i` the mode is `i`") |
| `StateSync.Neovim`, `StateSync.Listeners` | `core/StateSync` | adapter inside `NeovimSession`; `SessionEvents` |
| `UiExecutor` | `BufferSynchronizer`, `CaretSync`, `DiagnosticsSync` (`timerExec` for its debounce) | `editor/SwtUiExecutor` (`UiExecutor.IMMEDIATE` in tests, or a fake that holds timed work until the test runs it) |
| `EditorCaret` | `CaretSync`, `VisualSelectionView` | `editor/StyledTextCaret` (`FakeCaret` in tests) |
| `HostCommandRunner.Ide` | `util/HostCommandRunner` | a record inside `editor/HostCommands` |
| `DocumentPush.Neovim`, `DocumentPush.Mirror`, `DocumentPush.Caret` | `sync/DocumentPush` | a record in `NeovimEditorController`; `BufferSynchronizer`; `editor/CaretSync` |
| `BlockHighlight` | `VisualSelectionView` | `editor/BlockOverlay` (a fake that keeps the ranges in tests) |
| `CommandLineBox.Commands` | `editor/CommandLineBox` | `SessionCommands` in `NeovimEditorController` (a fake that records the keys and pastes sent, in tests) |
| `ClipboardPort` | `core/ClipboardRegisters` | `editor/ClipboardBridge` (`ClipboardPort.NONE`, or a fake that keeps one text per selection, in tests) |

A port is not the only way to keep Eclipse out of a class. When the class only *stores* Eclipse
objects and compares them, never calls them, type parameters are enough. `core/EditorBuffers<D, E>`
decides which editors share a document's buffer, which editor Eclipse activates when Neovim switches
buffers itself (the first one opened, so the choice is repeatable), and when a buffer is given up
(its last editor closes; the session then unloads it, as Vim's `:bd` does, and keeps it for the
file's next editor). In the plug-in it is an `EditorBuffers<IDocument, IEditorPart>`; in
`EditorBuffersTest` it is an `EditorBuffers<String, String>`:

```java
EditorBuffers<String, String> buffers = new EditorBuffers<>();
DocumentBuffer a = buffers.add("A.java", "editor 1", () -> "/src/A.java");
buffers.add("A.java", "editor 2", () -> "/src/A.java");      // same buffer, shared undo history
assertEquals(List.of(), buffers.remove("editor 1"));          // editor 2 still shows A.java
assertEquals(List.of(a), buffers.remove("editor 2"));         // last one closed: give it up
```

When *not* to add one: glue code that only passes values through (an adapter, `KeyInterceptor`) has no
decision to test, and a port would only add a layer to read through. The integration and UI tests
still matter: a fake says what the code *asks for*, and only a real Neovim or Eclipse shows that
asking for it works. See CONTRIBUTING.md, "Test helpers and fakes", for where the fakes live.

---

## Part 4: Recipes

**Add a preference.** Add a key and default to `PreferenceConstants`; set the default in
`PreferenceDefaults`; add a field to a preference page's body (`NeovimPage`, `StatusBarPage`,
`CtrlKeysPage`) and test it in its `tests/swt` test; read it with
`Activator.getDefault().getPreferenceStore()`; if it must apply at once, handle it in the
`Activator`'s property-change listener.

**Add a color users can change.** Declare it in `plugin.xml` (`org.eclipse.ui.themes`: a
`themeElementCategory` to group it, a `colorDefinition` with id, label, `value="r,g,b"` and a
description), give the dark theme its own value in a stylesheet (`css/`, listed in
`build.properties`, added with `org.eclipse.e4.ui.css.swt.theme`), read it with
`PlatformUI.getWorkbench().getThemeManager().getCurrentTheme().getColorRegistry().getRGB(id)`, and
listen to the theme manager for the id and `IThemeManager.CHANGE_CURRENT_THEME`, hopping to the UI
thread. Follow `editor/SearchColors`: the reading and listening sit in `editor/ThemeColorSource`,
behind `SearchColors.Source`, so what the code makes of the color is tested in `tests/swt` with a
source of the test's own. Add a check like `SearchMatchColorFilesTest` that the values in
`plugin.xml`, the stylesheet and the code agree.

**Add a setting for `init.lua` instead.** A Neovim-side switch that is set once and forgotten
(`vim.g.neovim4eclipse_folds`, `vim.g.neovim4eclipse_problems`) is better as a global variable than
a check box: it lives with the user's other Neovim settings and dotfiles, and keeps the preference
pages short. The price: it is read once, so a change needs a Neovim restart. Read it in a small
`LuaScripts` script that returns a boolean (`PROBLEMS`, `FOLDS`), run once in
`NeovimSession.initialize` after `SETUP` (so after `init.lua`); keep the answer in a field and
expose it on the feature's port (`DiagnosticsPort.problemsEnabled()`), so that off costs nothing.
Treat `0` and `false` as off and anything else, unset included, as on. Test the script in a
`*_spec.lua` and the read with `--cmd "let g:..."` in an integration test.

**Compare two file paths.** Never with `equals` or `==`: on Windows one file is `C:\WS\a.txt` and
`c:/ws/A.TXT`, on macOS `/var/x` and `/private/var/x` (and case does not count). Java: `FilePaths.same`
or `FilePaths.inside` (this machine's rule), or with a `FilePaths.Platform` of your own in a test;
Lua: `require('neovim4eclipse.paths').same` / `.inside`; a Lua spec: `t.same_file(expected, actual)`.
A path's workspace file: `NewFiles.workspaceFileAt` (also for a project or linked folder whose
location Eclipse recorded through a Windows junction: it compares the folders
`ProjectFolders.locations()` gives by the rule). A case the rule must handle goes into
`tests/unit/src/test/resources/path-cases.tsv`; `FilePathsTest` and `paths_spec.lua` both run it, on
the Linux, macOS and Windows rules, with fake links.

**Send something Eclipse knows about each open file to Neovim.** Follow `sync/DiagnosticsSync`
(Eclipse's problems, for `]d`): one object per document with a buffer, created and closed in
`EditorAttachManager` next to its `UnattachedDocumentSync`, started after the controller's attach so
that its first send is queued behind the buffer's first load. It listens to the Eclipse model on
any thread, schedules one send on the UI thread for many changes (`UiExecutor.timerExec`), reads the
model there, converts in a pure `util/` class (`DiagnosticPositions`), skips a list equal to the last
one, and sends with an `*Async` method of a small port (`core/DiagnosticsPort`), whose Lua script
(`LuaScripts.SET_DIAGNOSTICS`) is tested in a `*_spec.lua`. Test the timing in `tests/text` with a fake
port and held timers.

**Answer a command of Neovim's with something of the whole workspace.** Follow `:Eproblems`
(`editor/QuickfixLists`): a user command in a `LuaScripts` module checks its arguments and only
sends a notification; the session hands it to a handler the editor sets (`QuickfixPort.Handler`,
set in `EditorAttachManager` for each new session); one `Job` does the reading, one run at a time,
so an older answer never overtakes a newer one, and hops to the UI thread only for what lives there
(open editors, briefly, with `syncExec`); a pure `util/` class decides the content
(`QuickfixEntries`); the answer goes back with an `*Async` method. To keep the answer current, let
changes schedule the same job with a delay, and ask Neovim first whether it still shows that
answer.

**Put something in Eclipse's status bar.** Add a `ContributionItem` to the window's
`IStatusLineManager` (an editor reaches it through `getEditorSite().getActionBars()`) and create its
widgets in `fill(Composite)`; Eclipse disposes them and calls `fill` again whenever it rebuilds the bar
(`manager.update(true)`). Size them with `StatusLineLayoutData.widthHint` from the font, not fixed
pixels: characters times the average width for free text, `GC.textExtent` of the widest text for a
known set of texts (capitals are wider than the average). Leave Eclipse room: when the bar is too full it
cuts the rightmost items first, after its message area is down to a third (`util/StatusBarWidths`).
Example: `editor/ModeStatusContribution` with `editor/StatusBarLabels`; a new mode text goes in the
one table of `util/ModeLabels` and its test.

**Show a dialog once (or once per version).** Put the decision in a pure class in `util/` that takes
what was recorded and the running version, and returns what to show (`WelcomeNote.decide`); unit-test
it in `tests/unit`. The glue in `editor/` (`WelcomeNotice`) reads the record from a preference scope,
writes it *before* opening the dialog (so a crash does not repeat it), and opens the dialog with
`Display.asyncExec`, never inside the code that triggered it. The dialog itself, with what its
buttons do passed in (`dialogs/ActingHeadedDialog` and a `ButtonAction`), goes in `dialogs/`, and
its labels in the `util/` class (`WelcomeNote.WELCOME_BUTTONS`), so `tests/swt` opens it with the
real texts (`ActingDialogsTest`). Turn it off for the UI tests in
`test-customization.ini`, or the first test that triggers it waits for a click until it times out.

**Hand a normal-mode key to Eclipse.** Do not catch it in Java: map it in Neovim, so Neovim keeps
reading counts, prefixes and arguments (`fZ` is not `ZZ`). For a key that runs an Eclipse command
(`gd`, `K`, `grn`), add a constant to `core/IdeAction` and two rows to `core/IdeKeys`: the key in
`KEYS` (modes, action) and the command in `COMMANDS` (its label, and the *ways* to find it, tried in
order: the command bound to Eclipse's usual key, `Way.boundTo("M2+M3+R")`, so each editor's own
runs; a command id, `Way.command(...)`; or a hyperlink detector, `Way.hyperlink(...)`). That is
all: `LuaScripts.IDE_KEYS` maps every key of the table (inside the `neovim4eclipse_ide_keys`
check), and `IdeKeys.pick` runs it over a small port (`IdeKeys.Editor`, implemented for Eclipse by
`editor/IdeActions.EclipseEditor`), so the decision is unit-tested with a fake editor, no waiting. Check the command ids in a running Eclipse first: a temporary UI test that lists
`ICommandService.getDefinedCommandIds()` with `isHandled()` and `IBindingService.getActiveBindingsFor`
per editor shows them. A key the plug-in's own key contexts take (`Ctrl+O`) cannot be looked up by
binding; use the command id. For a key with an action of its own (`ZZ`, `ZQ`), add a
`vim.keymap.set` in `LuaScripts.SETUP` and a `case` in `editor/IdeActions.run`. Test the mapping in
Lua (`src/test/lua/setup_spec.lua`: the key sends the notification, with its count), the table in
`IdeKeysTest`, and the choice per editor in a UI test (`IdeKeysEndToEndTest`, or
`JavaEditorEndToEndTest` for Java). Example: `ZZ` and `ZQ` (`save_and_close`, `close`). Why
not Java: a `KeyRouter` that follows Vim's grammar itself (an earlier `KeyGrammar` class, 392 lines)
to know whether a `Z` or `:` starts a command gets every new case (`gqq`, `zfzf`, `g??`) wrong first.

**Work with Eclipse's navigation history (Back, Forward).** Each workbench page keeps one history
(`page.getNavigationHistory()`, an `INavigationHistory`): a list of locations, one current. Three
things to know. Every editor activation adds an entry, also one your code makes, and it is added a
moment later (Eclipse posts it with `asyncExec`), so read the history after your own `asyncExec`. A
new entry deletes the entries ahead of the current one, as a browser does. And the API only reads:
there is no call to leave an entry out or to go to one, so you move with the commands
(`IWorkbenchCommandConstants.NAVIGATE_BACKWARD_HISTORY`, run through the window's
`IHandlerService`) and count the steps yourself. Locations are compared by identity: Eclipse updates
an entry's location in place. Example: `Ctrl-O` past Neovim's jumps steps over the entries the
plug-in's own switches added (`util/HistorySteps` decides, `editor/NavigationHistorySteps` runs the
commands; docs/design/WINDOWS.md, E2).

**Get a new event from Neovim.** Add an autocommand to `LuaScripts.SETUP` that calls
`vim.rpcnotify(0, 'eclipse_<name>', ...)`; add a `case "eclipse_<name>"` to
`NotificationBridge.notification` (with a private method that reads the arguments) and a method to
its `Sink`; implement it in `NeovimSession.EventSink`. Test the autocommand in a Lua spec
(`t.sent('eclipse_<name>')`) and the reading in `NotificationBridgeTest`. If the
event changes state the session keeps (like mode or cursor), record it in `StateSync` and give it a
listener list in `SessionEvents`.

**Answer a question from Neovim.** In Lua, call `vim.rpcrequest(chan, 'eclipse_<name>', ...)`, where
`chan` is the channel number `LuaScripts.SETUP` receives as its argument and keeps in
`g:neovim4eclipse_channel` (Neovim waits for the answer,
so keep the Java side quick and bounded); add a case to `NotificationBridge.request` and a method to
its `Sink`; implement it in `NeovimSession.EventSink`. The value returned is the answer; Lua sees Java
lists as tables. `eclipse_clipboard_paste` and `eclipse_write` are the examples; the second shows the
UI-thread hop with a bounded wait (`EditorAttachManager.saveForNeovim`).

**Ask Neovim for more state per key.** Extend `LuaScripts.STATE_SNAPSHOT` (append at the end so the
existing indexes stay valid), add a reader for the new element to `StateSnapshot` (and a case to
`StateSnapshotTest`), and use it in `StateSync.publishState`.

**Handle an Ex command in Eclipse instead of Neovim.** Add Neovim's full name for it (what
`nvim_parse_cmd()` returns as `cmd`: `:wa` is `wall`) to the table in `util/HostCommand`, with what
arguments it takes (unit-test it in `HostCommandTest`); Neovim's side gets the table at start-up, so
`:that-command` typed, mapped or recorded now runs as `:Eclipse that-command`. Say what it does in
`util/HostCommandRunner`, adding a method to its `Ide` interface if the IDE
must do something new (unit-test that in `HostCommandRunnerTest` with the fake `Ide`); then implement
the method in `editor/HostCommands` with Eclipse's API (`editor/EclipseFileCommands`, `EditorTabs`).

**Carry out a command whose meaning Neovim must work out first.** Some commands need Neovim's
state to mean anything (`:vsplit` reads `'splitright'`, `:sfind` searches `'path'`, `:sbuffer 3`
names buffer 3's file). Register a `translate` hook for Neovim's name of it, as
`LuaScripts.WINDOW_COMMANDS` does (`host.translate('sfind', function(p) ... end)`): it returns the
words of the `:Eclipse` line to run instead (`{ 'window', 'split', 'above', 'file', path }`), or
`nil` and Vim's error. Eclipse then gets plain words; read them in a small `util` record
(`WindowCommand`) and test both sides: the words in a Lua spec, the reading in `tests/unit`.

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
  `ErrorLogHandler`. The details (FINE and below) are hidden unless Eclipse starts with
  `-Dneovim4eclipse.logLevel=FINE` (or `FINER`, `FINEST`; `util/LogLevelSwitch`, and docs/USER_GUIDE.md,
  "The plug-in's log"). `KeyContextDiagnostics` reports which command the workbench resolves for
  `Ctrl+V`; docs/USER_GUIDE.md, "Blockwise visual", lists what its messages mean.
- **Key bindings**: *Window > Preferences > General > Keys* shows each binding with its context
  ("When"). The *Ctrl Keys* page decides which of this plug-in's letter contexts are active.
- **Inside Neovim**: `:messages`, `:ls`, `:set option?` all work through the `:` box. `vim.g.neovim4eclipse`
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
- [msgpack-rpc specification](https://github.com/msgpack-rpc/msgpack-rpc/blob/master/spec.md), the protocol `core/rpc` implements.
