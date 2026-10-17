// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.HostExCommand;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.TabNavigation;

/**
 * Carries out the {@code :} commands that belong to the IDE (see {@link HostExCommand} for the
 * list): files through {@link EclipseFileCommands}, tabs through {@link EditorTabs}.
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
        HostExCommand cmd = HostExCommand.parse(raw);
        LOG.fine(() -> "tryHandleEx [" + raw + "] -> " + cmd);
        boolean force = cmd.force();
        switch (cmd.kind()) {
            case WRITE -> runOnUi(() -> EclipseFileCommands.save(editor));
            case WRITE_ALL -> runOnUi(EclipseFileCommands::saveAll);
            case QUIT -> runOnUi(() -> EclipseFileCommands.close(editor, force));
            case QUIT_ALL -> runOnUi(() -> EclipseFileCommands.closeAll(editor, force));
            case WRITE_QUIT -> runOnUi(() -> {
                if (EclipseFileCommands.save(editor) || force) {
                    EclipseFileCommands.close(editor, true);
                }
            });
            case WRITE_QUIT_ALL -> runOnUi(() -> {
                if (EclipseFileCommands.saveAll() || force) {
                    EclipseFileCommands.closeAll(editor, true);
                }
            });
            case EDIT -> runOnUi(() -> EclipseFileCommands.edit(editor, cmd.argument(), force,
                    () -> resyncNeovim(editor)));
            case TAB_NEXT -> runOnUi(() -> EditorTabs.go(editor, TabNavigation.Action.NEXT, cmd.count()));
            case TAB_PREVIOUS -> runOnUi(() -> EditorTabs.go(editor, TabNavigation.Action.PREVIOUS, cmd.count()));
            case TAB_FIRST -> runOnUi(() -> EditorTabs.go(editor, TabNavigation.Action.FIRST, 0));
            case TAB_LAST -> runOnUi(() -> EditorTabs.go(editor, TabNavigation.Action.LAST, 0));
            case NONE -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Shows a Vim-style error in the window's status line (left part), where Eclipse shows its own
     * messages. Used when a host command cannot be carried out, e.g. {@code :e} with changes.
     */
    static void showError(ITextEditor editor, String message) {
        try {
            IStatusLineManager status = editor.getEditorSite().getActionBars().getStatusLineManager();
            status.setErrorMessage(message);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not show: " + message, e);
        }
    }

    /** After Eclipse replaced the document's text (a revert), pushes it to Neovim. */
    private static void resyncNeovim(ITextEditor editor) {
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        if (controller != null && controller.getEditor() == editor) {
            controller.pushDocumentToNeovim();
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
