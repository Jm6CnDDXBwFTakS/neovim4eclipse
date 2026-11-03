// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.net.URI;

import org.junit.jupiter.api.Test;

/** The release data file and the platform codes (no Eclipse, no network). */
class NeovimReleaseTest {

    private static final String SHA = "8f1f64a0bdb97247034038c3823c6cbad5bdf9ecd5751b85494b71c3ee04c815";

    private static NeovimPlatform platform(String code) {
        return NeovimPlatform.byCode(code).orElseThrow();
    }

    @Test
    void theBundledFileHasAllSixArchivesOnGitHub() {
        NeovimRelease release = NeovimRelease.bundled();
        assertEquals(6, release.downloads().size());
        for (NeovimPlatform p : NeovimPlatform.ALL) {
            NeovimRelease.Archive a = release.forPlatform(p).orElseThrow();
            assertTrue(a.url().toString().startsWith(NeovimRelease.URL_PREFIX + "v" + release.version() + "/"), a.url()::toString);
            assertTrue(a.url().toString().endsWith("/" + a.asset()), a.url()::toString);
            assertEquals(p.windows() ? NeovimRelease.Kind.ZIP : NeovimRelease.Kind.TAR_GZ, a.kind(), a.asset());
        }
    }

    @Test
    void everyHashIsDifferent() {
        NeovimRelease release = NeovimRelease.bundled();
        assertEquals(6, release.downloads().values().stream().map(NeovimRelease.Archive::sha256).distinct().count());
    }

    @Test
    void anArchiveIsFoundByItsHash() {
        NeovimRelease release = NeovimRelease.bundled();
        NeovimRelease.Archive mac = release.forPlatform(platform("ma")).orElseThrow();
        assertEquals("ma", release.bySha256(mac.sha256().toUpperCase()).orElseThrow().platform().code());
        assertTrue(release.bySha256("0".repeat(64)).isEmpty());
    }

    @Test
    void parsesOneEntry() throws Exception {
        NeovimRelease r = NeovimRelease.parse(new StringReader("""
                version=0.12.6
                url=https://github.com/neovim/neovim/releases/download/v{version}/{asset}
                la.asset=nvim-linux-arm64.tar.gz
                la.size=11367117
                la.sha256=%s
                la.tree=%s
                """.formatted(SHA, SHA)));
        assertEquals(new NeovimVersion(0, 12, 6), r.version());
        NeovimRelease.Archive a = r.forPlatform(platform("la")).orElseThrow();
        assertEquals(URI.create("https://github.com/neovim/neovim/releases/download/v0.12.6/nvim-linux-arm64.tar.gz"),
                a.url());
        assertEquals(11367117, a.size());
        assertTrue(r.forPlatform(platform("lx")).isEmpty(), "a platform missing from the file is offered nothing");
        assertEquals(URI.create("https://github.com/neovim/neovim/releases/tag/v0.12.6"), r.releasePage());
    }

    @Test
    void refusesABadHashSizeOrUrl() {
        String good = "version=0.12.6\nurl=https://github.com/neovim/neovim/releases/download/v{version}/{asset}\n"
                + "la.asset=nvim-linux-arm64.tar.gz\nla.size=1\nla.sha256=" + SHA + "\nla.tree=" + SHA + "\n";
        assertThrows(IllegalArgumentException.class,
                () -> NeovimRelease.parse(new StringReader(good.replace(SHA, "abc"))));
        assertThrows(IllegalArgumentException.class,
                () -> NeovimRelease.parse(new StringReader(good.replace("la.size=1", "la.size=0"))));
        assertThrows(IllegalArgumentException.class,
                () -> NeovimRelease.parse(new StringReader(good.replace("https://github.com", "http://example.com"))));
        assertThrows(IllegalArgumentException.class,
                () -> NeovimRelease.parse(new StringReader(good.replace("version=0.12.6", "version=latest"))));
    }

    @Test
    void platformCodesFromJavaNames() {
        assertEquals("lx", NeovimPlatform.of("Linux", "amd64").orElseThrow().code());
        assertEquals("la", NeovimPlatform.of("Linux", "aarch64").orElseThrow().code());
        assertEquals("mx", NeovimPlatform.of("Mac OS X", "x86_64").orElseThrow().code());
        assertEquals("ma", NeovimPlatform.of("Mac OS X", "aarch64").orElseThrow().code());
        assertEquals("wx", NeovimPlatform.of("Windows 11", "amd64").orElseThrow().code());
        assertEquals("wa", NeovimPlatform.of("Windows 11", "ARM64").orElseThrow().code());
        assertTrue(NeovimPlatform.of("Linux", "riscv64").isEmpty());
        assertTrue(NeovimPlatform.of("FreeBSD", "amd64").isEmpty());
        assertEquals("Windows arm64", NeovimPlatform.byCode("wa").orElseThrow().describe());
    }
}
