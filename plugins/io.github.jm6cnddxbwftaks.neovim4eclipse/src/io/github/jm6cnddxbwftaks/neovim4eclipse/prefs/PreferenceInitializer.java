// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;

/**
 * Sets default values in the plug-in preference store: the values are {@link PreferenceDefaults}'s,
 * which the SWT tests write into a store of their own.
 *
 * <p>Writes to the {@link DefaultScope} node directly instead of going through
 * {@code Activator.getDefault()}: the platform may run initializers before the bundle is
 * activated, when {@code getDefault()} is still {@code null}.
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(Activator.PLUGIN_ID);
        PreferenceDefaults.fill(new PreferenceDefaults.Sink() {
            @Override
            public void put(String key, boolean value) {
                defaults.putBoolean(key, value);
            }

            @Override
            public void put(String key, int value) {
                defaults.putInt(key, value);
            }
        });
    }
}
