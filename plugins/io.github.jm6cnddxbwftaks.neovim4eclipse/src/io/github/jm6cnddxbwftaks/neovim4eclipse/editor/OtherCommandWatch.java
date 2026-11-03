// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.ui.commands.ICommandService;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * Tells when any Eclipse command other than the plug-in's own {@code Ctrl} key commands is about to
 * run. A key Eclipse binds to a command never reaches the editor's key listener: the workbench takes
 * it first. So this is how the editor learns that such a key came. Example: after {@code Ctrl-X},
 * {@code Ctrl-N} runs Word Completion through its binding; the pending {@code Ctrl-X} must end
 * there ({@code KeyRouter#cancelCtrlX}).
 *
 * <p>The command service is the workbench's, so commands run in other editors count too; for a
 * one-key prefix that is harmless. UI thread (commands run there).
 */
final class OtherCommandWatch implements IExecutionListener {

    private final ICommandService commands;
    private final Runnable onOtherCommand;

    /**
     * @param commands       the workbench's command service, or {@code null} for none (nothing to watch)
     * @param onOtherCommand run before each such command
     */
    OtherCommandWatch(ICommandService commands, Runnable onOtherCommand) {
        this.commands = commands;
        this.onOtherCommand = onOtherCommand;
        if (commands != null) {
            commands.addExecutionListener(this);
        }
    }

    void dispose() {
        if (commands != null) {
            commands.removeExecutionListener(this);
        }
    }

    @Override
    public void preExecute(String commandId, ExecutionEvent event) {
        if (!VimCtrlKeys.isCtrlKeyCommand(commandId)) {
            onOtherCommand.run();
        }
    }

    @Override
    public void notHandled(String commandId, NotHandledException exception) {
        // nothing ran
    }

    @Override
    public void postExecuteFailure(String commandId, ExecutionException exception) {
        // preExecute already told
    }

    @Override
    public void postExecuteSuccess(String commandId, Object returnValue) {
        // preExecute already told
    }
}
