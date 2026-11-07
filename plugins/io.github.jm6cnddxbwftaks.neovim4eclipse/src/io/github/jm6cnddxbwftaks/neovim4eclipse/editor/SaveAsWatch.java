// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.commands.ICommandService;

/**
 * Tells whether Eclipse's <em>File &gt; Save As</em> command is running, so that the editor input
 * it sets can be told from other new inputs ({@code EditorAttachManager.rebind}).
 *
 * <p>Why: after Save As the editor shows a new document for the new file, with the same text, and
 * the old file is still there. An editor Eclipse reuses for another file ("reuse editors", search
 * results) looks the same from the editor: a new document, the old file still there, and, for two
 * empty files or two copies, the same text. Only Save As moves the Neovim buffer (its undo
 * history) to the new file. The command is Eclipse's own signal for it: its handler calls the
 * editor's {@code doSaveAs}, which sets the new input before the command ends. Example: Save As of
 * {@code a.txt} as {@code b.txt}: {@code u} in {@code b.txt} undoes what was typed in {@code a.txt};
 * {@code b.txt} opened in a reused editor of an empty {@code a.txt} gets a buffer of its own.
 *
 * <p>Commands run on the UI thread, and so does {@link #running}'s reader (the editor's
 * {@code PROP_INPUT} listener).
 */
final class SaveAsWatch implements IExecutionListener {

    private boolean running;

    /** Starts watching {@code commands}, the workbench's command service; {@code null} watches nothing. */
    static SaveAsWatch install(ICommandService commands) {
        SaveAsWatch watch = new SaveAsWatch();
        if (commands != null) {
            commands.addExecutionListener(watch);
        }
        return watch;
    }

    /** Whether Save As is running now: an input set now is the file saved under its new name. */
    boolean running() {
        return running;
    }

    @Override
    public void preExecute(String commandId, ExecutionEvent event) {
        if (IWorkbenchCommandConstants.FILE_SAVE_AS.equals(commandId)) {
            running = true;
        }
    }

    @Override
    public void postExecuteSuccess(String commandId, Object returnValue) {
        ended(commandId);
    }

    @Override
    public void postExecuteFailure(String commandId, ExecutionException exception) {
        ended(commandId);
    }

    @Override
    public void notHandled(String commandId, NotHandledException exception) {
        ended(commandId);
    }

    private void ended(String commandId) {
        if (IWorkbenchCommandConstants.FILE_SAVE_AS.equals(commandId)) {
            running = false;
        }
    }
}
