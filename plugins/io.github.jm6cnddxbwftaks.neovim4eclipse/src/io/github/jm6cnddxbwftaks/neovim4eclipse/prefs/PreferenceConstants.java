// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

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

    /** Whether the Neovim toolbar button (the logo) is visible. */
    public static final String SHOW_TOOLBAR = "showToolbar";

    public static final boolean SHOW_TOOLBAR_DEFAULT = true;

    /** Whether the Neovim menu is in the menu bar. */
    public static final String SHOW_MENU = "showMenu";

    public static final boolean SHOW_MENU_DEFAULT = true;

    // ---- Which parts of the Vim status information the Eclipse status bar shows ----

    /** Show the mode ({@code -- INSERT --}, {@code recording @q}). */
    public static final String STATUS_ITEM_MODE = "statusItemMode";
    /** Show the pending command ({@code showcmd}, e.g. {@code 2d}). */
    public static final String STATUS_ITEM_CMD = "statusItemCmd";
    /** Show Neovim's own {@code 'statusline'}. */
    public static final String STATUS_ITEM_STATUSLINE = "statusItemStatusline";

    public static final boolean STATUS_ITEM_MODE_DEFAULT = true;
    public static final boolean STATUS_ITEM_CMD_DEFAULT = true;
    /** Off by default: it is long and mostly repeats what Eclipse already shows (file, position). */
    public static final boolean STATUS_ITEM_STATUSLINE_DEFAULT = false;

    // ---- Messages in the editor ----

    /**
     * Whether an error also flashes the editor briefly, like Vim's {@code 'visualbell'}, so that it is
     * noticed while the eyes are on the cursor. Messages always appear in the editor's message line.
     */
    public static final String FLASH_ON_ERROR = "flashOnError";

    /** Off by default: a flash is easy to find distracting. */
    public static final boolean FLASH_ON_ERROR_DEFAULT = false;

    // ---- The welcome note (editor/WelcomeNotice, util/WelcomeNote) ----

    /**
     * Whether the welcome appears when Neovim first starts in an editor, and the update note after an
     * upgrade to a new minor version. A check box on the Neovim page. An administrator can turn it
     * off for every user with a {@code plugin_customization.ini} line
     * {@code io.github.jm6cnddxbwftaks.neovim4eclipse/showWelcome=false}; the UI tests do the same
     * ({@code tests/...tests/test-customization.ini}), because a modal dialog would stop them.
     */
    public static final String SHOW_WELCOME = "showWelcome";

    public static final boolean SHOW_WELCOME_DEFAULT = true;

    /**
     * The feature line ({@code "0.1"}) a note was last shown for. Kept in the
     * <em>configuration</em> scope, which belongs to the Eclipse installation, not in the workspace
     * like the other preferences: the welcome is about installing the plug-in, so a second workspace
     * should not show it again. Not on any preference page.
     */
    public static final String WELCOME_SHOWN_LINE = "welcomeShownLine";

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

    /** Whether the key is one of the three status item preferences. */
    public static boolean isStatusItemKey(String key) {
        return STATUS_ITEM_MODE.equals(key) || STATUS_ITEM_CMD.equals(key) || STATUS_ITEM_STATUSLINE.equals(key);
    }
}
