// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.AbstractMarkerAnnotationModel;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DiagnosticsPort;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.DocumentBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.QuickfixPort;
import io.github.jm6cnddxbwftaks.neovim4eclipse.sync.DiagnosticsSync;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.Diagnostic.Severity;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.DiagnosticPositions;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries.Current;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries.Entry;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries.Kind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries.Mode;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries.TaskTags;

/**
 * Fills Neovim's quickfix list with the workspace's problems ({@code :Eproblems}) or tasks
 * ({@code :Etasks}), keeps it current while it is the current list, and shows the Problems or Tasks
 * view for {@code :copen} (docs/design/DIAGNOSTICS.md, section 5). Neovim walks the list with its own
 * {@code ]q}, {@code :cnext} and {@code :cc}; a jump into a file without an editor opens its editor
 * as any jump does ({@code ProjectFileEditors}).
 *
 * <h2>Where the entries come from</h2>
 * <ul>
 *   <li><b>A file with an open text editor</b>: what that editor shows. Problems are read from its
 *       annotation model as {@link DiagnosticsSync} reads them (so JDT's problems found while typing,
 *       before a save, are listed too), with exact lines and columns. Tasks are task markers placed
 *       where the editor's model has moved them ({@link AbstractMarkerAnnotationModel#getMarkerPosition}),
 *       which follows unsaved edits. The entry names the document's buffer when it has one.</li>
 *   <li><b>A closed file</b>: its markers, by line only ({@code IMarker.LINE_NUMBER}); a column would
 *       need the file's text. Once the file's editor opens, the next update gives its entries
 *       columns, and the jump that opened it moves on to the column ({@code LuaScripts.QUICKFIX}).
 *       Example: a closed {@code B.java} with an error at line 7, column 12: {@code ]q} lands on line 7
 *       at its first non-blank, and some 500 ms later on column 12.</li>
 *   <li><b>A marker on a folder or project</b> ("The project cannot be built until build path errors
 *       are resolved"): an entry with its text and no file, which {@code :clist} shows and
 *       {@code :cnext} steps over, as Vim does with a line it could not parse.</li>
 * </ul>
 * Which entries count, their order, and the arguments sent are decided in {@link QuickfixEntries}
 * (pure, unit-tested).
 *
 * <h2>Keeping the list current</h2>
 * A change schedules an update ({@value #UPDATE_DELAY_MS} ms after the first, so a build's burst of
 * marker changes gives one): problem or task markers changed (a build; the
 * {@link IResourceChangeEvent#POST_CHANGE} listener {@link #install}s), a document got its Neovim
 * buffer or lost it ({@link #changed}), or an open document's problems changed (JDT's reconcile,
 * through {@link #watching}). The update asks Neovim for the current list first (Neovim has no event
 * for a change of the current list); only one of ours with its severity or tags is read again and
 * sent, by action {@code 'u'}, which keeps the entry the user is on; a list equal to the one sent
 * last is not sent again. Before the first
 * {@code :Eproblems} or {@code :Etasks} of a Neovim no list of ours can exist, so a change costs
 * nothing then.
 *
 * <h2>Threads</h2>
 * One {@link Job} does all of it, one run at a time (as {@code ProjectFolders}'s), so a list read
 * before a change never reaches Neovim after the list read after it. The job reads the markers (no
 * workspace lock is needed for that) and waits for Neovim's answer; the open editors' models and
 * documents are read on the UI thread, briefly ({@code syncExec}: one pass per open editor), as phase
 * 1 reads them. Nothing on the UI thread waits for Neovim.
 */
public final class QuickfixLists implements IResourceChangeListener {

    private static final Logger LOG = Logger.getLogger(QuickfixLists.class.getName());

    /** How long changes are collected before an update, in ms. */
    static final long UPDATE_DELAY_MS = 500;
    /** How long an update waits for Neovim to say which list is current, in seconds. */
    private static final long CURRENT_TIMEOUT_SEC = 10;

    /** The one job of the class comment. */
    private static final Runner RUNNER = new Runner();

