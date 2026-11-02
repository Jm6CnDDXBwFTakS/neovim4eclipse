// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.GetNeovim;

/**
 * Command "Neovim: Get Neovim" (the Neovim menu, while the plug-in has no current Neovim of its own,
 * and Quick Access): opens <i>Get Neovim</i>'s confirmation ({@link GetNeovim}).
 */
public final class GetNeovimHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        GetNeovim.open(HandlerUtil.getActiveShell(event));
        return null;
    }
}
