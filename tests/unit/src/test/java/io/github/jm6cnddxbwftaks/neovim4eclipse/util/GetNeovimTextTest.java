// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The words of Get Neovim's dialogs. */
class GetNeovimTextTest {

    private static final NeovimRelease RELEASE = NeovimRelease.bundled();
    private static final NeovimRelease.Archive LA = RELEASE.forPlatform(NeovimPlatform.byCode("la").orElseThrow())
            .orElseThrow();

    @Test
    void theConfirmationNamesTheFolderTheSourceAndTheCheck() {
        Path home = Path.of("/home/you/.local/share/neovim4eclipse-plugin/neovim/la");
        String text = GetNeovimText.confirm(LA, RELEASE.version(), home);
        assertTrue(text.startsWith("Neovim " + RELEASE.version() + " for Linux arm64 goes into\n" + home + "\n"), text);
        assertTrue(text.contains("Download: nvim-linux-arm64.tar.gz, 10.8 MB, from\n" + LA.url()), text);
        assertTrue(text.contains("release-assets.githubusercontent.com"), text);
        assertTrue(text.contains("Install from File: an nvim-linux-arm64.tar.gz you already have"), text);
        assertTrue(text.endsWith("which the plug-in checks before unpacking."), text);
    }

    @Test
    void theDoneNoteSaysWhatIsStillNeeded() {
        Path program = Path.of("/n/la/bin/nvim");
        String plain = GetNeovimText.done(RELEASE.version(), program, false, false);
        assertEquals("Neovim " + RELEASE.version() + " is installed:\n" + program
                + "\n\nThe archive matched this SHA-256:", plain);
        assertTrue(GetNeovimText.done(RELEASE.version(), program, true, false).contains("Restart Neovim to finish"));
        String other = GetNeovimText.done(RELEASE.version(), program, false, true);
        assertTrue(other.contains("Use the Download clears it"), other);
        assertFalse(other.contains("Restart"), other);
    }

    @Test
    void labelsAndLinks() {
        assertEquals("Get Neovim " + RELEASE.version(), GetNeovimText.label(RELEASE));
        assertEquals(URI.create("https://www.virustotal.com/gui/file/" + "ab".repeat(32)),
                GetNeovimText.virusTotal("AB".repeat(32)));
        assertEquals("10.8 MB", GetNeovimText.megabytes(11367117));
    }
}
