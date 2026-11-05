// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage.Link;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage.Point;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimInstallException.Reason;

/** The words, values and links of Get Neovim's dialogs. */
class GetNeovimTextTest {

    private static final NeovimRelease RELEASE = NeovimRelease.bundled();
    private static final NeovimRelease.Archive LA = RELEASE.forPlatform(NeovimPlatform.byCode("la").orElseThrow())
            .orElseThrow();

    /**
     * Opened by the plug-in (no verified dedicated Neovim): a first Why point says so, and a last
     * Instead point names the preference, for a reader who has a Neovim of their own.
     */
    @Test
    void theConfirmationThePlugInOpensSaysWhyAndWhatInstead() {
        Path home = Path.of("/n/la");
        HeadedMessage m = GetNeovimText.confirm(RELEASE, LA, home, "The dedicated Neovim is not installed.");
        assertEquals(List.of("Why", "Folder", "Download", "From File", "SHA-256", "Instead"), headings(m));
        assertEquals("The dedicated Neovim is not installed. With the Neovim program preference blank, Neovim for "
                + "Eclipse will only run with the dedicated Neovim; until it is installed or replaced, editors keep "
                + "their normal Eclipse keys.", point(m, "Why").text());
        assertEquals("To use a Neovim of your own, cancel and enter its program on the Neovim preference page "
                + "(Window > Preferences > Neovim). It is run unchecked.", point(m, "Instead").text());
    }

    /** Opened by the user (menu, preference page): no Why, and no Instead nudging away from the default. */
    @Test
    void theConfirmationTheUserOpensHasNeitherWhyNorInstead() {
        Path home = Path.of("/n/la");
        List<String> expected = List.of("Folder", "Download", "From File", "SHA-256");
        assertEquals(expected, headings(GetNeovimText.confirm(RELEASE, LA, home)));
        assertEquals(expected, headings(GetNeovimText.confirm(RELEASE, LA, home, null)));
        assertEquals(expected, headings(GetNeovimText.confirm(RELEASE, LA, home, " ")), "a blank why is none");
    }

    @Test
    void theConfirmationNamesTheFolderTheSourceAndTheCheck() {
        Path home = Path.of("/home/you/.local/share/neovim4eclipse-plugin/neovim/la");
        HeadedMessage m = GetNeovimText.confirm(RELEASE, LA, home);
        assertEquals("Install the dedicated Neovim " + RELEASE.version() + " for Linux arm64?", m.intro());
        assertEquals(List.of("Folder", "Download", "From File", "SHA-256"), headings(m));
        assertTrue(point(m, "Folder").text().startsWith("Dedicated to the plug-in"), "not a Neovim of the user's");
        assertEquals(home.toString(), point(m, "Folder").copy());
        String download = point(m, "Download").text();
        assertTrue(download.startsWith("nvim-linux-arm64.tar.gz, 10.8 MB, from github.com"), download);
        assertTrue(download.contains("release-assets.githubusercontent.com"), "the host a proxy must allow");
        assertEquals(List.of(LA.url()), urls(point(m, "From File")), "the archive itself, for a browser");
        assertEquals("Download nvim-linux-arm64.tar.gz", point(m, "From File").links().get(0).label(),
                "the label says it is the file itself, not a page");
        Point sha = point(m, "SHA-256");
        assertEquals(LA.sha256(), sha.copy());
        assertEquals(List.of(RELEASE.releasePage(), GetNeovimText.virusTotal(LA.sha256())), urls(sha));
    }

    @Test
    void theDoneNoteSaysWhatIsStillNeeded() {
        Path program = Path.of("/n/la/bin/nvim");
        HeadedMessage plain = GetNeovimText.done(RELEASE.version(), program, LA.sha256(), false, false);
        assertEquals("The dedicated Neovim " + RELEASE.version() + " is installed.", plain.intro());
        assertEquals(List.of("Program", "SHA-256"), headings(plain));
        assertEquals(program.toString(), point(plain, "Program").copy());
        assertEquals("The archive matched the one pinned in the plug-in.", point(plain, "SHA-256").text());
        assertEquals(List.of(GetNeovimText.virusTotal(LA.sha256())), urls(point(plain, "SHA-256")));

        HeadedMessage waits = GetNeovimText.done(RELEASE.version(), program, LA.sha256(), true, false);
        assertEquals("The new dedicated Neovim replaces the one running now from the same folder, in this Eclipse or "
                + "another. Restart Neovim to finish, once no other Eclipse runs it.", point(waits, "Restart").text());
        HeadedMessage other = GetNeovimText.done(RELEASE.version(), program, LA.sha256(), false, true);
        assertEquals("The \"Neovim program\" preference names another Neovim. Use Dedicated Neovim clears "
                + "it, so the dedicated Neovim is used.", point(other, "Preference").text(),
                "names the button, without its mnemonic");
        assertEquals("&Use Dedicated Neovim", GetNeovimText.USE_DEDICATED_BUTTON);
        assertFalse(headings(other).contains("Restart"));
    }

