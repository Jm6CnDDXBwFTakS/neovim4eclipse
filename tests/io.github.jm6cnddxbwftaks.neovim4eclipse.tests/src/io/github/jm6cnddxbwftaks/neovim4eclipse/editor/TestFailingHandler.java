// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

/**
 * The handler of the test command {@value #COMMAND} ({@code fragment.xml}): it always fails, as a
 * real command can while running (a search that cannot start). For the message an IDE key shows then
 * ({@code IdeKeysEndToEndTest}).
 */
public class TestFailingHandler extends AbstractHandler {

    static final String COMMAND = "io.github.jm6cnddxbwftaks.neovim4eclipse.tests.commands.failing";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        throw new ExecutionException("boom");
    }
}
