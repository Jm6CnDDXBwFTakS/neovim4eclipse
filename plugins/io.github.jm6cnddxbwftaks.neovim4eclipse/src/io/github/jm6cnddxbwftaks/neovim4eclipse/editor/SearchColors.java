// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.BiConsumer;

import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Listener;
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
 * <h2>Dark editors without Eclipse's dark theme</h2>
 * Where the registry holds one of the two defaults, the editor's background picks between them
 * ({@link OverlayColors#searchMatch}): a dark editor in another theme, or in a high-contrast
 * system setting, gets the dark amber too. The editor's colors are watched on the widget itself,
 * at each paint: they change through the Text Editors preferences, but also straight on the
 * widget, without any preference (a TextMate theme in the Generic Editor, for example), and the
 * widget repaints after either. A paint whose colors differ from the last ones schedules new
 * highlight colors (not during the paint, which must not restyle the text it draws).
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
    static final RGB DEFAULT = rgb(OverlayColors.SEARCH_MATCH_LIGHT);

    private final StyledText widget;
    private final BiConsumer<Color, Color> apply;
    /** The theme manager, or null without a running workbench (see {@link #themeManager}). */
    private final IThemeManager themes = themeManager();
    /**
     * Hears the theme: a change of this color, or another theme. Either may happen on any thread
     * (a preference import, a Job), and the colors go to the widget, so the work hops to the
     * widget's UI thread.
     */
    private final IPropertyChangeListener listener = this::onThemeChange;
    /** Notices a change of the editor's own colors at the next paint (see the class comment). */
    private final Listener paint = event -> onPaint();
    /** The widget's background and text color the colors were last made for. UI thread. */
    private RGB seenBackground;
    private RGB seenForeground;
    /** Set by {@link #dispose}: an update queued before it is dropped. UI thread. */
    private boolean disposed;

    /**
     * Hands {@code apply} the match and box colors now and after every change of the color, the
     * theme, or the editor's background or text color, until {@link #dispose}.
     */
    SearchColors(StyledText widget, BiConsumer<Color, Color> apply) {
        this.widget = widget;
        this.apply = apply;
        if (themes != null) {
            themes.addPropertyChangeListener(listener);
        }
        widget.addListener(SWT.Paint, paint);
        update();
    }

    /** The match color now, for the widget's background. */
    Color match() {
        int chosen = toInt(registryRgb());
        return new Color(rgb(OverlayColors.searchMatch(chosen, toInt(widget.getBackground().getRGB()))));
    }

    /** The box color for {@code match}, toward the widget's text color. */
    Color box(Color match) {
        RGB fg = widget.getForeground().getRGB();
        int mixed = OverlayColors.searchBox(toInt(match.getRGB()), toInt(fg));
        return new Color(rgb(mixed));
    }

    private void onThemeChange(PropertyChangeEvent event) {
        String property = event.getProperty();
        if ((COLOR_ID.equals(property) || IThemeManager.CHANGE_CURRENT_THEME.equals(property)) && !widget.isDisposed()) {
            widget.getDisplay().asyncExec(this::update);
        }
    }

    /** At a paint: new colors once the editor's background or text color changed. */
    private void onPaint() {
        RGB background = widget.getBackground().getRGB();
        RGB foreground = widget.getForeground().getRGB();
        if (!background.equals(seenBackground) || !foreground.equals(seenForeground)) {
            seenBackground = background; // one update per change, however many paints come first
            seenForeground = foreground;
            widget.getDisplay().asyncExec(this::update);
        }
    }

    /** Stops listening; an update already queued does nothing. UI thread. */
    void dispose() {
        disposed = true;
        if (themes != null) {
            themes.removePropertyChangeListener(listener);
        }
        if (!widget.isDisposed()) {
            widget.removeListener(SWT.Paint, paint);
        }
    }

    private void update() {
        if (disposed || widget.isDisposed()) {
            return;
        }
        seenBackground = widget.getBackground().getRGB();
        seenForeground = widget.getForeground().getRGB();
        Color match = match();
        apply.accept(match, box(match));
    }

    /** The color in Colors and Fonts: the user's, the theme's or plugin.xml's; {@link #DEFAULT} where none answers. */
    private RGB registryRgb() {
        RGB rgb = themes != null ? themes.getCurrentTheme().getColorRegistry().getRGB(COLOR_ID) : null;
        return rgb != null ? rgb : DEFAULT;
    }

    /**
     * The workbench's theme manager, or null when there is no running workbench: an editor may
     * attach while Eclipse shuts down, when {@link PlatformUI#getWorkbench()} may throw. The
     * highlights then keep {@link #DEFAULT} (chosen by background) and hear no theme change.
     */
    private static IThemeManager themeManager() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return null;
        }
        try {
            return PlatformUI.getWorkbench().getThemeManager();
        } catch (IllegalStateException e) {
            return null; // closed between the check and the call
        }
    }

    private static RGB rgb(int rgb) {
        return new RGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static int toInt(RGB rgb) {
        return (rgb.red << 16) | (rgb.green << 8) | rgb.blue;
    }
}
