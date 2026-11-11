// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs;

import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage;

/**
 * An information {@link HeadedMessageDialog} that runs a {@link ButtonAction} for the pressed button,
 * as {@link ActingDialog} does: the welcome ({@code editor/WelcomeNotice}). The last button is the
 * default.
 */
public class ActingHeadedDialog extends HeadedMessageDialog {

    private final ButtonAction action;

    public ActingHeadedDialog(Shell shell, String title, HeadedMessage message, List<String> buttons, ButtonAction action) {
        super(shell, title, MessageDialog.INFORMATION, message, buttons.size() - 1, buttons.toArray(String[]::new));
        this.action = action;
    }

    @Override
    protected void buttonPressed(int buttonId) {
        super.buttonPressed(buttonId); // sets the return code and closes
        action.pressed(buttonId);
    }
}
