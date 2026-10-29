// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
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
 * removes, opens, closes or moves a project, or adds or removes a linked folder (a
 * {@link IResourceChangeEvent#POST_CHANGE} listener: {@link #install}). Changing a link's target in
 * place is not seen until one of those; the cost of watching every change for it would be a walk of
 * the whole workspace each time.
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

    private ProjectFolders(Supplier<NeovimSession> session) {
        this.session = session;
    }

    /**
     * Starts watching the workspace (from {@code Activator.start}); undo with {@link #uninstall}.
     *
     * @return the listener, or {@code null} when there is no workspace (logged)
     */
    public static ProjectFolders install(Supplier<NeovimSession> session) {
        try {
            ProjectFolders listener = new ProjectFolders(session);
            ResourcesPlugin.getWorkspace().addResourceChangeListener(listener, IResourceChangeEvent.POST_CHANGE);
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
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(listener);
        } catch (IllegalStateException e) {
            LOG.log(Level.FINE, "The workspace is already gone", e);
        }
        SENDER.cancel(); // a run already started finishes; its session check makes it harmless
    }

    /** Sends the folders to {@code neovim} in the background (see the class comment). Any thread. */
    static void sendTo(NeovimSession neovim) {
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

    /** The folders now (see the class comment), in the workspace's order, each once. */
    static List<String> current() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        Set<String> folders = new LinkedHashSet<>();
        Set<IPath> linked = new HashSet<>();
        for (IProject project : root.getProjects()) {
            IPath location = project.isOpen() ? project.getLocation() : null;
            if (location == null) {
                continue;
            }
            folders.add(location.toOSString());
            try {
                project.accept(proxy -> {
                    if (proxy.isLinked() && proxy.getType() == IResource.FOLDER) {
                        IResource folder = proxy.requestResource();
                        IPath target = folder.getLocation(); // null for a link to a missing variable
                        if (target != null) {
                            folders.add(target.toOSString());
                            linked.add(folder.getFullPath());
                        }
                    }
                    return proxy.getType() != IResource.FILE;
                }, IResource.NONE);
            } catch (CoreException e) {
                LOG.log(Level.FINE, "Could not look for linked folders in " + project.getName(), e);
            }
        }
        linkedFolders = Set.copyOf(linked);
        return new ArrayList<>(folders);
    }

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        IResourceDelta delta = event.getDelta();
        NeovimSession neovim = session.get();
        if (delta == null || neovim == null || !neovim.isAlive()) {
            return;
        }
        if (changesFolders(delta)) {
            sendTo(neovim);
        }
    }

    /**
     * Whether a change adds, removes, opens, closes or moves a project, or adds or removes a linked
     * folder. Only folders are walked: a file's change cannot be one of those.
     */
    private static boolean changesFolders(IResourceDelta delta) {
        boolean[] found = { false };
        Set<IPath> known = linkedFolders;
        try {
            delta.accept(d -> {
                if (found[0]) {
                    return false;
                }
                IResource resource = d.getResource();
                int kind = d.getKind();
                switch (resource.getType()) {
                    case IResource.ROOT -> {
                        return true;
                    }
                    case IResource.PROJECT -> {
                        int flags = IResourceDelta.OPEN | IResourceDelta.MOVED_FROM | IResourceDelta.MOVED_TO;
                        if (kind != IResourceDelta.CHANGED || (d.getFlags() & flags) != 0) {
                            found[0] = true;
                            return false;
                        }
                        return true;
                    }
                    case IResource.FOLDER -> {
                        if ((kind == IResourceDelta.ADDED && resource.isLinked())
                                || (kind == IResourceDelta.REMOVED && known.contains(resource.getFullPath()))) {
                            found[0] = true;
                            return false;
                        }
                        return true;
                    }
                    default -> {
                        return false;
                    }
                }
            });
        } catch (CoreException e) {
            LOG.log(Level.FINE, "Could not read a workspace change", e);
        }
        return found[0];
    }
}
