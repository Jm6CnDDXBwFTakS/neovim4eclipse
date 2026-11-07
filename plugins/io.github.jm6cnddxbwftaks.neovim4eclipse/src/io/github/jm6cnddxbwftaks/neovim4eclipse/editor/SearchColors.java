// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.BiConsumer;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AnnotationPreference;
import org.eclipse.ui.texteditor.MarkerAnnotationPreferences;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OverlayColors;

/**
 * The colors of the search highlights: Eclipse's own <i>Search Results</i> annotation color
 * (<i>Window &gt; Preferences &gt; General &gt; Editors &gt; Text Editors &gt; Annotations</i>), so
 * that they follow the theme and the user's choice, and for the match under the caret the same
 * color a shade toward the text ({@link OverlayColors#currentSearch}). Neovim's {@code Search}
 * highlight group is not used: its colors are made for Neovim's own screen, not for the editor's
 * theme.
 *
 * <p>The color's preference key is the one the Search plug-in declares for its annotation type
 * ({@code org.eclipse.search.results}), read from the annotation preferences rather than written
 * here; where that plug-in is missing, its default (206, 204, 247) is used. A change of the color
 * (or a theme that sets it) reaches the editor at once through the preference store's listener.
 * UI thread.
 */
final class SearchColors {

    /** The Search plug-in's annotation type for search results. */
    static final String ANNOTATION_TYPE = "org.eclipse.search.results";

    /** Its default color, when the Search plug-in is not installed. */
    private static final RGB DEFAULT = new RGB(206, 204, 247);

    private final StyledText widget;
    private final BiConsumer<Color, Color> apply;
    private final IPreferenceStore store = EditorsUI.getPreferenceStore();
    private final String key = Key.VALUE;
    private final IPropertyChangeListener listener = event -> {
        if (key != null && key.equals(event.getProperty())) {
            update();
        }
    };

    /**
     * Hands {@code apply} the match and current-match colors now and after every change of the
     * preference, until {@link #dispose}.
     */
    SearchColors(StyledText widget, BiConsumer<Color, Color> apply) {
        this.widget = widget;
        this.apply = apply;
        store.addPropertyChangeListener(listener);
        update();
    }

    /** The match color now. */
    Color match() {
        return new Color(rgb());
    }

    /** The current-match color for {@code match}, toward the widget's text color. */
    Color current(Color match) {
        RGB fg = widget.getForeground().getRGB();
        int mixed = OverlayColors.currentSearch(toInt(match.getRGB()), toInt(fg));
        return new Color((mixed >> 16) & 0xFF, (mixed >> 8) & 0xFF, mixed & 0xFF);
    }

    void dispose() {
        store.removePropertyChangeListener(listener);
    }

    private void update() {
        if (widget.isDisposed()) {
            return;
        }
        Color match = match();
        apply.accept(match, current(match));
    }

    private RGB rgb() {
        return key != null && store.contains(key) ? PreferenceConverter.getColor(store, key) : DEFAULT;
    }

    private static int toInt(RGB rgb) {
        return (rgb.red << 16) | (rgb.green << 8) | rgb.blue;
    }

    /** The key, looked up once: reading the annotation preferences reads every plug-in's declarations. */
    private static final class Key {
        static final String VALUE = colorKey();
    }

    /** The Search Results color's preference key, or {@code null} without the Search plug-in. */
    private static String colorKey() {
        for (Object o : new MarkerAnnotationPreferences().getAnnotationPreferences()) {
            if (o instanceof AnnotationPreference preference && ANNOTATION_TYPE.equals(preference.getAnnotationType())) {
                return preference.getColorPreferenceKey();
            }
        }
        return null;
    }
}
