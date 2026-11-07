// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IPathVariableChangeListener;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;

/**
 * Tells Neovim where the open projects' files are, so that it never reads one by itself
 * (docs/design/BUFFER_NUMBERS.md, 2.3; {@code LuaScripts.PROJECT_FILES}).
 *
 * <h2>Why</h2>
 * When Neovim reads a file ({@code gf}, {@code 'A}, a quickfix entry), filetype detection runs, and
 * with it the filetype plug-ins and the user's {@code FileType} autocommands, which may start a
 * language server inside Eclipse's Neovim. For a project's file the plug-in reads the text instead,
 * and Eclipse opens the file's editor. Neovim decides which buffers are a project's from the folders
 * sent here.
 *
 * <h2>Which folders</h2>
 * Each open project's location, and the location of every linked folder in it (a folder that lives
 * somewhere else on disk: Project Properties, "Link to alternate location"; links may sit at any
 * depth). Example: project {@code app} at {@code /ws/app}, with {@code app/ext} linked to
 * {@code /data/ext}, sends {@code /ws/app} and {@code /data/ext}. A closed project, a project or
 * virtual folder with no location on disk, and a linked single file are left out (a linked file's
 * target is then read by Neovim as before).
 *
 * <h2>When</h2>
 * At the first attach of each Neovim ({@link #sendTo}), and again after a workspace change that adds,
 * removes, opens, closes or moves a project, adds or removes a linked folder, or changes a project's
 * {@code .project} file (a {@link IResourceChangeEvent#POST_CHANGE} listener: {@link #install}). The
 * {@code .project} file holds the links' targets and the project's path variables, so a link moved
 * to another target in place (Properties &gt; Resource &gt; Edit) is seen too.
 *
 * <p>A link's target may also be given through a workspace path variable ({@code EXT/lib}, with
 * {@code EXT} set in Preferences &gt; General &gt; Workspace &gt; Linked Resources). A change of
 * that variable moves the target without any resource change at all; a path-variable listener
 * ({@link IPathVariableChangeListener}) has every project walked again then. Such changes are
 * rare, and any project's link may use the variable.
 *
 * <h2>One job sends them</h2>
 * Finding the linked folders walks each project's resources (Eclipse's in-memory tree, no disk
 * access). The walk runs in one {@link Job}, {@link #SENDER}, never on the thread that asked: a
 * workspace change is announced on the thread that made it, often the UI thread (closing a project
 * from the Project Explorer). Both the first attach and the listener only schedule that job, which
 * gives two more things for free:
 * <ul>
 *   <li><b>Order.</b> A job runs one run at a time, and each run walks the workspace as it is then,
 *       so a walk made before a change can never reach Neovim after the walk made after it (with a
 *       job per request it could: the first attach's walk, made before an import, sent after the
 *       import's walk, and Neovim would keep the older folders).</li>
 *   <li><b>Bursts.</b> Scheduling a job that is already waiting does nothing, and scheduling one
 *       that is running runs it once more afterwards. Importing 20 projects then gives a few walks,
 *       not 20.</li>
 * </ul>
 * The cost: Neovim learns of a change some milliseconds later, so a {@code gf} in the same moment
 * as an import may still be read by Neovim itself.
 *
 * <h2>Only the changed projects are walked again</h2>
 * Each project's walk is kept ({@link #WALKED}). The first attach of a Neovim walks every project
 * (the workspace may have changed while no Neovim ran); after that a workspace change marks only
 * the projects it touches ({@link #STALE}), and the next run walks those and reuses the others.
 * Example: a workspace of 200 projects, one of them closed and opened again: one walk, not 200.
 * A project whose location differs from its kept walk's is walked again too, marked or not.
 */
public final class ProjectFolders implements IResourceChangeListener {

    private static final Logger LOG = Logger.getLogger(ProjectFolders.class.getName());

    /** The running session, or {@code null}: a listener must never start Neovim. */
    private final Supplier<NeovimSession> session;
    /** The one job that walks the workspace and sends the folders (see the class comment). */
    private static final Sender SENDER = new Sender();
    /**
     * The linked folders found by the last walk, by workspace path: their removal changes the folders.
     * Written by {@link #current} in {@link #SENDER}, read by the listener.
     */
    private static volatile Set<IPath> linkedFolders = Set.of();

    /**
     * One open project's folders, as its last walk found them: its location, and its linked folders'
     * targets and workspace paths, each in the walk's order.
     */
    private record Walked(String location, List<String> targets, Set<IPath> linked) {
    }

