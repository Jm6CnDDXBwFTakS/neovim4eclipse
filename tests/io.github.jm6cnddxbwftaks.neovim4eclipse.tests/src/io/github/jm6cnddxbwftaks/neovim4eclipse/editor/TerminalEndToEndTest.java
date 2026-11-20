// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.EclipseTerminal;

/**
 * {@code :terminal} in an Eclipse without the Terminal, from Neovim's command line to the message: the
 * test Eclipse has none, so the command opens nothing and says how to get one
 * ({@link EclipseTerminal#NO_TERMINAL}). The way through is the real one: Neovim's command line
 * hands the line to Eclipse ({@code LuaScripts.HOST_COMMANDS}), which asks the workbench whether a
 * Terminal command is defined and handled ({@link EclipseTerminals}). That the Terminal opens where it is
 * installed is a manual check (docs/MANUAL_CHECKS.md, card {@code tm-open}); which id is taken is
 * {@code EclipseTerminalTest}, and that no program starts in Neovim is {@code terminal_spec.lua}.
 */
class TerminalEndToEndTest extends EndToEndTestBase {

    @Test
    void withoutTheTerminalTerminalSaysHowToGetOne() {
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        assumeTrue(EclipseTerminal.commandId(id -> commands.getCommand(id).isDefined(), id -> commands.getCommand(id).isHandled()).isEmpty(),
                "this test Eclipse has a Terminal; the message is for one without");
        runCommandLine(widget, "terminal make");

        expectShown(EclipseTerminal.NO_TERMINAL);
    }
}
