// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

/**
 * The plug-in's own dialog layout, shared by {@code editor} (the welcome, the Restart Neovim
 * question) and {@code prefs} (Get Neovim), so the plug-in's longer dialogs look alike:
 * {@link io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs.HeadedMessageDialog} lays out a
 * {@link io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage} (an intro, headed points, a
 * closing line).
 *
 * <p>Why a package of its own: both {@code editor} and {@code prefs} need it, and {@code editor}
 * already uses {@code prefs}, so putting it in {@code editor} would make the two use each other. It
 * uses SWT and JFace only, no workbench, so {@code tests/swt} opens it without starting Eclipse. The
 * words stay in {@code util} (for example {@code util/GetNeovimText}), where the unit tests read them.
 */
package io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs;
