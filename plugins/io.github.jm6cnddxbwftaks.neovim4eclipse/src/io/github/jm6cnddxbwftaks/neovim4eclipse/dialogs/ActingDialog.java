// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs;

import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

/**
 * An information {@link MessageDialog} that runs a {@link ButtonAction} for the pressed button: the
 * update note after a new minor version ({@code editor/WelcomeNotice}).
 *
 * <p>Acting inside {@link #buttonPressed}, instead of on {@code open()}'s return value, makes the
 * dialog work the same whether or not it blocks: the startup path blocks, tests open it with
 * {@code setBlockOnOpen(false)}. The last button is the default, so Enter just dismisses the note.
 * Example: {@code new ActingDialog(shell, "Neovim for Eclipse", text, List.of("What's New", "Close"),
 * button -> ...).open()}.
 */
public class ActingDialog extends MessageDialog {

    private final ButtonAction action;

    public ActingDialog(Shell shell, String title, String message, List<String> buttons, ButtonAction action) {
        super(shell, title, null, message, MessageDialog.INFORMATION, buttons.size() - 1, buttons.toArray(String[]::new));
        this.action = action;
    }

    @Override
    protected void buttonPressed(int buttonId) {
        super.buttonPressed(buttonId); // sets the return code and closes
        action.pressed(buttonId);
    }
}
