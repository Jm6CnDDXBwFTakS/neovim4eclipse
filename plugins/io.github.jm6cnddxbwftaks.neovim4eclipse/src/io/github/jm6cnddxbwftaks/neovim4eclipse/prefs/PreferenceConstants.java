// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

/**
 * Keys and defaults for the Neovim plug-in preference store.
 */
public final class PreferenceConstants {

    private PreferenceConstants() {
    }

    /**
     * The removed "Neovim program" preference (0.2.0 and the 0.2.1 builds before the change): which
     * Neovim ran. Since 0.2.1 that is the startup option {@code -Dneovim4eclipse.nvim}
     * ({@code util/NeovimOption}), and this key is only read once, so that a value still stored in
     * a workspace is shown to the user and then removed ({@code editor/OldNeovimProgramNotice}). No
     * default: an unset key reads as blank.
     */
    public static final String REMOVED_NVIM_PROGRAM = "nvimExecutable";

    /** Whether the Neovim toolbar button (the logo) is visible. */
    public static final String SHOW_TOOLBAR = "showToolbar";

    public static final boolean SHOW_TOOLBAR_DEFAULT = true;

    /** Whether the Neovim menu is in the menu bar. */
    public static final String SHOW_MENU = "showMenu";

    public static final boolean SHOW_MENU_DEFAULT = true;

    /**
     * <b>Unsupported, with no control on any preference page:</b> whether Eclipse's <i>Toggle Split
     * Editor</i> (Ctrl+_, Ctrl+{) makes Eclipse's own two halves of one editor in an editor Neovim
     * drives, instead of a Vim split (a new editor stack, as {@code :split} and {@code :vsplit}
     * make; docs/design/SPLITS.md). Kept in case the halves turn out to be wanted, so that offering
     * the choice later takes one checkbox: the key, the default and the handler's check
     * ({@code editor/SplitEditorHandler}) are already here, and a UI test keeps the halves working.
     * It may change or go away until then, and the settings the plug-in promises to keep leave it
     * out.
     *
     * <p>Read each time the command runs, from the preference store (no Neovim call), so a change
     * applies at once. It can be set by hand (the workspace's
     * {@code .metadata/.plugins/org.eclipse.core.runtime/.settings/io.github.jm6cnddxbwftaks.neovim4eclipse.prefs},
     * an {@code .epf} import, a {@code plugin_customization.ini}): it only picks the kind of split.
     */
    public static final String ECLIPSE_SPLITS = "eclipseSplits";

    public static final boolean ECLIPSE_SPLITS_DEFAULT = false;

    // ---- Which parts of the Vim status information the Eclipse status bar shows ----

    /** Show the mode ({@code INSERT}, {@code INSERT rec @q}). */
    public static final String STATUS_ITEM_MODE = "statusItemMode";
    /** Show the pending command ({@code showcmd}, e.g. {@code 2d}). */
    public static final String STATUS_ITEM_CMD = "statusItemCmd";
    /** Show Neovim's own {@code 'statusline'}. */
    public static final String STATUS_ITEM_STATUSLINE = "statusItemStatusline";

    public static final boolean STATUS_ITEM_MODE_DEFAULT = true;
    public static final boolean STATUS_ITEM_CMD_DEFAULT = true;
    /** Off by default: it is long and mostly repeats what Eclipse already shows (file, position). */
    public static final boolean STATUS_ITEM_STATUSLINE_DEFAULT = false;

    /**
     * How wide the statusline part is, in characters of the status bar's font. Neovim fits its
     * {@code 'statusline'} into that many columns ({@code %<} cuts, {@code %=} fills), so the
     * statusline is never cut by Eclipse instead.
     */
    public static final String STATUS_STATUSLINE_WIDTH = "statusStatuslineWidth";

    public static final int STATUS_STATUSLINE_WIDTH_DEFAULT = 60;
    /** The range the preference page accepts. */
    public static final int STATUS_STATUSLINE_WIDTH_MIN = 10;
    public static final int STATUS_STATUSLINE_WIDTH_MAX = 300;

    /**
     * The most the plug-in's labels may take of the status bar's width, in percent, so Eclipse's own
     * message area keeps room ({@code util/StatusBarWidths}).
     */
    public static final String STATUS_MAX_SHARE = "statusMaxShare";

    public static final int STATUS_MAX_SHARE_DEFAULT = 40;
    public static final int STATUS_MAX_SHARE_MIN = 10;
    public static final int STATUS_MAX_SHARE_MAX = 90;

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

    /**
     * Prefix of the per-letter insert-mode preferences: {@code ctrlKeyInInsert.w} is {@code true} when
     * Ctrl+W goes to Neovim in insert mode (docs/design/INSERT_CTRL_KEYS.md).
     */
    public static final String CTRL_KEY_IN_INSERT_PREFIX = "ctrlKeyInInsert.";

    /** Preference key for one letter in insert mode: {@code true} sends it to Neovim there. */
    public static String ctrlKeyInInsert(char letter) {
        return CTRL_KEY_IN_INSERT_PREFIX + Character.toLowerCase(letter);
    }

    /** Whether the key is one of the per-letter Ctrl-key preferences, normal or insert. */
    public static boolean isCtrlKeyKey(String key) {
        return key != null && (key.startsWith(CTRL_KEY_TO_NEOVIM_PREFIX) || key.startsWith(CTRL_KEY_IN_INSERT_PREFIX));
    }

    /** Whether the key is one of the status bar preferences: the three parts, the widths. */
    public static boolean isStatusItemKey(String key) {
        return STATUS_ITEM_MODE.equals(key) || STATUS_ITEM_CMD.equals(key) || STATUS_ITEM_STATUSLINE.equals(key)
                || STATUS_STATUSLINE_WIDTH.equals(key) || STATUS_MAX_SHARE.equals(key);
    }
}
