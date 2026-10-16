// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.HostExCommand;

/**
 * Maps Neovim write/quit semantics onto Eclipse save/close.
 *
 * <p>The embedded buffer is a scratch buffer with no file, so Neovim's own {@code :w} fails
 * (E382). Those commands are therefore handled by the host: the IDE owns the real file. Which commands count is decided by {@link HostExCommand}.
 */
public final class EclipseFileCommands {

    private static final Logger LOG = Logger.getLogger(EclipseFileCommands.class.getName());

    private EclipseFileCommands() {
    }

    /**
     * @return {@code true} if the ex command was handled here and must not reach Neovim
     */
    public static boolean tryHandleEx(String raw, ITextEditor editor) {
        if (editor == null) {
            return false;
        }
        HostExCommand cmd = HostExCommand.parse(raw);
        LOG.fine(() -> "tryHandleEx [" + raw + "] -> " + cmd);
        switch (cmd.kind()) {
            case WRITE:
                runOnUi(() -> save(editor));
                return true;
            case QUIT:
                runOnUi(() -> close(editor, cmd.force()));
                return true;
            case WRITE_QUIT:
                runOnUi(() -> {
                    if (save(editor) || cmd.force()) {
                        close(editor, true);
                    }
                });
                return true;
            default:
                return false;
        }
    }

    /** {@code ZZ}: write if needed, then quit. */
    public static void zz(ITextEditor editor) {
        runOnUi(() -> {
            save(editor);
            close(editor, true);
        });
    }

    /** {@code ZQ}: quit without writing. */
    public static void zq(ITextEditor editor) {
        runOnUi(() -> close(editor, true));
    }

    /** @return {@code true} if the editor is clean afterward */
    static boolean save(ITextEditor editor) {
        try {
            IEditorPart part = EditorAttachManager.getInstance().workbenchPartOf(editor);
            if (!part.isDirty()) {
                return true;
            }
            part.doSave(new NullProgressMonitor());
            return !part.isDirty();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Eclipse save failed", e);
            return false;
        }
    }

    /**
     * @param force {@code true} closes without prompting to save (discarding changes);
     *              {@code false} lets Eclipse ask
     */
    static void close(ITextEditor editor, boolean force) {
        try {
            IWorkbenchPage page = editor.getEditorSite().getPage();
            if (page != null) {
                page.closeEditor(EditorAttachManager.getInstance().workbenchPartOf(editor), !force);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Eclipse close failed", e);
        }
    }

    private static void runOnUi(Runnable r) {
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
