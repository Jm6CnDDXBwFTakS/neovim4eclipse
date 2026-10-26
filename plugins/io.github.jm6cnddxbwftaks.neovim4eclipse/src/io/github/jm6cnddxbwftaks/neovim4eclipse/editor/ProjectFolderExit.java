// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.time.Duration;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;

/**
 * Moves Neovim out of a project's folder just before Eclipse deletes or closes the project.
 *
 * <h2>Why</h2>
 * An attached editor sets Neovim's window folder to its project's root ({@code :lcd}, so that
 * {@code :e} completion and {@code :!} start there), and Neovim's process then works in that folder.
 * Windows does not delete a folder a process works in: deleting the project failed with "The
 * process cannot access the file because it is being used by another process", even after its
 * editors were closed (the window keeps the folder of the last editor attached). Linux and macOS
 * delete it anyway; moving out there too keeps Neovim from working in a folder that is gone.
 *
 * <h2>How</h2>
 * A workspace listener for {@link IResourceChangeEvent#PRE_DELETE} and
 * {@link IResourceChangeEvent#PRE_CLOSE}, which Eclipse sends for a project before touching its
 * files, on the thread that deletes it. It asks Neovim to leave the folder
 * ({@link NeovimSession#leaveFolder}) and waits for the answer, at most {@link #TIMEOUT}: the delete
 * must not start before Neovim has moved, and a Neovim that does not answer must not hang Eclipse.
 * Example: the window works in {@code /ws/app}; deleting project {@code app} moves it to {@code /ws}.
 * The next editor attached sets its own project's folder again.
 */
public final class ProjectFolderExit implements IResourceChangeListener {

    private static final Logger LOG = Logger.getLogger(ProjectFolderExit.class.getName());

    /** The longest a delete waits for Neovim. Neovim answers in milliseconds when it is idle. */
    static final Duration TIMEOUT = Duration.ofSeconds(1);

    /** The events this listener needs, for {@link IWorkspace#addResourceChangeListener(IResourceChangeListener, int)}. */
    static final int EVENTS = IResourceChangeEvent.PRE_DELETE | IResourceChangeEvent.PRE_CLOSE;

    /** The running session, or {@code null}: a listener must never start Neovim. */
    private final Supplier<NeovimSession> session;

    ProjectFolderExit(Supplier<NeovimSession> session) {
        this.session = session;
    }

    /**
     * Starts listening to the workspace (from {@code Activator.start}); undo with {@link #uninstall}.
     *
     * @param session the running session, or {@code null} when none runs
     * @return the listener, or {@code null} when there is no workspace (logged)
     */
    public static ProjectFolderExit install(Supplier<NeovimSession> session) {
        try {
            ProjectFolderExit listener = new ProjectFolderExit(session);
            ResourcesPlugin.getWorkspace().addResourceChangeListener(listener, EVENTS);
            return listener;
        } catch (IllegalStateException e) { // no workspace: Eclipse started without one
            LOG.log(Level.WARNING, "No workspace: Neovim will not leave a project folder before it is deleted", e);
            return null;
        }
    }

    /** Stops listening; does nothing for {@code null}. */
    public static void uninstall(ProjectFolderExit listener) {
        if (listener == null) {
            return;
        }
        try {
            ResourcesPlugin.getWorkspace().removeResourceChangeListener(listener);
        } catch (IllegalStateException e) {
            LOG.log(Level.FINE, "The workspace is already gone", e);
        }
    }

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        if (!(event.getResource() instanceof IProject project)) {
            return;
        }
        IPath location = project.getLocation(); // null for a project whose folder is not on disk
        NeovimSession neovim = session.get();
        if (location == null || neovim == null || !neovim.isAlive()) {
            return;
        }
        String folder = location.toOSString();
        int moved = neovim.leaveFolder(folder, TIMEOUT);
        if (moved > 0) {
            LOG.fine(() -> "Neovim left " + folder + " before Eclipse " + (event.getType() == IResourceChangeEvent.PRE_DELETE
                    ? "deleted" : "closed") + " project " + project.getName() + " (" + moved + " working folders)");
        } else if (moved < 0) {
            LOG.log(Level.WARNING, "Neovim may still work in " + folder + "; on Windows, deleting project "
                    + project.getName() + " can then fail");
        }
    }
}
