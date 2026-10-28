// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;

/**
 * Command "Neovim: Restart Neovim" (<em>Neovim → Restart Neovim...</em>, or Quick Access): asks
 * first, then stops the embedded Neovim, starts a new one and attaches it to the active editor.
 *
 * <p>Why: Neovim reads {@code init.lua} once, when it starts, and so do the plug-in's settings
 * there ({@code vim.g.neovim4eclipse_folds}, {@code vim.g.neovim4eclipse_problems}). Before this
 * command, trying a change to the config meant restarting Eclipse. It is also a way out of a
 * Neovim that is stuck.
 *
 * <p>It takes the same path as changing the Neovim executable in the preferences
 * ({@link Activator#restartNeovim()}). Nothing of Eclipse's is lost: the documents are Eclipse's,
 * and each buffer is filled again from its document when its editor attaches.
 *
 * <p>What a restart loses is less than one might think, and the question says exactly what. Neovim
 * writes its ShaDa file ({@code :help shada}) when it quits, and the new one reads it: registers
 * (so macros), marks, the jump list and the command and search history come back, as when Neovim is
 * quit and started again in a terminal. Lost: the undo history (kept in memory, unless
 * {@code 'undofile'} is on), and what was set by hand since the start ({@code :set}, {@code :map},
 * {@code :let}) and is not in {@code init.lua}. {@code RestartNeovimEndToEndTest} checks both
 * sides.
 *
 * <p>When Neovim has died on its own, <em>Attach Neovim</em> already starts a new one without asking,
 * since nothing is left to lose; this command is for a Neovim that still runs.
 */
public final class RestartNeovimHandler extends AbstractHandler {

    /** The question's title (also what a test looks for). */
    static final String TITLE = "Restart Neovim";

    /**
     * The question. Example of what it guards: {@code :set list} typed an hour ago, or the undo
     * history of a long edit, which the new Neovim does not have.
     */
    static final String QUESTION = """
            Stop Neovim and start a new one?

            Lost: the undo history, and anything set by hand since Neovim started \
            (:set, :map, :let) that your init.lua does not set.
            Kept: the text of every editor, unsaved changes included. Registers, marks \
            and history come back from Neovim's ShaDa file, as after quitting Neovim \
            in a terminal.

            Use it after changing your init.lua.""";

    @Override
    public Object execute(ExecutionEvent event) {
        Shell shell = HandlerUtil.getActiveShell(event);
        MessageDialog dialog = new MessageDialog(shell, TITLE, null, QUESTION, MessageDialog.QUESTION,
                1, "&Restart", "Cancel"); // Cancel is the default button: Enter does not lose state
        if (dialog.open() == 0) {
            Activator.getDefault().restartNeovim();
        }
        return null;
    }
}
