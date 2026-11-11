// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * The plug-in's default preference values, written into whatever takes them: in Eclipse, the
 * default scope ({@link PreferenceInitializer}); in the SWT tests, an in-memory JFace
 * {@code PreferenceStore}, so a page's Restore Defaults shows the same values there. One list, so
 * the tests cannot drift from the plug-in. Example, in a test:
 *
 * <pre>
 * PreferenceStore store = new PreferenceStore();
 * PreferenceDefaults.fill(new PreferenceDefaults.Sink() {
 *     public void put(String key, boolean value) { store.setDefault(key, value); }
 *     public void put(String key, int value) { store.setDefault(key, value); }
 * });
 * </pre>
 *
 * Uses no Eclipse class, so it compiles wherever {@link PreferenceConstants} does.
 */
final class PreferenceDefaults {

    /** Where the defaults go: one call per key. */
    interface Sink {
        void put(String key, boolean value);

        void put(String key, int value);
    }

    private PreferenceDefaults() {
    }

    /** Writes every default into {@code sink}. */
    static void fill(Sink sink) {
        sink.put(PreferenceConstants.SHOW_TOOLBAR, PreferenceConstants.SHOW_TOOLBAR_DEFAULT);
        sink.put(PreferenceConstants.SHOW_MENU, PreferenceConstants.SHOW_MENU_DEFAULT);
        sink.put(PreferenceConstants.ECLIPSE_SPLITS, PreferenceConstants.ECLIPSE_SPLITS_DEFAULT);
        sink.put(PreferenceConstants.STATUS_ITEM_MODE, PreferenceConstants.STATUS_ITEM_MODE_DEFAULT);
        sink.put(PreferenceConstants.STATUS_ITEM_CMD, PreferenceConstants.STATUS_ITEM_CMD_DEFAULT);
        sink.put(PreferenceConstants.STATUS_ITEM_STATUSLINE, PreferenceConstants.STATUS_ITEM_STATUSLINE_DEFAULT);
        sink.put(PreferenceConstants.STATUS_STATUSLINE_WIDTH, PreferenceConstants.STATUS_STATUSLINE_WIDTH_DEFAULT);
        sink.put(PreferenceConstants.STATUS_MAX_SHARE, PreferenceConstants.STATUS_MAX_SHARE_DEFAULT);
        sink.put(PreferenceConstants.FLASH_ON_ERROR, PreferenceConstants.FLASH_ON_ERROR_DEFAULT);
        // A plugin_customization.ini (a product's, or -pluginCustomization <file>) can still change this
        // default: Eclipse applies it after the initializer. The UI tests set it to false that way.
        sink.put(PreferenceConstants.SHOW_WELCOME, PreferenceConstants.SHOW_WELCOME_DEFAULT);
        for (char letter : VimCtrlKeys.LETTERS.toCharArray()) {
            sink.put(PreferenceConstants.ctrlKeyToNeovim(letter), VimCtrlKeys.toNeovimByDefault(letter));
            sink.put(PreferenceConstants.ctrlKeyInInsert(letter), VimCtrlKeys.inInsertByDefault(letter, PlatformUtil.isMac()));
        }
    }
}