    /** The last walk of each open project, by name. Only {@link #current} touches it (synchronized). */
    private static final Map<String, Walked> WALKED = new HashMap<>();
    /** Projects, by name, that a workspace change touched since their last walk. Any thread. */
    private static final Set<String> STALE = ConcurrentHashMap.newKeySet();
    /** Every project must be walked again: set at each first attach ({@link #sendTo}). Any thread. */
    private static final AtomicBoolean ALL_STALE = new AtomicBoolean(true);
    /** How many projects have been walked, for tests that check an untouched one is not walked again. */
    private static final AtomicInteger WALKS = new AtomicInteger();
    /**
     * The folders the last walk sent, as Eclipse records them: each open project's location and each
     * linked folder's target, spelled as the user gave it. Written by {@link #current}, read by
     * {@link #locations}.
     */
    private static volatile List<String> lastFolders = List.of();

    /**
     * A workspace path variable changed (see the class comment): every project is walked again, as
     * a link anywhere may use it. Any thread.
     */
    private final IPathVariableChangeListener variables = event -> {
        ALL_STALE.set(true);
        sendToRunning();
    };

    private ProjectFolders(Supplier<NeovimSession> session) {
        this.session = session;
    }

    /**
     * Starts watching the workspace and its path variables (from {@code Activator.start}); undo with
     * {@link #uninstall}.
     *
     * @return the listener, or {@code null} when there is no workspace (logged)
     */
    public static ProjectFolders install(Supplier<NeovimSession> session) {
        try {
            ProjectFolders listener = new ProjectFolders(session);
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            workspace.addResourceChangeListener(listener, IResourceChangeEvent.POST_CHANGE);
            workspace.getPathVariableManager().addChangeListener(listener.variables);
            return listener;
        } catch (IllegalStateException e) { // no workspace: Eclipse started without one
            LOG.log(Level.WARNING, "No workspace: Neovim will read project files itself", e);
            return null;
        }
    }