    /** The running session, or {@code null}: a listener must never start Neovim. */
    private final Supplier<NeovimSession> session;

    private QuickfixLists(Supplier<NeovimSession> session) {
        this.session = session;
    }

    /**
     * Starts watching the workspace's markers (from {@code Activator.start}); undo with
     * {@link #uninstall}.
     *
     * @return the listener, or {@code null} when there is no workspace (logged)
     */
    public static QuickfixLists install(Supplier<NeovimSession> session) {
        try {
            QuickfixLists listener = new QuickfixLists(session);
            ResourcesPlugin.getWorkspace().addResourceChangeListener(listener, IResourceChangeEvent.POST_CHANGE);
            return listener;
        } catch (IllegalStateException e) { // no workspace: Eclipse started without one
            LOG.log(Level.WARNING, "No workspace: :Eproblems and :Etasks lists are not kept current", e);
            return null;
        }
    }

    /** Stops watching; does nothing for {@code null}. */
    public static void uninstall(QuickfixLists listener) {
        if (listener == null) {
            return;
        }
        try {
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(listener);
        } catch (IllegalStateException e) {
            LOG.log(Level.FINE, "The workspace is already gone", e);
        }
        RUNNER.cancel();
    }

    /** What {@code session} hears from Neovim's {@code :Eproblems}, {@code :Etasks} and {@code :copen}. */
    static QuickfixPort.Handler handlerFor(NeovimSession session) {
        return new QuickfixPort.Handler() {
            @Override
            public void request(Kind kind, String arg, boolean bang) {
                RUNNER.command(session, new Runner.Command(kind, arg, bang));
            }

            @Override
            public void showView(Kind kind) {
                QuickfixLists.showView(kind);
            }
        };
    }

    /**
     * Something the lists show changed outside the markers (a document got or lost its buffer, its
     * editor opened or closed): an update is scheduled. Any thread.
     */
    static void changed(NeovimSession session) {
        RUNNER.update(session);
    }

