// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Command "Neovim: Welcome" (the Neovim menu's <em>Welcome...</em>, or Quick Access): shows the
 * welcome note again, whatever was shown before and whether or not it is turned off in the
 * preferences. The automatic one is {@link WelcomeNotice#afterStart()}.
 */
public final class WelcomeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        WelcomeNotice.showWelcome(HandlerUtil.getActiveShell(event));
        return null;
    }
}
