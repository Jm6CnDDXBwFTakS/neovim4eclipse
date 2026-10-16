// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.prefs;

/**
 * Keys and defaults for the Neovim plug-in preference store.
 */
public final class PreferenceConstants {

    private PreferenceConstants() {
    }

    /** Absolute path or command name for the Neovim executable. */
    public static final String NVIM_EXECUTABLE = "nvimExecutable";

    /** Default: resolve {@code nvim} from {@code PATH}. */
    public static final String NVIM_EXECUTABLE_DEFAULT = "nvim";

    /** Whether the NVIM toolbar contribution is visible. */
    public static final String SHOW_TOOLBAR = "showToolbar";

    public static final boolean SHOW_TOOLBAR_DEFAULT = true;

    // ---- Which parts of the Vim status information the Eclipse status bar shows ----

    /** Show the mode ({@code -- INSERT --}, {@code recording @q}). */
    public static final String STATUS_ITEM_MODE = "statusItemMode";
    /** Show the pending command ({@code showcmd}, e.g. {@code 2d}). */
    public static final String STATUS_ITEM_CMD = "statusItemCmd";
    /** Show the latest Neovim message or error. */
    public static final String STATUS_ITEM_MSG = "statusItemMsg";
    /** Show Neovim's own {@code 'statusline'}. */
    public static final String STATUS_ITEM_STATUSLINE = "statusItemStatusline";

    public static final boolean STATUS_ITEM_MODE_DEFAULT = true;
    public static final boolean STATUS_ITEM_CMD_DEFAULT = true;
    public static final boolean STATUS_ITEM_MSG_DEFAULT = true;
    /** Off by default: it is long and mostly repeats what Eclipse already shows (file, position). */
    public static final boolean STATUS_ITEM_STATUSLINE_DEFAULT = false;

    // ---- Which Ctrl+letter keys go to Neovim (Window > Preferences > Neovim > Ctrl Keys) ----

    /** Prefix of the per-letter preferences; the lower-case letter follows, e.g. {@code ctrlKeyToNeovim.f}. */
    public static final String CTRL_KEY_TO_NEOVIM_PREFIX = "ctrlKeyToNeovim.";

    /** Preference key for one letter: {@code true} sends Ctrl+letter to Neovim, {@code false} leaves it to Eclipse. */
    public static String ctrlKeyToNeovim(char letter) {
        return CTRL_KEY_TO_NEOVIM_PREFIX + Character.toLowerCase(letter);
    }

    /** Whether the key is one of the per-letter Ctrl-key preferences. */
    public static boolean isCtrlKeyKey(String key) {
        return key != null && key.startsWith(CTRL_KEY_TO_NEOVIM_PREFIX);
    }

    /** Whether the key is one of the four status item preferences. */
    public static boolean isStatusItemKey(String key) {
        return STATUS_ITEM_MODE.equals(key) || STATUS_ITEM_CMD.equals(key) || STATUS_ITEM_MSG.equals(key)
                || STATUS_ITEM_STATUSLINE.equals(key);
    }
}
