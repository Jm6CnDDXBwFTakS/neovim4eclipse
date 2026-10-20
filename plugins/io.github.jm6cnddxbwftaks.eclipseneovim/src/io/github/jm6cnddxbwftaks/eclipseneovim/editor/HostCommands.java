// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.HostCommandRunner;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.HostExCommand;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.TabNavigation;

/**
 * Carries out the {@code :} commands that belong to the IDE (see {@link HostExCommand} for the
 * list): files through {@link EclipseFileCommands}, tabs through {@link EditorTabs}.
 *
 * <p>What each command does (save before close, a failed save keeps the file open, ...) is decided
 * by {@link HostCommandRunner}, which has no Eclipse code and is unit-tested; this class only
 * connects it to the editor ({@link EditorIde}) and the UI thread.
 *
 * <p>Two places call {@link #tryHandleEx}: the inline command box ({@link CommandLineBox}) and the
 * session, when Enter is pressed on Neovim's own command line (for example while a macro is being
 * recorded). A command handled here never reaches Neovim.
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
        public boolean save(boolean createFolders) {
            return EclipseFileCommands.save(editor);
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
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        if (controller != null && controller.getEditor() == editor && !controller.isClosed()) {
            runOnUi(() -> controller.showMessage(message, true));
            return;
        }
        try {
            IStatusLineManager status = editor.getEditorSite().getActionBars().getStatusLineManager();
            status.setErrorMessage(message);
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
