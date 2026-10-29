// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * Sets default values in the plug-in preference store.
 *
 * <p>Writes to the {@link DefaultScope} node directly instead of going through
 * {@code Activator.getDefault()}: the platform may run initializers before the bundle is
 * activated, when {@code getDefault()} is still {@code null}.
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(Activator.PLUGIN_ID);
        defaults.put(PreferenceConstants.NVIM_EXECUTABLE, PreferenceConstants.NVIM_EXECUTABLE_DEFAULT);
        defaults.putBoolean(PreferenceConstants.SHOW_TOOLBAR, PreferenceConstants.SHOW_TOOLBAR_DEFAULT);
        defaults.putBoolean(PreferenceConstants.SHOW_MENU, PreferenceConstants.SHOW_MENU_DEFAULT);
        defaults.putBoolean(PreferenceConstants.STATUS_ITEM_MODE, PreferenceConstants.STATUS_ITEM_MODE_DEFAULT);
        defaults.putBoolean(PreferenceConstants.STATUS_ITEM_CMD, PreferenceConstants.STATUS_ITEM_CMD_DEFAULT);
        defaults.putBoolean(PreferenceConstants.STATUS_ITEM_STATUSLINE,
                PreferenceConstants.STATUS_ITEM_STATUSLINE_DEFAULT);
        defaults.putBoolean(PreferenceConstants.FLASH_ON_ERROR, PreferenceConstants.FLASH_ON_ERROR_DEFAULT);
        // A plugin_customization.ini (a product's, or -pluginCustomization <file>) can still change this
        // default: Eclipse applies it after this initializer. The UI tests set it to false that way.
        defaults.putBoolean(PreferenceConstants.SHOW_WELCOME, PreferenceConstants.SHOW_WELCOME_DEFAULT);
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            defaults.putBoolean(PreferenceConstants.ctrlKeyToNeovim(letter), VimCtrlKeys.toNeovimByDefault(letter));
        }
    }
}
