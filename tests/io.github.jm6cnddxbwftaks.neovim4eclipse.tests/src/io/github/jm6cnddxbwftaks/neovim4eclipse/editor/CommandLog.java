// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

/**
 * The workbench commands run while it is open, by id, in order: for a test that checks a key ran
 * Eclipse's command (Content Assist, for example), or that it did not run one. Use it in
 * try-with-resources, which removes the listener again. A command asked for that no handler took
 * counts too.
 *
 * <p>Example: {@code try (CommandLog log = new CommandLog()) { ...; assertTrue(log.run.contains(id)); }}
 */
final class CommandLog implements AutoCloseable {
    final List<String> run = new CopyOnWriteArrayList<>();
    private final ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
    private final IExecutionListener listener = new IExecutionListener() {
        @Override
        public void preExecute(String commandId, ExecutionEvent event) {
            run.add(commandId);
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception) {
            run.add(commandId);
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception) {
        }

        @Override
        public void postExecuteSuccess(String commandId, Object returnValue) {
        }
    };

    CommandLog() {
        commands.addExecutionListener(listener);
    }

    @Override
    public void close() {
        commands.removeExecutionListener(listener);
    }
}
