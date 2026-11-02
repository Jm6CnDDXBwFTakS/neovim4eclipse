// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The words of <i>Get Neovim</i>'s dialogs, kept apart from the dialogs ({@code prefs/GetNeovim}) so
 * the unit tests can read them. Example of the confirmation:
 *
 * <pre>
 * Neovim 0.12.6 for Linux arm64 goes into
 * /home/you/.local/share/neovim4eclipse-plugin/neovim/la
 *
 * Download: nvim-linux-arm64.tar.gz, 10.8 MB, from
 * https://github.com/neovim/neovim/releases/download/v0.12.6/nvim-linux-arm64.tar.gz
 * (github.com sends it on to release-assets.githubusercontent.com).
 *
 * Install from File: an nvim-linux-arm64.tar.gz you already have, for example from a browser.
 *
 * Either way the archive must have the SHA-256 below, which the plug-in checks before unpacking.
 * </pre>
 */
public final class GetNeovimText {

    /** The dialogs' title. */
    public static final String TITLE = "Get Neovim";

    private GetNeovimText() {
    }

    /** {@code Get Neovim 0.12.6}, for buttons and the menu (without the ellipsis). */
    public static String label(NeovimRelease release) {
        return "Get Neovim " + release.version();
    }

    /** The confirmation's text. */
    public static String confirm(NeovimRelease.Archive archive, NeovimVersion version, Path home) {
        return "Neovim " + version + " for " + archive.platform().describe() + " goes into\n" + home + "\n\n"
                + "Download: " + archive.asset() + ", " + megabytes(archive.size()) + ", from\n" + archive.url()
                + "\n(github.com sends it on to release-assets.githubusercontent.com).\n\n"
                + "Install from File: an " + archive.asset() + " you already have, for example from a browser.\n\n"
                + "Either way the archive must have the SHA-256 below, which the plug-in checks before unpacking.";
    }

    /**
     * The done note's text.
     *
     * @param waits        whether the new Neovim waits for <i>Restart Neovim</i>
     * @param otherInUse   the preference names another Neovim, so the download is not used until it is cleared
     */
    public static String done(NeovimVersion version, Path program, boolean waits, boolean otherInUse) {
        StringBuilder s = new StringBuilder("Neovim " + version + " is installed:\n" + program);
        if (waits) {
            s.append("\n\nThe Neovim running now came from the same folder: Restart Neovim to finish. Until then "
                    + "the new one waits beside it.");
        }
        if (otherInUse) {
            s.append("\n\nThe Neovim preference names another Neovim. Use the Download clears it, so that the "
                    + "plug-in uses this one.");
        }
        s.append("\n\nThe archive matched the SHA-256 pinned in the plug-in.");
        return s.toString();
    }

    /** The VirusTotal page for a hash, which the user may open to see others' scans of the archive. */
    public static URI virusTotal(String sha256) {
        return URI.create("https://www.virustotal.com/gui/file/" + sha256.toLowerCase(Locale.ROOT));
    }

    /** {@code 10.8 MB}. */
    static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
