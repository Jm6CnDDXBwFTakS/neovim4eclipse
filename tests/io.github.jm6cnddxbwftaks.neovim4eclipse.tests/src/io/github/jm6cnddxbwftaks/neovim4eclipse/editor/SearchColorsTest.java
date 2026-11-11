// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OverlayColors;

/**
 * The search match color (editor/SearchColors): declared in {@code plugin.xml}, so Eclipse lists it
 * under Colors and Fonts with the light themes' amber, and a change there reaches the editor's
 * highlights at once, and a dark editor gets the dark amber without Eclipse's dark theme. The dark
 * theme's own value comes from a stylesheet, which only the gallery's dark run starts
 * ({@code SearchGalleryTest}, card {@code sh-colors}); {@code SearchMatchColorFilesTest} checks
 * the stylesheet's selector and value.
 */
class SearchColorsTest {

    private static final long TIMEOUT_MS = TestWait.millis();

    private final ColorRegistry colors = PlatformUI.getWorkbench().getThemeManager().getCurrentTheme().getColorRegistry();

    private Shell shell;
    private SearchColors searchColors;
    private RGB own;

    @AfterEach
    void restore() {
        if (searchColors != null) {
            searchColors.dispose();
        }
        if (own != null) {
            colors.put(SearchColors.COLOR_ID, own);
        }
        if (shell != null) {
            shell.dispose();
        }
    }

    /** The color is in the theme's registry, with plugin.xml's value: the light themes' amber. */
    @Test
    void theColorIsDeclaredWithTheAmberDefault() {
        RGB declared = colors.getRGB(SearchColors.COLOR_ID);
        assertNotNull(declared, "no color definition " + SearchColors.COLOR_ID + " in the theme (plugin.xml?)");
        assertEquals(SearchColors.DEFAULT, declared, "the test Eclipse runs the light theme");
    }

    /**
     * An editor that sets its colors on the widget itself, without a preference (a TextMate theme
     * in the Generic Editor), gets the amber for them at the next paint.
     */
    @Test
    void colorsSetStraightOnTheWidgetAreSeenAtTheNextPaint() {
        shell = new Shell(PlatformUI.getWorkbench().getDisplay());
        shell.setSize(300, 200);
        StyledText widget = new StyledText(shell, SWT.NONE);
        widget.setBounds(0, 0, 300, 200);
        widget.setText("fox");
        shell.open();
        AtomicReference<RGB> match = new AtomicReference<>();
        searchColors = new SearchColors(widget, (m, b) -> match.set(m.getRGB()));
        assertEquals(SearchColors.DEFAULT, match.get(), "a light widget: the light amber");

        widget.setBackground(new Color(30, 30, 30));
        // Paint now rather than wait for the system to: a hidden or locked screen may never paint.
        widget.redraw();
        widget.update();
        int dark = OverlayColors.SEARCH_MATCH_DARK;
        RGB darkAmber = new RGB((dark >> 16) & 0xFF, (dark >> 8) & 0xFF, dark & 0xFF);
        waitUntil("the dark amber after the widget turned dark", TIMEOUT_MS, () -> darkAmber.equals(match.get()));
    }

    /** After dispose, an update already queued hands on nothing. */
    @Test
    void anUpdateQueuedBeforeDisposeDoesNothing() {
        shell = new Shell(PlatformUI.getWorkbench().getDisplay());
        StyledText widget = new StyledText(shell, SWT.NONE);
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        searchColors = new SearchColors(widget, (m, b) -> calls.set(calls.get() + 1));
        own = colors.getRGB(SearchColors.COLOR_ID);
        colors.put(SearchColors.COLOR_ID, new RGB(170, 230, 200)); // queues an update (asyncExec)
        searchColors.dispose();
        searchColors = null;
        while (widget.getDisplay().readAndDispatch()) {
            // run the queued update, which must do nothing
        }
        assertEquals(1, calls.get(), "only the constructor's colors");
    }

    /** A change in Colors and Fonts (the registry's new value) is handed on, with a box toward the text. */
    @Test
    void aChangeOfTheColorReachesTheHighlights() {
        shell = new Shell(PlatformUI.getWorkbench().getDisplay());
        StyledText widget = new StyledText(shell, SWT.NONE);
        AtomicReference<RGB> match = new AtomicReference<>();
        AtomicReference<RGB> box = new AtomicReference<>();
        searchColors = new SearchColors(widget, (m, b) -> {
            match.set(m.getRGB());
            box.set(b.getRGB());
        });
        assertEquals(colors.getRGB(SearchColors.COLOR_ID), match.get(), "the color now, at once");

        own = colors.getRGB(SearchColors.COLOR_ID);
        RGB green = new RGB(170, 230, 200);
        colors.put(SearchColors.COLOR_ID, green);
        waitUntil("the new color handed on", TIMEOUT_MS, () -> green.equals(match.get()));
        assertEquals(searchColors.box(searchColors.match()).getRGB(), box.get(), "the box follows the new color");
    }
}
