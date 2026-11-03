// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage.Link;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage.Point;

/**
 * The words of <i>Get Neovim</i>'s dialogs, as {@link HeadedMessage}s that
 * {@code dialogs/HeadedMessageDialog} lays out, kept apart from the dialogs ({@code prefs/GetNeovim})
 * so the unit tests can read them. Example of the confirmation ({@code [..]} is a field with Copy):
 *
 * <pre>
 *   Install the dedicated Neovim 0.12.6 for Linux arm64?
 *
 *   Folder      Dedicated to the plug-in; no other Neovim is changed.
 *               [/home/you/.local/share/neovim4eclipse-plugin/neovim/la]
 *   Download    nvim-linux-arm64.tar.gz, 10.8 MB, from github.com, which sends it on to
 *               release-assets.githubusercontent.com.
 *   From File   An nvim-linux-arm64.tar.gz you already have, for example from a browser.
 *               Download nvim-linux-arm64.tar.gz
 *   SHA-256     Either way, the plug-in checks the archive against this before unpacking.
 *               [2e58d259ffcc6145e5112ea02330126c6b57da09087bb9265fb2fa9aa10b485c]
 *               GitHub release page  ·  VirusTotal report
 * </pre>
 *
 * Why the SHA-256 and the links: the plug-in checks the archive against the hash pinned in it, and a
 * user who wants to check that hash themselves can compare it with GitHub's release page or look it
 * up on VirusTotal. The plug-in never contacts VirusTotal: only the user's click on the link sends
 * the hash, from their browser.
 *
 * <p>"Dedicated" is the word for the plug-in's own Neovim, in {@code neovim/<code>/} of the plug-in's
 * folder ({@link PluginNeovim}): installed for Eclipse alone, apart from any Neovim the user runs
 * elsewhere, which Get Neovim never changes. The dialogs, the preference page's note
 * ({@link NvimProbe}) and the docs all use it, so the user can tell the two apart.
 */
public final class GetNeovimText {

    /** The dialogs' title. */
    public static final String TITLE = "Get Neovim";

    /** The confirmation's buttons, in order; the first is the default. */
    public static final List<String> CONFIRM_BUTTONS = List.of("&Download", "Install from &File...", "Cancel");
    /** The failure dialog's buttons, in order; the first is the default. */
    public static final List<String> FAILED_BUTTONS = List.of("&Retry", "Install from &File...", "Close");

    /**
     * The done note's button that clears the preference when it names another Neovim; the note's
     * <i>Preference</i> point names it, without the mnemonic.
     */
    public static final String USE_DEDICATED_BUTTON = "&Use Dedicated Neovim";
    private static final String USE_DEDICATED_LABEL = USE_DEDICATED_BUTTON.replace("&", "");

    private GetNeovimText() {
    }

    /** {@code Get Neovim 0.12.6}, for buttons and the menu (without the ellipsis). */
    public static String label(NeovimRelease release) {
        return "Get Neovim " + release.version();
    }

    /** The confirmation, before anything is downloaded or unpacked: where it goes, from where, and the check. */
    public static HeadedMessage confirm(NeovimRelease release, NeovimRelease.Archive archive, Path home) {
        return new HeadedMessage(
                "Install the dedicated Neovim " + release.version() + " for " + archive.platform().describe() + "?",
                List.of(new Point("Folder", "Dedicated to the plug-in; no other Neovim is changed.")
                                .withCopy(home.toString()),
                        new Point("Download", archive.asset() + ", " + megabytes(archive.size())
                                + ", from github.com, which sends it on to release-assets.githubusercontent.com."),
                        new Point("From File", "An " + archive.asset() + " you already have, for example from a "
                                + "browser.").withLinks(inBrowser(archive)),
                        new Point("SHA-256", "Either way, the plug-in checks the archive against this before "
                                + "unpacking.").withCopy(archive.sha256())
                                .withLinks(releasePage(release), virusTotalLink(archive.sha256()))),
                "");
    }

    /**
     * The done note.
     *
     * @param waits      whether the new Neovim waits for <i>Restart Neovim</i>
     * @param otherInUse the preference names another Neovim, so the download is not used until it is cleared
     */
    public static HeadedMessage done(NeovimVersion version, Path program, String sha256, boolean waits,
            boolean otherInUse) {
        List<Point> points = new ArrayList<>();
        points.add(new Point("Program", "").withCopy(program.toString()));
        if (waits) {
            points.add(new Point("Restart", "The new dedicated Neovim replaces the one running now from the same "
                    + "folder, in this Eclipse or another. Restart Neovim to finish, once no other Eclipse runs it."));
        }
        if (otherInUse) {
            points.add(new Point("Preference", "The \"Neovim program\" preference names another Neovim. "
                    + USE_DEDICATED_LABEL + " clears it, so the dedicated Neovim is used."));
        }
        points.add(new Point("SHA-256", "The archive matched the one pinned in the plug-in.").withCopy(sha256)
                .withLinks(virusTotalLink(sha256)));
        return new HeadedMessage("The dedicated Neovim " + version + " is installed.", points, "");
    }

