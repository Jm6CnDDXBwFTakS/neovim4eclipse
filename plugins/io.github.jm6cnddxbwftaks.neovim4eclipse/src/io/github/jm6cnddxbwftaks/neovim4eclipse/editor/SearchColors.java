// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.BiConsumer;

import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.IThemeManager;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OverlayColors;

/**
 * The colors of the search highlights: the plug-in's own <i>Search match</i> color, and for the
 * box around the match under the caret the same color halfway toward the text
 * ({@link OverlayColors#searchBox}).
 *
 * <h2>Where the color comes from</h2>
 * A color definition in {@code plugin.xml} ({@value #COLOR_ID}), which Eclipse lists under
 * <i>Window &gt; Preferences &gt; General &gt; Appearance &gt; Colors and Fonts &gt; Neovim for
 * Eclipse &gt; Search match</i>, where a user changes it. Its default is an amber, 255,214,102, as
 * Vim users know {@code hlsearch}; Eclipse's dark theme gets a darker amber, 110,88,24, from the
 * plug-in's stylesheet for that theme ({@code css/e4-dark_neovim4eclipse.css}), the way JDT gives
 * its syntax colors a dark default. The value is read from the current theme's
 * {@link ColorRegistry}, which holds the default, the theme's value and the user's choice in that
 * order. Neovim's {@code Search} highlight group is not used: its colors are made for Neovim's own
 * screen, not for the editor's theme.
 *
 * <p>Before 0.2.7 the matches took Eclipse's <i>Search Results</i> annotation color. A color of
 * its own changes the matches without changing Eclipse's own search marks, and its default could
 * differ from that lavender, which a dark theme turns into a gray that matches barely show on.
 *
 * <p>A change of the color, or of the theme, reaches the editor at once through the theme
 * manager's listener. UI thread, but for that listener, which may be called on another.
 */
final class SearchColors {

    /** The color definition's id in {@code plugin.xml}, and the key of a user's choice. */
    static final String COLOR_ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.searchMatch";

    /** The light default, as in {@code plugin.xml}: used where no theme registry answers. */
    static final RGB DEFAULT = new RGB(255, 214, 102);

    private final StyledText widget;
    private final BiConsumer<Color, Color> apply;
    private final IThemeManager themes = PlatformUI.getWorkbench().getThemeManager();
    /**
     * Hears the theme: a change of this color, or another theme. Either may happen on any thread
     * (a preference import, a Job), and the colors go to the widget, so the work hops to the
     * widget's UI thread.
     */
    private final IPropertyChangeListener listener = this::onThemeChange;

    /**
     * Hands {@code apply} the match and box colors now and after every change of the color or the
     * theme, until {@link #dispose}.
     */
    SearchColors(StyledText widget, BiConsumer<Color, Color> apply) {
        this.widget = widget;
        this.apply = apply;
        themes.addPropertyChangeListener(listener);
        update();
    }

    /** The match color now. */
    Color match() {
        return new Color(rgb());
    }

    /** The box color for {@code match}, toward the widget's text color. */
    Color box(Color match) {
        RGB fg = widget.getForeground().getRGB();
        int mixed = OverlayColors.searchBox(toInt(match.getRGB()), toInt(fg));
        return new Color((mixed >> 16) & 0xFF, (mixed >> 8) & 0xFF, mixed & 0xFF);
    }

    private void onThemeChange(PropertyChangeEvent event) {
        String property = event.getProperty();
        if ((COLOR_ID.equals(property) || IThemeManager.CHANGE_CURRENT_THEME.equals(property)) && !widget.isDisposed()) {
            widget.getDisplay().asyncExec(this::update);
        }
    }

    void dispose() {
        themes.removePropertyChangeListener(listener);
    }

    private void update() {
        if (widget.isDisposed()) {
            return;
        }
        Color match = match();
        apply.accept(match, box(match));
    }

    private RGB rgb() {
        RGB rgb = themes.getCurrentTheme().getColorRegistry().getRGB(COLOR_ID);
        return rgb != null ? rgb : DEFAULT;
    }

    private static int toInt(RGB rgb) {
        return (rgb.red << 16) | (rgb.green << 8) | rgb.blue;
    }
}
