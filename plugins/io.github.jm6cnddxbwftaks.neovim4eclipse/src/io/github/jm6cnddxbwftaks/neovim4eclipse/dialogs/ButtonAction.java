// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs;

/**
 * What a button press in an {@link ActingDialog} or {@link ActingHeadedDialog} does. The argument is
 * the button's index in the labels the dialog was given, not its place on the screen (GTK shows the
 * default button rightmost). Example: {@code button -> { if (button == 0) openPreferences(); }}.
 */
@FunctionalInterface
public interface ButtonAction {

    /** Runs after the dialog has closed, on the UI thread. */
    void pressed(int button);
}
