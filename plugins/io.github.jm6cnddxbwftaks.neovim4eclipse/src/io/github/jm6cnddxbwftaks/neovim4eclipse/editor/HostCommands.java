// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommandRunner;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostExCommand;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TabNavigation;

/**
 * Carries out the {@code :} commands that belong to the IDE (see {@link HostExCommand} for the
 * list): files through {@link EclipseFileCommands}, tabs through {@link EditorTabs}.
 *
 * <p>What each command does (save before close, a failed save keeps the file open, ...) is decided
 * by {@link HostCommandRunner}, which has no Eclipse code and is unit-tested; this class only
 * connects it to the editor ({@link EditorIde}) and the UI thread.
 *
 * <p>The session calls {@link #tryHandleEx} when Enter is pressed on Neovim's command line, which is
 * also the line the {@code :} box shows ({@link CommandLineBox}). A command handled here is not run
 * by Neovim: its line is closed with Esc, so it stays in Neovim's history.
 */
public final class HostCommands {

    private static final Logger LOG = Logger.getLogger(HostCommands.class.getName());

    private HostCommands() {
    }

    /**
     * Runs {@code raw} in Eclipse if it is a host command. Any thread: the work is done on the UI
     * thread.
     *
     * @param raw    the command line, with or without {@code :}
     * @param editor the editor the command was typed in
     * @return {@code true} if the command was handled here and must not reach Neovim
     */
    public static boolean tryHandleEx(String raw, ITextEditor editor) {
        if (editor == null) {
            return false;
        }
        LOG.fine(() -> "tryHandleEx [" + raw + "] -> " + HostExCommand.parse(raw));
        return new HostCommandRunner(new EditorIde(editor), HostCommands::runOnUi).run(raw);
    }

    /** {@link HostCommandRunner.Ide} for one editor: Eclipse's save, close, open and tab switching. */
    private record EditorIde(ITextEditor editor) implements HostCommandRunner.Ide {
        @Override
        public boolean save(boolean createFolders, boolean evenIfUnchanged) {
            return EclipseFileCommands.save(editor, createFolders, evenIfUnchanged);
        }

        @Override
        public boolean saveAll() {
            return EclipseFileCommands.saveAll();
        }

        @Override
        public void close(boolean force) {
            EclipseFileCommands.close(editor, force);
        }

        @Override
        public void closeAll(boolean force) {
            EclipseFileCommands.closeAll(editor, force);
        }

        @Override
        public void closeBuffers(List<String> buffers, boolean force) {
            EditorAttachManager.getInstance().closeBuffers(editor, buffers, force);
        }

        @Override
        public void edit(String file, boolean force) {
            EclipseFileCommands.edit(editor, file, force);
        }

        @Override
        public void goToTab(TabNavigation.Action action, int count) {
            EditorTabs.go(editor, action, count);
        }
    }

    /**
     * Shows a Vim-style error in the editor's message line ({@link MessageLine}), where Neovim's own
     * errors appear. Used when a host command cannot be carried out, e.g. {@code :e} with changes.
     * Without Neovim attached to {@code editor}, it goes to the window's status line instead.
     */
    static void showError(ITextEditor editor, String message) {
        showMessage(editor, message, true);
    }

    /**
     * Shows a message in the editor's message line, as an error or as a normal message (such as
     * {@code "new.txt" [New]}). Without Neovim attached to {@code editor}, it goes to the window's
     * status line instead.
     */
    static void showMessage(ITextEditor editor, String message, boolean error) {
        if (editor == null) {
            return;
        }
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        if (controller != null && controller.getEditor() == editor && !controller.isClosed()) {
            runOnUi(() -> controller.showMessage(message, error));
            return;
        }
        try {
            IStatusLineManager status = editor.getEditorSite().getActionBars().getStatusLineManager();
            if (error) {
                status.setErrorMessage(message);
            } else {
                status.setMessage(message);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not show: " + message, e);
        }
    }

    /** Runs {@code r} on the UI thread: at once when already there, else posted. */
    static void runOnUi(Runnable r) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        if (display.getThread() == Thread.currentThread()) {
            r.run();
        } else {
            display.asyncExec(r);
        }
    }
}
