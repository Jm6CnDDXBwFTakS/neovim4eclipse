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
    public static final String SHOW_TOGGLE_BUTTON = "showToggleButton";

    public static final boolean SHOW_TOGGLE_BUTTON_DEFAULT = true;

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
    public static final String STATUS_MODE = "statusMode";
    public static final boolean STATUS_MODE_DEFAULT = true;

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
    public static final String WELCOME_SHOWN_FOR_VERSION = "welcomeShownForVersion";

    // ---- Which Ctrl+letter keys go to Neovim (Window > Preferences > Neovim > Ctrl Keys) ----

    /** Prefix of the per-letter preferences; the lower-case letter follows, e.g. {@code ctrlKeyNormal.f}. */
    public static final String CTRL_KEY_NORMAL_PREFIX = "ctrlKeyNormal.";

    /** Preference key for one letter: {@code true} sends Ctrl+letter to Neovim, {@code false} leaves it to Eclipse. */
    public static String ctrlKeyNormal(char letter) {
        return CTRL_KEY_NORMAL_PREFIX + Character.toLowerCase(letter);
    }

    /**
     * Prefix of the per-letter insert-mode preferences: {@code ctrlKeyInsert.w} is {@code true} when
     * Ctrl+W goes to Neovim in insert mode (docs/design/INSERT_CTRL_KEYS.md).
     */
    public static final String CTRL_KEY_INSERT_PREFIX = "ctrlKeyInsert.";

    /** Preference key for one letter in insert mode: {@code true} sends it to Neovim there. */
    public static String ctrlKeyInsert(char letter) {
        return CTRL_KEY_INSERT_PREFIX + Character.toLowerCase(letter);
    }

    /** Whether the key is one of the per-letter Ctrl-key preferences, normal or insert. */
    public static boolean isCtrlKeyKey(String key) {
        return key != null && (key.startsWith(CTRL_KEY_NORMAL_PREFIX) || key.startsWith(CTRL_KEY_INSERT_PREFIX));
    }

    /** Whether the key is one of the status bar preferences: the mode, the one part left. */
    public static boolean isStatusItemKey(String key) {
        return STATUS_MODE.equals(key);
    }
}