    @Test
    void aProxyPasswordFailureGivesTheEclipseIniLineToCopy() {
        HeadedMessage m = GetNeovimText.failed(new NeovimInstallException(Reason.PROXY_PASSWORD, "long"), RELEASE, LA);
        assertEquals("Neovim was not installed.", m.intro());
        assertEquals(List.of("Why", "Fix", "Then", "Instead"), headings(m));
        assertTrue(point(m, "Why").text().contains("HTTP 407"));
        assertEquals(NeovimDownload.TUNNEL_AUTH_LINE, point(m, "Fix").copy());
        assertEquals(List.of(LA.url(), RELEASE.releasePage()), urls(point(m, "Instead")));
    }

    @Test
    void otherFailuresShowTheMessageAndOfferABrowserOnlyWhereItHelps() {
        HeadedMessage network = failed(Reason.NETWORK, "Cannot find github.com.");
        assertEquals("Cannot find github.com.", point(network, "Why").text());
        assertEquals(List.of("Why", "Instead"), headings(network));
        // The same archive from a browser would not run either, and a busy folder needs only a retry.
        assertEquals(List.of("Why"), headings(failed(Reason.DOES_NOT_RUN, "x")));
        assertEquals(List.of("Why"), headings(failed(Reason.BUSY, "x")));
    }

    @Test
    void aWrongArchiveSetsBothHashesInFieldsToCompare() {
        String other = "0".repeat(64);
        HeadedMessage m = GetNeovimText.failed(new NeovimInstallException("long, for the log",
                new NeovimInstallException.Found("nvim(1).tar.gz", 11000, other)), RELEASE, LA);
        assertEquals(List.of("Why", "Expected", "Your File", "Instead"), headings(m));
        assertEquals("nvim(1).tar.gz is not the Neovim " + RELEASE.version() + " archive for Linux arm64: this file "
                + "is another version, or damaged.", point(m, "Why").text());
        assertEquals("nvim-linux-arm64.tar.gz, 11,367,117 bytes, SHA-256:", point(m, "Expected").text());
        assertEquals(LA.sha256(), point(m, "Expected").copy());
        assertEquals("11,000 bytes, SHA-256:", point(m, "Your File").text());
        assertEquals(other, point(m, "Your File").copy());
    }

    private static HeadedMessage failed(Reason reason, String message) {
        return GetNeovimText.failed(new NeovimInstallException(reason, message), RELEASE, LA);
    }

    @Test
    void aMachineWithoutAnArchiveIsToldWhatToDoInstead() {
        HeadedMessage m = GetNeovimText.noArchive(RELEASE.version(), "Linux", "arm");
        assertEquals("Neovim publishes no release archive for Linux on arm.", m.intro());
        assertTrue(point(m, "Instead").text().startsWith("Install Neovim " + RELEASE.version() + " or newer"));
    }

    @Test
    void labelsAndLinks() {
        assertEquals("Get Neovim " + RELEASE.version(), GetNeovimText.label(RELEASE));
        assertEquals(URI.create("https://www.virustotal.com/gui/file/" + "ab".repeat(32)),
                GetNeovimText.virusTotal("AB".repeat(32)));
        assertEquals("10.8 MB", GetNeovimText.megabytes(11367117));
        assertEquals("&Download", GetNeovimText.CONFIRM_BUTTONS.get(0), "the default button");
    }

    private static List<String> headings(HeadedMessage m) {
        return m.points().stream().map(Point::heading).toList();
    }

    private static Point point(HeadedMessage m, String heading) {
        return m.point(heading).orElseThrow(() -> new AssertionError("no point " + heading + " in " + m.plain()));
    }

    private static List<URI> urls(Point p) {
        return p.links().stream().map(Link::url).toList();
    }
}
