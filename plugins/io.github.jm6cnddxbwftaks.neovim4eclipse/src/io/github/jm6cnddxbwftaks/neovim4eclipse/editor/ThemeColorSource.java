// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.IThemeManager;

/**
 * The search match color in Eclipse ({@link SearchColors.Source}): the current theme's
 * {@link ColorRegistry} holds plugin.xml's default, the theme's value and the user's choice in
 * Colors and Fonts, in that order, and the workbench's {@link IThemeManager} tells of a change of
 * the color ({@link SearchColors#COLOR_ID}) or of the theme.
 *
 * <p>Without a running workbench (an editor may attach while Eclipse shuts down, when
 * {@link PlatformUI#getWorkbench()} may throw) it answers no color and hears nothing: the
 * highlights then keep {@link SearchColors#DEFAULT}, chosen by the editor's background.
 */
final class ThemeColorSource implements SearchColors.Source {

    /** The theme manager, or null without a running workbench. */
    private final IThemeManager themes = themeManager();

    @Override
    public RGB chosen() {
        return themes != null ? themes.getCurrentTheme().getColorRegistry().getRGB(SearchColors.COLOR_ID) : null;
    }

    @Override
    public Runnable listen(Runnable changed) {
        if (themes == null) {
            return () -> { };
        }
        IPropertyChangeListener listener = event -> {
            String property = event.getProperty();
            if (SearchColors.COLOR_ID.equals(property) || IThemeManager.CHANGE_CURRENT_THEME.equals(property)) {
                changed.run();
            }
        };
        themes.addPropertyChangeListener(listener);
        return () -> themes.removePropertyChangeListener(listener);
    }

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
}
