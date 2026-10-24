// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Decides whether to greet the user when Neovim first starts in an editor, and what to say: the
 * part of {@code editor/WelcomeNotice} that needs no Eclipse, so that it is unit-tested. The
 * dialog, the preferences and the UI thread stay in that class.
 *
 * <h2>Once, and again for each new minor version</h2>
 * The installation remembers the <em>feature line</em> it last greeted for: the version's
 * {@code major.minor} ({@code "0.1"} for 0.1.5). Comparing lines, not full versions, keeps patch
 * releases quiet; a minor release is where behavior may change before 1.0.0 (see CHANGELOG.md),
 * so that is when "what's new" is worth a dialog:
 *
 * <pre>
 *   last shown   running         decide()
 *   (none)       0.1.5.qualifier WELCOME   first run after installing
 *   "0.1"        0.1.6           NONE      a patch release
 *   "0.1"        0.2.0           UPDATED   a new minor version: point to the change log
 *   "0.2"        0.1.6           NONE      a downgrade says nothing
 *   "garbage"    0.1.5           WELCOME   an unreadable record counts as none
 * </pre>
 *
 * <p>Users who installed before this note existed have no record, so they get the welcome once
 * too, which is harmless and tells them about the toggle and the preferences.
 */
public final class WelcomeNote {

    /** What to show when Neovim starts in an editor. */
    public enum Kind {
        /** Nothing: already greeted for this feature line (or a newer one). */
        NONE,
        /** The welcome: what the plug-in does and where its switches are. */
        WELCOME,
        /** A short "updated to x.y" note with a link to the change log. */
        UPDATED
    }

    /** The dialog's title for both kinds. */
    public static final String TITLE = "Neovim for Eclipse";

    /** The toggle command (plugin.xml), whose key binding the welcome names. */
    public static final String TOGGLE_COMMAND_ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.commands.toggleMode";

    /**
     * The toggle's key binding as {@code plugin.xml} declares it, in Eclipse's platform-neutral
     * notation ({@code M1} is Ctrl, or Command on macOS). The welcome names it when Eclipse cannot say
     * which binding is active (see {@code WelcomeNotice.toggleKey}); {@code PluginXmlConsistencyTest}
     * checks that it matches {@code plugin.xml}.
     */
    public static final String DEFAULT_TOGGLE_SEQUENCE = "M1+M2+M";

    /** The README, opened by the welcome's Documentation button. */
    public static final String DOCUMENTATION_URL = "https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#readme";

    /** The change log, opened by the update note's What's New button. */
    public static final String CHANGELOG_URL =
            "https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse/blob/main/CHANGELOG.md";

    private WelcomeNote() {
    }

    /**
     * Whether to show a note now.
     *
     * @param lastShownLine the feature line recorded when a note was last shown ({@code "0.1"}), or
     *                      {@code null} / empty if none was
     * @param runningVersion the plug-in's bundle version, for example {@code "0.1.5.qualifier"}
     */
    public static Kind decide(String lastShownLine, String runningVersion) {
        int[] running = parse(runningVersion);
        int[] last = parse(lastShownLine);
        if (running == null) {
            return Kind.NONE; // cannot tell; better quiet than a dialog on every start
        }
        if (last == null) {
            return Kind.WELCOME;
        }
        int order = Integer.compare(running[0], last[0]) != 0
                ? Integer.compare(running[0], last[0])
                : Integer.compare(running[1], last[1]);
        return order > 0 ? Kind.UPDATED : Kind.NONE;
    }

    /**
     * The feature line to record after showing a note: {@code "0.1"} for {@code "0.1.5.qualifier"}.
     * Returns {@code null} for a version that cannot be read, so nothing misleading is stored.
     */
    public static String featureLine(String version) {
        int[] parsed = parse(version);
        return parsed == null ? null : parsed[0] + "." + parsed[1];
    }

    /**
     * The welcome text. For example, with the default key binding on Linux:
     *
     * <pre>
     * Neovim now handles the keys in this editor: a real Neovim edits the file you see, ...
     *
     * - Turn it off and on with Ctrl+Shift+M, the Neovim toolbar button or Quick Access (Ctrl+3) ...
     * </pre>
     *
     * @param toggleKey the toggle command's key binding as Eclipse formats it ({@code "Ctrl+Shift+M"},
     *                  on macOS {@code "⇧⌘M"}), or {@code null} / empty if the user removed it
     */
    public static String welcomeMessage(String toggleKey) {
        String toggle = toggleKey == null || toggleKey.isBlank()
                ? "the Neovim toolbar button or Quick Access (search \"Toggle Neovim\")"
                : toggleKey + ", the Neovim toolbar button or Quick Access (search \"Toggle Neovim\")";
        return "Neovim now handles the keys in this editor: a real Neovim, with your own init.lua, "
                + "edits the file you see. Eclipse still owns files, content assist and its own commands.\n\n"
                + "- Turn it off and on with " + toggle + ". Off, the editor uses Eclipse's own keys.\n"
                + "- gd and K use Eclipse's Open Declaration and hover; :w, :q, :e and gt act on Eclipse's "
                + "editors; the \"+ register is the system clipboard.\n"
                + "- Preferences > Neovim: the path to nvim, the toolbar button and menu, and on Ctrl Keys, "
                + "which Ctrl+letter keys go to Neovim and which stay Eclipse's.\n\n"
                + "The Neovim menu's Welcome entry shows this again. Documentation and known limitations "
                + "are on the project page.";
    }

    /**
     * The update note, for example "Neovim for Eclipse was updated to 0.2.0. ...".
     *
     * @param runningVersion the bundle version; only {@code major.minor.micro} is shown, not the
     *                       build qualifier
     */
    public static String updatedMessage(String runningVersion) {
        return "Neovim for Eclipse was updated to " + releaseVersion(runningVersion) + ".\n\n"
                + "The change log lists what is new and what behaves differently. "
                + "This note appears once for each new minor version; turn it off on Preferences > Neovim.";
    }

    /** {@code "0.2.0"} for {@code "0.2.0.v20261101-1200"}; the input itself if it has fewer parts. */
    static String releaseVersion(String version) {
        if (version == null) {
            return "";
        }
        String[] parts = version.split("\\.");
        return parts.length <= 3 ? version : parts[0] + "." + parts[1] + "." + parts[2];
    }

    /** {@code {major, minor}} of {@code "0.1"} or {@code "0.1.5.qualifier"}; {@code null} if unreadable. */
    private static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.strip().split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major < 0 || minor < 0 ? null : new int[] { major, minor };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