    /**
     * {@code session} as a {@link DiagnosticsSync} sees it, with every list of problems it sends also
     * scheduling an update ({@link #changed}): JDT's problems found while typing reach the quickfix
     * list too. The sync sends only lists that differ from the last, so this is rare.
     */
    static DiagnosticsPort watching(NeovimSession session) {
        return new DiagnosticsPort() {
            @Override
            public boolean problemsEnabled() {
                return session.problemsEnabled();
            }

            @Override
            public void setDiagnosticsAsync(DocumentBuffer buffer, List<Diagnostic> diagnostics) {
                session.setDiagnosticsAsync(buffer, diagnostics);
                changed(session);
            }
        };
    }

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        NeovimSession neovim = session.get();
        if (neovim == null || !neovim.isAlive()) {
            return;
        }
        if (event.findMarkerDeltas(IMarker.PROBLEM, true).length > 0
                || event.findMarkerDeltas(IMarker.TASK, true).length > 0) {
            RUNNER.update(neovim);
        }
    }

    /**
     * The job of the class comment. A command ({@code :Eproblems}) runs at once; changes are
     * collected for {@link #UPDATE_DELAY_MS}. Both go to the session they came from.
     */
    private static final class Runner extends Job {

        /** {@code :Eproblems[!] arg} or {@code :Etasks[!]} as Neovim sent it. */
        record Command(Kind kind, String arg, boolean bang) {
        }

        /** The newest command not yet run; an older one waiting is replaced (only the last list counts). */
        private final AtomicReference<Command> command = new AtomicReference<>();
        /** A change came and no update has read the workspace since. */
        private final AtomicBoolean update = new AtomicBoolean();
        private volatile NeovimSession target;
        /** The session in which a command made a list: before that, there is nothing to update. */
        private volatile NeovimSession listsIn;
        /**
         * The list sent last, so that an update equal to it is not sent again. Job thread only. An
         * equal list would change nothing in Neovim, but {@code setqflist()} still takes its time on
         * Neovim's input thread (about 150 ms for 9,000 entries), where it holds up keys.
         */
        private Sent lastSent;

        Runner() {
            super("Neovim: quickfix list");
            setSystem(true); // no progress shown: it takes milliseconds
        }

        /** The family {@code QuickfixLists.class}, so that a test can wait for the job to be done. */
        @Override
        public boolean belongsTo(Object family) {
            return family == QuickfixLists.class;
        }

        void command(NeovimSession session, Command next) {
            target = session;
            listsIn = session;
            command.set(next);
            schedule();
            wakeUp(); // sleeping (an update's delay): run now
        }

        void update(NeovimSession session) {
            if (session == null || session != listsIn) {
                return;
            }
            target = session;
            // A command waiting for its run makes the list anew, which covers this change; and while
            // the job runs, this schedule() would set the rerun's delay, so the command would wait
            // UPDATE_DELAY_MS. The run takes the flag along with the command either way.
            if (update.compareAndSet(false, true) && command.get() == null) {
                schedule(UPDATE_DELAY_MS); // running: once more afterwards
            }
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            NeovimSession neovim = target;
            Command next = command.getAndSet(null);
            boolean changed = update.getAndSet(false);
            if (neovim == null || !neovim.isAlive()) {
                return Status.OK_STATUS;
            }
            try {
                if (next != null) {
                    lastSent = send(neovim, next.kind(), next.arg(), Mode.COMMAND, !next.bang(), null);
                } else if (changed) {
                    Current current = neovim.currentQuickfixAsync().get(CURRENT_TIMEOUT_SEC, TimeUnit.SECONDS);
                    if (current != null) {
                        lastSent = send(neovim, current.kind(), current.arg(), Mode.UPDATE, false, lastSent);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) { // Neovim's error or timeout, a closed connection, no workspace
                LOG.log(Level.WARNING, "Could not fill the quickfix list", e);
            }
            return Status.OK_STATUS;
        }
    }

    /** A list as sent: to whom, which, and its entries. */
    private record Sent(NeovimSession session, Kind kind, String arg, List<Entry> entries) {
    }

    /**
     * Reads the list and sends it, unless it equals {@code last} (an update that changes nothing).
     * Returns what Neovim has now: the list sent, or {@code last}.
     */
    private static Sent send(NeovimSession neovim, Kind kind, String arg, Mode mode, boolean jump, Sent last) {
        List<Entry> entries = read(kind, arg);
        if (entries == null) {
            return last;
        }
        Sent now = new Sent(neovim, kind, arg, entries);
        if (now.equals(last)) {
            LOG.finer(() -> kind.title() + " (" + arg + "): unchanged, not sent");
            return last;
        }
        LOG.fine(() -> kind.title() + " (" + arg + "): " + entries.size() + " entries, " + mode);
        neovim.setQuickfixAsync(kind, arg, mode, jump, entries);
        return now;
    }

    // ---- reading the workspace -----------------------------------------------------------------

    /** One marker, read once (its attributes are read off the UI thread). */
    private record MarkerRead(IMarker marker, IResource resource, int line, Severity severity, String message) {
    }

    /** A file's open editor: its document, model and Neovim buffer (0: none). */
    private record OpenFile(IDocument document, IAnnotationModel model, int bufnr) {
    }

    /**
     * The entries of a list of {@code kind} with argument {@code arg}, sorted; {@code null} for an
     * argument Eclipse does not take (Neovim refuses those first). Job thread.
     */
    static List<Entry> read(Kind kind, String arg) {
        Severity least = kind == Kind.PROBLEMS ? QuickfixEntries.leastSeverity(arg) : null;
        TaskTags tags = kind == Kind.TASKS ? TaskTags.parse(arg) : null;
        if (kind == Kind.PROBLEMS && least == null) {
            LOG.warning(() -> "No such severity for :Eproblems: " + arg);
            return null;
        }
        Map<IResource, List<MarkerRead>> markers = new LinkedHashMap<>();
        try {
            String type = kind == Kind.PROBLEMS ? IMarker.PROBLEM : IMarker.TASK;
            for (IMarker marker : ResourcesPlugin.getWorkspace().getRoot().findMarkers(type, true, IResource.DEPTH_INFINITE)) {
                MarkerRead read = readMarker(marker, kind);
                boolean wanted = kind == Kind.PROBLEMS ? QuickfixEntries.atLeast(read.severity(), least)
                        : !marker.getAttribute(IMarker.DONE, false) && tags.matches(read.message());
                if (wanted) {
                    markers.computeIfAbsent(read.resource(), r -> new ArrayList<>()).add(read);
                }
            }
        } catch (CoreException e) {
            LOG.log(Level.WARNING, "Could not read the workspace's markers", e);
        }
        List<Entry> entries = new ArrayList<>();
        // The open editors on the UI thread; whatever they do not cover, from the markers here. Both
        // by the file's place on disk, not its IFile: a file of a nested project is two IFiles
        // (app/lib/X.java and lib/X.java), and its open editor covers both.
        Map<String, List<Entry>> fromEditors = onUiThread(() -> fromOpenEditors(kind, least, markers));
        for (Map.Entry<IResource, List<MarkerRead>> file : markers.entrySet()) {
            String location = fileOf(file.getKey());
            if (location.isEmpty() || !fromEditors.containsKey(location)) {
                for (MarkerRead read : file.getValue()) {
                    entries.add(lineOnly(read));
                }
            }
        }
        fromEditors.values().forEach(entries::addAll);
        return QuickfixEntries.sorted(entries);
    }

    private static MarkerRead readMarker(IMarker marker, Kind kind) {
        Severity severity = null;
        if (kind == Kind.PROBLEMS) {
            severity = switch (marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO)) {
                case IMarker.SEVERITY_ERROR -> Severity.ERROR;
                case IMarker.SEVERITY_WARNING -> Severity.WARN;
                default -> Severity.INFO;
            };
        }
        return new MarkerRead(marker, marker.getResource(), marker.getAttribute(IMarker.LINE_NUMBER, 0), severity,
                marker.getAttribute(IMarker.MESSAGE, ""));
    }

    /** A marker's entry by its line alone (a closed file, or a resource that is no file). */
    private static Entry lineOnly(MarkerRead read) {
        return new Entry(0, fileOf(read.resource()), moduleOf(read.resource()), read.line(), 0, read.severity(),
                read.message());
    }

    /** The resource's full path on disk, or "" for one that is no file or has none (a virtual folder). */
    private static String fileOf(IResource resource) {
        IPath location = resource instanceof IFile ? resource.getLocation() : null;
        return location == null ? "" : location.toOSString();
    }

    /** The workspace path {@code :clist} shows: {@code app/src/A.java}. */
    private static String moduleOf(IResource resource) {
        return resource.getFullPath().makeRelative().toString();
    }

    /**
     * The entries of the files with an open text editor, by the file's place on disk. Problems: every
     * problem the editor shows (its annotations), when its model holds the file's markers
     * ({@link AbstractMarkerAnnotationModel}, as every Eclipse text editor's does); an editor whose
     * model does not is left out, so its file is listed from its markers, by line. Tasks: the task
     * markers of the file (through any of its IFiles), where the editor's model has them now. UI
     * thread.
     */
    private static Map<String, List<Entry>> fromOpenEditors(Kind kind, Severity least,
            Map<IResource, List<MarkerRead>> markers) {
        Map<String, List<MarkerRead>> markersByLocation = new HashMap<>();
        markers.forEach((resource, reads) -> {
            String location = fileOf(resource);
            if (!location.isEmpty()) {
                markersByLocation.computeIfAbsent(location, l -> new ArrayList<>()).addAll(reads);
            }
        });
        Map<String, List<Entry>> byFile = new HashMap<>();
        openFiles().forEach((file, open) -> {
            List<Entry> entries = new ArrayList<>();
            String path = fileOf(file);
            String module = moduleOf(file);
            if (path.isEmpty() || byFile.containsKey(path)) {
                return; // no place on disk, or already read through its other IFile
            }
            if (kind == Kind.PROBLEMS) {
                if (!(open.model() instanceof AbstractMarkerAnnotationModel)) {
                    return; // its annotations may lack the markers: read the file from its markers
                }
                for (Diagnostic d : DiagnosticsSync.read(open.document(), open.model(), ProblemAnnotations::severityOf)) {
                    if (QuickfixEntries.atLeast(d.severity(), least)) {
                        entries.add(new Entry(open.bufnr(), path, module, d.lnum() + 1, d.col() + 1, d.severity(), d.message()));
                    }
                }
            } else {
                DiagnosticPositions.Lines lines = DiagnosticsSync.lines(open.document());
                for (MarkerRead read : markersByLocation.getOrDefault(path, List.of())) {
                    Position at = open.model() instanceof AbstractMarkerAnnotationModel model
                            ? model.getMarkerPosition(read.marker()) : null;
                    if (at == null || at.isDeleted()) {
                        entries.add(new Entry(open.bufnr(), path, module, read.line(), 0, null, read.message()));
                        continue;
                    }
                    Diagnostic d = DiagnosticPositions.toDiagnostic(
                            new DiagnosticPositions.Problem(at.getOffset(), 0, Severity.INFO, read.message()), lines);
                    entries.add(new Entry(open.bufnr(), path, module, d.lnum() + 1, d.col() + 1, null, read.message()));
                }
            }
            byFile.put(path, entries);
        });
        return byFile;
    }

    /**
     * The workspace files that have an open text editor (restored ones; an editor tab never shown since
     * Eclipse started has no document yet), each once, with its document and model. UI thread.
     */
    private static Map<IFile, OpenFile> openFiles() {
        Map<IFile, OpenFile> files = new LinkedHashMap<>();
        if (!PlatformUI.isWorkbenchRunning()) {
            return files;
        }
        EditorAttachManager manager = EditorAttachManager.getInstance();
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorReference reference : page.getEditorReferences()) {
                    IEditorPart part = reference.getEditor(false);
                    ITextEditor editor = part == null ? null : EditorAttachManager.textEditorOf(part);
                    IEditorInput input = editor == null ? null : editor.getEditorInput();
                    IFile file = input == null ? null : input.getAdapter(IFile.class);
                    if (file == null || files.containsKey(file) || editor.getDocumentProvider() == null) {
                        continue;
                    }
                    IDocument document = editor.getDocumentProvider().getDocument(input);
                    IAnnotationModel model = editor.getDocumentProvider().getAnnotationModel(input);
                    if (document != null && model != null) {
                        files.put(file, new OpenFile(document, model, manager.bufferNumberOf(document)));
                    }
                }
            }
        }
        return files;
    }

    /**
     * Runs {@code read} on the UI thread and returns its answer; an empty map when there is no UI
     * (the workbench is closing), so that every file is read from its markers.
     */
    private static Map<String, List<Entry>> onUiThread(Supplier<Map<String, List<Entry>>> read) {
        Display display = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null;
        if (display == null || display.isDisposed()) {
            return Map.of();
        }
        AtomicReference<Map<String, List<Entry>>> result = new AtomicReference<>(Map.of());
        try {
            display.syncExec(() -> {
                try {
                    result.set(read.get());
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not read the open editors' problems", e);
                }
            });
        } catch (SWTException e) { // disposed meanwhile
            LOG.log(Level.FINE, "No UI to read the open editors", e);
        }
        return result.get();
    }

    // ---- :copen --------------------------------------------------------------------------------

    /** Shows the Problems view, or the Tasks view, in the active window, as {@code :copen} does Neovim's list. */
    private static void showView(Kind kind) {
        String id = kind == Kind.PROBLEMS ? IPageLayout.ID_PROBLEM_VIEW : IPageLayout.ID_TASK_LIST;
        HostCommands.runOnUi(() -> {
            IWorkbenchWindow window = PlatformUI.isWorkbenchRunning()
                    ? PlatformUI.getWorkbench().getActiveWorkbenchWindow() : null;
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            if (page == null) {
                return;
            }
            try {
                page.showView(id);
            } catch (PartInitException e) {
                LOG.log(Level.WARNING, "Could not show the view " + id, e);
            }
        });
    }
}
