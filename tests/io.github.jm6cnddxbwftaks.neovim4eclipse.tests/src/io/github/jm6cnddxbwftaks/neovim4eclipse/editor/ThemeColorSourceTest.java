// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * The search match color as Eclipse holds it ({@link ThemeColorSource}): declared in
 * {@code plugin.xml}, so Eclipse lists it under Colors and Fonts with the light themes' amber, and a
 * change there is heard until the source stops listening. What the highlights make of the color is
 * {@code SearchColorsTest}'s (tests/swt). The dark theme's own value comes from a stylesheet, which
 * only the gallery's dark run starts ({@code SearchGalleryTest}, card {@code sh-colors});
 * {@code SearchMatchColorFilesTest} checks the stylesheet's selector and value.
 */
class ThemeColorSourceTest {

    private final ColorRegistry colors = PlatformUI.getWorkbench().getThemeManager().getCurrentTheme().getColorRegistry();

    private RGB own;

    @AfterEach
    void restore() {
        if (own != null) {
            colors.put(SearchColors.COLOR_ID, own);
        }
    }

    /** The color is in the theme's registry, with plugin.xml's value: the light themes' amber. */
    @Test
    void theColorIsDeclaredWithTheAmberDefault() {
        RGB declared = colors.getRGB(SearchColors.COLOR_ID);
        assertNotNull(declared, "no color definition " + SearchColors.COLOR_ID + " in the theme (plugin.xml?)");
        assertEquals(SearchColors.DEFAULT, declared, "the test Eclipse runs the light theme");
        assertEquals(declared, new ThemeColorSource().chosen());
    }

    /** A change in Colors and Fonts (the registry's new value) is heard, and no longer once stopped. */
    @Test
    void aChangeOfTheColorIsHeardUntilStopped() {
        ThemeColorSource source = new ThemeColorSource();
        AtomicInteger heard = new AtomicInteger();
        Runnable stop = source.listen(heard::incrementAndGet);
        own = colors.getRGB(SearchColors.COLOR_ID);
        RGB green = new RGB(170, 230, 200);

        colors.put(SearchColors.COLOR_ID, green);
        UiTestSupport.waitUntil("the change heard", TestWait.millis(), () -> heard.get() >= 1);
        assertEquals(green, source.chosen());

        stop.run();
        int before = heard.get();
        colors.put(SearchColors.COLOR_ID, own);
        UiTestSupport.runUntilIdle(TestWait.millis()); // what the change queued, if anything
        assertEquals(before, heard.get(), "nothing after stop");
    }
}
