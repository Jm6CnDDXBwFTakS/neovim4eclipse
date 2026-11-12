// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.lang.ref.WeakReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.sync.RepairReporting;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.RepairReport;

/**
 * The editor's part of a repair's report ({@code sync/TextRepairs}): what the file is, the
 * versions, and the status-bar message that tells the user.
 *
 * <h2>Why tell the user, once</h2>
 * A repair means Neovim had a stale copy for a while, and what Neovim did meanwhile may have gone
 * wrong: a yank of a line the editor never showed, an edit a line off, which is in the document now.
 * The repair puts Neovim right from here on; only the user can look at the last change. The message
 * goes to the window's status bar, where it stays until the next message, not into a dialog: the
 * work goes on. Once per Neovim session, so that a cause that repeats does not keep interrupting;
 * the Error Log has the details of the first repair of each document.
 */
final class RepairNotice implements RepairReporting {

    /** What the status bar says. */
    static final String MESSAGE = "Neovim's and Eclipse's copies of this file had drifted apart and were made"
            + " equal again. Check your last change; the Error Log has details to report.";

    private static final Logger LOG = Logger.getLogger(RepairNotice.class.getName());

    /** The session the user was told about last; a new Neovim (after a restart) tells again. UI thread only. */
    private static WeakReference<NeovimSession> told = new WeakReference<>(null);

    private final ITextEditor editor;
    private final NeovimSession session;
    /** The editor's kind ("Java Editor"): it does not change, unlike its file ({@code :saveas}). */
    private final String editorName;

    /** For {@code editor}, whose document {@code session} mirrors. UI thread (reads the editor's site). */
    RepairNotice(ITextEditor editor, NeovimSession session) {
        this.editor = editor;
        this.session = session;
        this.editorName = editor.getEditorSite() == null ? null : editor.getEditorSite().getRegisteredName();
    }

    /** From the editor's file now, so that a report after {@code :saveas b.java} says {@code .java}. Any thread. */
    @Override
    public String fileKind() {
        IEditorInput input = editor.getEditorInput();
        return RepairReport.fileKind(input == null ? null : input.getName(), editorName);
    }

    @Override
    public String neovimVersion() {
        return session.neovimVersion();
    }

    @Override
    public String pluginVersion() {
        Activator plugin = Activator.getDefault();
        return plugin == null ? "" : plugin.getBundle().getVersion().toString();
    }

    /** Shows {@link #MESSAGE} in the editor window's status bar, the first time for this session. Any thread. */
    @Override
    public void reported() {
        SwtUiExecutor.INSTANCE.asyncExec(() -> {
            if (told.get() == session) {
                return;
            }
            told = new WeakReference<>(session);
            try {
                IStatusLineManager status = editor.getEditorSite().getActionBars().getStatusLineManager();
                status.setErrorMessage(MESSAGE);
            } catch (RuntimeException e) {
                // The editor closed meanwhile: the Error Log still has the report.
                LOG.log(Level.FINE, "could not show the repair notice", e);
            }
        });
    }
}