    /** Stops watching; does nothing for {@code null}. */
    public static void uninstall(ProjectFolders listener) {
        if (listener == null) {
            return;
        }
        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            workspace.removeResourceChangeListener(listener);
            workspace.getPathVariableManager().removeChangeListener(listener.variables);
        } catch (IllegalStateException e) {
            LOG.log(Level.FINE, "The workspace is already gone", e);
        }
        SENDER.cancel(); // a run already started finishes; its session check makes it harmless
    }

    /**
     * Sends the folders to {@code neovim} in the background (see the class comment), after a walk of
     * every project: a new Neovim may follow changes that no Neovim was there for. Any thread.
     */
    static void sendTo(NeovimSession neovim) {
        ALL_STALE.set(true);
        SENDER.sendTo(neovim);
    }

    /**
     * The job of the class comment. It sends to the session it was last given: after a restart of
     * Neovim the new one (the old one is no longer alive anyway).
     */
    private static final class Sender extends Job {

        private volatile NeovimSession target;

        Sender() {
            super("Neovim: project folders");
            setSystem(true); // no progress shown: it takes milliseconds
        }

        void sendTo(NeovimSession neovim) {
            target = neovim;
            schedule(); // waiting: nothing more; running: once more afterwards
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            send(target);
            return Status.OK_STATUS;
        }
    }

    private static void send(NeovimSession neovim) {
        if (neovim == null || !neovim.isAlive()) {
            return;
        }
        try {
            List<String> folders = current();
            LOG.fine(() -> "Project folders for Neovim: " + folders);
            neovim.setProjectFoldersAsync(folders);
        } catch (IllegalStateException e) {
            LOG.log(Level.FINE, "No workspace: no project folders for Neovim", e);
        }
    }

    /**
     * The folders now (see the class comment), in the workspace's order, each once. Walks the
     * projects marked {@link #STALE} (all of them after {@link #sendTo}) and reuses the other
     * projects' last walks. Synchronized: the job and a test may both call it.
     */
    static synchronized List<String> current() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        // Taken before the walk: a change made during it marks again and runs the job once more.
        boolean all = ALL_STALE.getAndSet(false);
        Set<String> stale = new HashSet<>();
        for (String name : STALE) {
            if (STALE.remove(name)) {
                stale.add(name);
            }
        }
        Map<String, Walked> now = new HashMap<>();
        Set<String> folders = new LinkedHashSet<>();
        Set<IPath> linked = new HashSet<>();
        for (IProject project : root.getProjects()) {
            IPath location = project.isOpen() ? project.getLocation() : null;
            if (location == null) {
                continue;
            }
            String name = project.getName();
            Walked walked = all || stale.contains(name) ? null : WALKED.get(name);
            if (walked == null || !walked.location().equals(location.toOSString())) {
                walked = walk(project, location);
            }
            now.put(name, walked);
            folders.add(walked.location());
            folders.addAll(walked.targets());
            linked.addAll(walked.linked());
        }
        WALKED.clear(); // closed, deleted and renamed projects go
        WALKED.putAll(now);
        linkedFolders = Set.copyOf(linked);
        lastFolders = List.copyOf(folders);
        return new ArrayList<>(folders);
    }

    /** Walks one open project for its linked folders (Eclipse's in-memory tree, no disk access). */
    private static Walked walk(IProject project, IPath location) {
        WALKS.incrementAndGet();
        List<String> targets = new ArrayList<>();
        Set<IPath> linked = new HashSet<>();
        try {
            project.accept(proxy -> {
                if (proxy.isLinked() && proxy.getType() == IResource.FOLDER) {
                    IResource folder = proxy.requestResource();
                    IPath target = folder.getLocation(); // null for a link to a missing variable
                    if (target != null) {
                        targets.add(target.toOSString());
                        linked.add(folder.getFullPath());
                    }
                }
                return proxy.getType() != IResource.FILE;
            }, IResource.NONE);
        } catch (CoreException e) {
            LOG.log(Level.FINE, "Could not look for linked folders in " + project.getName(), e);
        }
        return new Walked(location.toOSString(), List.copyOf(targets), Set.copyOf(linked));
    }

    /** For tests: the folders after a walk of every project, as at a first attach. */
    static List<String> currentAfterFullWalk() {
        ALL_STALE.set(true);
        return current();
    }

    /** For tests: how many project walks have been made so far. */
    static int walks() {
        return WALKS.get();
    }

    /**
     * The folders Neovim was last told about ({@link #current}), as Eclipse records them, for the
     * lookup of a path's workspace file through a link ({@code NewFiles.workspaceFileAt}). Empty
     * before the first walk. Any thread. Example: project {@code app} at {@code /ws/app}, with
     * {@code app/ext} linked to {@code /data/ext}, gives {@code [/ws/app, /data/ext]}.
     */
    static List<String> locations() {
        return lastFolders;
    }

    /**
     * Marks the projects a change touches as {@link #STALE}, with or without a Neovim (marking is
     * cheap), and has their folders sent to a running Neovim.
     */
    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        IResourceDelta delta = event.getDelta();
        if (delta == null) {
            return;
        }
        Set<String> touched = projectsWithChangedFolders(delta);
        if (touched.isEmpty()) {
            return;
        }
        STALE.addAll(touched);
        sendToRunning();
    }

    /** Has the marked projects' folders sent to a running Neovim; with none, the marks wait for one. */
    private void sendToRunning() {
        NeovimSession neovim = session.get();
        if (neovim != null && neovim.isAlive()) {
            SENDER.sendTo(neovim); // not sendTo: only the marked projects are walked again
        }
    }

    /**
     * The names of the projects whose folders a change changes: one added, removed, opened, closed
     * or moved (under its old and its new name), one in which a linked folder was added or removed,
     * or one whose {@code .project} file changed (a link's new target, a project path variable).
     * Below the project only folders are walked, and its {@code .project} file: no other file's
     * change can be one of those.
     */
    private static Set<String> projectsWithChangedFolders(IResourceDelta delta) {
        Set<String> touched = new HashSet<>();
        Set<IPath> known = linkedFolders;
        try {
            delta.accept(d -> {
                IResource resource = d.getResource();
                int kind = d.getKind();
                switch (resource.getType()) {
                    case IResource.ROOT -> {
                        return true;
                    }
                    case IResource.PROJECT -> {
                        int flags = IResourceDelta.OPEN | IResourceDelta.MOVED_FROM | IResourceDelta.MOVED_TO;
                        if (kind != IResourceDelta.CHANGED || (d.getFlags() & flags) != 0) {
                            touched.add(resource.getName());
                            return false; // walked again whole: its folders need no look
                        }
                        return true;
                    }
                    case IResource.FOLDER -> {
                        if ((kind == IResourceDelta.ADDED && resource.isLinked())
                                || (kind == IResourceDelta.REMOVED && known.contains(resource.getFullPath()))) {
                            touched.add(resource.getProject().getName());
                            return false;
                        }
                        return true;
                    }
                    case IResource.FILE -> {
                        // The project's description: its links and their targets, its path variables.
                        if (resource.getParent().getType() == IResource.PROJECT
                                && IProjectDescription.DESCRIPTION_FILE_NAME.equals(resource.getName())) {
                            touched.add(resource.getProject().getName());
                        }
                        return false;
                    }
                    default -> {
                        return false;
                    }
                }
            });
        } catch (CoreException e) {
            LOG.log(Level.FINE, "Could not read a workspace change", e);
        }
        return touched;
    }
}
