// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * The Neovim view's decisions apart from SWT, so that they are unit-tested: how many cells a view
 * of so many pixels holds, and the view's title.
 */
public final class ScreenInput {

    /** The start of every title of the Neovim view; what it shows follows ({@link #title}). */
    public static final String VIEW_NAME = "Neovim";

    private ScreenInput() {
    }

    /**
     * How many whole cells of {@code cell} pixels fit in {@code pixels}, at least one: Neovim needs
     * a screen of one row and one column at the smallest. Example: 803 pixels of 8-pixel cells
     * hold 100 columns; a view squeezed to nothing still asks for one.
     */
    public static int cells(int pixels, int cell) {
        return Math.max(1, pixels / Math.max(1, cell));
    }

    /**
     * The view's title for what it shows: {@code "Neovim: checkhealth"}, {@code "Neovim: messages"};
     * {@code "Neovim"} alone for nothing yet.
     */
    public static String title(String shows) {
        return shows == null || shows.isBlank() ? VIEW_NAME : VIEW_NAME + ": " + shows;
    }
}
