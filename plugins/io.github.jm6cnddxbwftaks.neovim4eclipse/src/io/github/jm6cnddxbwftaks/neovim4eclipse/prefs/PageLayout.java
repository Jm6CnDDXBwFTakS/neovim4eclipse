// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

/**
 * The spacing the plug-in's preference pages share, so that they look alike and change together.
 *
 * <p>Gaps are in <em>dialog units</em> (DLUs), which follow the dialog font: a vertical DLU is an
 * eighth of the font's height, so a gap grows with a larger font or a high-resolution screen, as the
 * rest of the dialog does. A page turns them into pixels with
 * {@code DialogPage.convertVerticalDLUsToPixels}. Example: 4 DLUs came out as 10 pixels with Linux's
 * default dialog font (screenshots, 2026-10-30). SWT's own default, a fixed 5 pixels, left the check
 * boxes, radio groups and fields crowded together.
 */
final class PageLayout {

    /** Between the rows of a page or of a group box: 4 DLUs, chosen from screenshots of 4 and 6. */
    static final int ROW_SPACING_DLUS = 4;

    /** Between two group boxes on a page: a little more than between rows. */
    static final int GROUP_SPACING_DLUS = 6;

    private PageLayout() {
    }
}