    /**
     * The failure dialog: why, and what may help. Example for a proxy that wants a password:
     *
     * <pre>
     *   Neovim was not installed.
     *
     *   Why      The proxy asks for a password (HTTP 407), which Java does not send ...
     *   Fix      Add this line to eclipse.ini, after -vmargs, and restart Eclipse ...:
     *            [-Djdk.http.auth.tunneling.disabledSchemes=]
     *   Then     Enter the proxy's user and password in ... Network Connections, and retry.
     *   Instead  Download nvim-linux-arm64.tar.gz in a browser, then use Install from File.
     *            Download nvim-linux-arm64.tar.gz  ·  GitHub release page
     * </pre>
     *
     * Mostly <i>Why</i> is the exception's message, written for the user. Two reasons are laid out
     * instead, as their message holds a value to copy or compare: the {@code eclipse.ini} line for a
     * proxy password, and both hashes for a wrong archive (the message keeps them inline, for the log).
     */
    public static HeadedMessage failed(NeovimInstallException e, NeovimRelease release,
            NeovimRelease.Archive archive) {
        NeovimInstallException.Reason reason = e.reason();
        List<Point> points = new ArrayList<>();
        var found = e.found();
        if (reason == NeovimInstallException.Reason.PROXY_PASSWORD) {
            points.add(new Point("Why", "The proxy asks for a password (HTTP 407), which Java does not send "
                    + "through an HTTPS connection by default."));
            points.add(new Point("Fix", "Add this line to eclipse.ini, after -vmargs, and restart Eclipse "
                    + "(Eclipse's own updater needs it too):").withCopy(NeovimDownload.TUNNEL_AUTH_LINE));
            points.add(new Point("Then", "Enter the proxy's user and password in Window > Preferences > General > "
                    + "Network Connections, and retry."));
        } else if (found.isPresent()) {
            points.add(new Point("Why", found.get().fileName() + " is not the Neovim " + release.version()
                    + " archive for " + archive.platform().describe() + ": this file is another version, or damaged."));
            points.add(new Point("Expected", archive.asset() + ", " + bytes(archive.size()) + ", SHA-256:")
                    .withCopy(archive.sha256()));
            points.add(new Point("Your File", bytes(found.get().size()) + ", SHA-256:").withCopy(found.get().sha256()));
        } else {
            points.add(new Point("Why", e.getMessage()));
        }
        if (browserHelps(reason)) {
            points.add(new Point("Instead", "Download " + archive.asset() + " in a browser, then use Install from File.")
                    .withLinks(inBrowser(archive), releasePage(release)));
        }
        return new HeadedMessage("Neovim was not installed.", points, "");
    }

    /** Whether downloading the archive in a browser may get past the failure: not when the archive itself is the problem. */
    private static boolean browserHelps(NeovimInstallException.Reason reason) {
        return switch (reason) {
            case NETWORK, PROXY_PASSWORD, WRONG_ARCHIVE, WRONG_PLATFORM -> true;
            case DOES_NOT_RUN, BUSY, CANCELLED, FILES -> false;
        };
    }

    /** For a machine Neovim publishes no archive for, such as Linux on a 32-bit processor. */
    public static HeadedMessage noArchive(NeovimVersion version, String osName, String osArch) {
        return new HeadedMessage("Neovim publishes no release archive for " + osName + " on " + osArch + ".",
                List.of(new Point("Instead", "Install Neovim " + version + " or newer from your system's packages, "
                        + "and enter its program, such as /usr/bin/nvim, on the Neovim preference page (Window > "
                        + "Preferences > Neovim). It is run unchecked.")),
                "");
    }

    /** The VirusTotal page for a hash, which the user may open to see others' scans of the archive. */
    public static URI virusTotal(String sha256) {
        return URI.create("https://www.virustotal.com/gui/file/" + sha256.toLowerCase(Locale.ROOT));
    }

    private static Link virusTotalLink(String sha256) {
        return new Link("VirusTotal report", virusTotal(sha256));
    }

    private static Link releasePage(NeovimRelease release) {
        return new Link("GitHub release page", release.releasePage());
    }

    /**
     * The archive's own URL ({@code Download nvim-linux-arm64.tar.gz}): a direct download, not a page,
     * so the browser saves the file at once, for Install from File. The label names the file so the
     * user sees it is the archive itself.
     */
    private static Link inBrowser(NeovimRelease.Archive archive) {
        return new Link("Download " + archive.asset(), archive.url());
    }

    /** {@code 11,367,117 bytes}: exact, as the release page gives it, with separators to read it by. */
    static String bytes(long bytes) {
        return String.format(Locale.ROOT, "%,d bytes", bytes);
    }

    /** {@code 10.8 MB}. */
    static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
