// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import common.ToolFailure;
import common.VersionCopies;

/**
 * The Ubuntu tool's rules ({@code releng/tools/runner/}): reading GitHub's list of runner images,
 * which release it moves to, its edit of the POM, and that the copies it rewrites are this
 * repository's. The download of the list and the commit are not run here; the tool itself does them.
 */
class UbuntuUpgradeTest {

    private static final Path ROOT = Path.of("../..");

    /**
     * The table as the runner-images README had it in November 2026, the badges shortened: x64 and
     * arm64 rows for each Ubuntu, a slim image, then macOS and Windows; plus a beta row for 28.04,
     * as a new release first appears.
     */
    private static final String README = """
            # GitHub Actions Runner Images

            ## Available Images

            | Image | Architecture | YAML Label | Included Software |
            | --------------------|---------------------|--------------------|---------------------|
            | Ubuntu 28.04 <sup>beta</sup><br>![Endpoint Badge](https://img.shields.io/x) | x64 | `ubuntu-28.04` | [ubuntu-28.04] |
            | Ubuntu 26.04<br>![Endpoint Badge](https://img.shields.io/x) | x64 | `ubuntu-latest` or `ubuntu-26.04` | [ubuntu-26.04] |
            | Ubuntu 26.04 Arm64<br>![Endpoint Badge](https://img.shields.io/x) | arm64 | `ubuntu-26.04-arm` | [ubuntu-26.04-arm64] |
            | Ubuntu 24.04<br>![Endpoint Badge](https://img.shields.io/x) | x64 | `ubuntu-24.04` | [ubuntu-24.04] |
            | Ubuntu 24.04 Arm64<br>![Endpoint Badge](https://img.shields.io/x) | arm64 | `ubuntu-24.04-arm` | [ubuntu-24.04-arm64] |
            | Ubuntu Slim<br>![Endpoint Badge](https://img.shields.io/x) | x64 | `ubuntu-slim` | [ubuntu-slim] |
            | macOS 26 Arm64<br>![Endpoint Badge](https://img.shields.io/x) | arm64 | `macos-latest`, `macos-26` | [macOS-26-arm64] |
            | Windows Server 2025<br>![Endpoint Badge](https://img.shields.io/x) | x64 | `windows-latest`, `windows-2025` | [windows-2025] |

            ### Label scheme
            """;

    @Test
    void theTableGivesTheX64UbuntuReleasesNewestFirst() {
        assertEquals(List.of(new UbuntuFiles.Image("28.04", true), new UbuntuFiles.Image("26.04", false),
                new UbuntuFiles.Image("24.04", false)), UbuntuFiles.images(README),
                "arm64, slim, macOS and Windows rows left out; the beta marked");
    }

    @Test
    void theNewestReleasedSkipsABeta() {
        assertEquals(Optional.of(new UbuntuFiles.Image("26.04", false)), UbuntuFiles.newestReleased(UbuntuFiles.images(README)));
    }

    /**
     * Released is a known form, the bare name: any other marking of a trial release counts as not
     * released, and a release with a trial row besides its released one is not released either.
     */
    @Test
    void onlyABareNameIsReleased() {
        String table = """
                | Image | Architecture | YAML Label |
                |---|---|---|
                | Ubuntu 30.04 (preview)<br>![Endpoint Badge](https://img.shields.io/beta-free) | x64 | `ubuntu-30.04` |
                | Ubuntu 28.04<br>![Endpoint Badge](https://img.shields.io/x) | x64 | `ubuntu-28.04` |
                | Ubuntu 28.04 <sup>beta</sup> large | x64 | `ubuntu-28.04` |
                | Ubuntu 26.04<br>![Endpoint Badge](https://img.shields.io/ubuntu-beta-badge.json) | x64 | `ubuntu-26.04` |
                | Ubuntu 24.04<br/>![Endpoint Badge](https://img.shields.io/x) | x64 | `ubuntu-24.04` |
                """;
        assertEquals(List.of(new UbuntuFiles.Image("30.04", true), new UbuntuFiles.Image("28.04", true),
                new UbuntuFiles.Image("26.04", false), new UbuntuFiles.Image("24.04", false)), UbuntuFiles.images(table),
                "30.04 is marked another way; 28.04 has a trial row too; 26.04's badge saying beta does not count;"
                        + " 24.04's <br/> is a <br>");
    }

    /** Columns are found by their header, so a column added in front does not shift them. */
    @Test
    void columnsAreFoundByName() {
        String moved = """
                | Image | Status | YAML Label | Architecture |
                |---|---|---|---|
                | Ubuntu 26.04 | GA | `ubuntu-26.04` | x64 |
                """;
        assertEquals(List.of(new UbuntuFiles.Image("26.04", false)), UbuntuFiles.images(moved));
    }

    /** A README whose table changed shape stops the tool instead of a guess. */
    @Test
    void aTableItCannotReadStopsTheTool() {
        assertThrows(ToolFailure.class, () -> UbuntuFiles.images("# Runner images\n\nNo table here.\n"));
        assertThrows(ToolFailure.class, () -> UbuntuFiles.images("| OS | Label |\n|---|---|\n| Ubuntu 26.04 | `ubuntu-26.04` |\n"),
                "no Architecture column");
        assertThrows(ToolFailure.class, () -> UbuntuFiles.images("""
                | Image | Architecture | YAML Label |
                |---|---|---|
                | Ubuntu 26.04 badge | x64 | `ubuntu-26.04` |
                | Ubuntu 24.04 badge | x64 | `ubuntu-24.04` |
                """), "every row reads as a beta: the names changed shape (here the badge without <br>)");
    }

    @Test
    void theToolMovesOnlyToAnotherListedReleasedRelease() {
        List<UbuntuFiles.Image> images = UbuntuFiles.images(README);
        UbuntuFiles.checkTarget("24.04", "26.04", images);
        UbuntuFiles.checkTarget("26.04", "24.04", images); // back, to undo a broken move
        assertThrows(ToolFailure.class, () -> UbuntuFiles.checkTarget("26.04", "26.04", images), "the release in use");
        assertThrows(ToolFailure.class, () -> UbuntuFiles.checkTarget("26.04", "28.04", images), "a beta");
        assertThrows(ToolFailure.class, () -> UbuntuFiles.checkTarget("26.04", "30.04", images), "not listed");
    }

    @Test
    void aReleaseIsTwoDigitsADotTwoDigits() {
        assertTrue(UbuntuFiles.isVersion("28.04"));
        for (String other : new String[] {"28", "28.4", "latest", "ubuntu-28.04", "28.04-arm"}) {
            assertFalse(UbuntuFiles.isVersion(other), other);
        }
    }

    @Test
    void thePomPropertyIsReadAndSet() {
        String pom = "<properties>\n    <ci.ubuntu>26.04</ci.ubuntu>\n    <x>26.04</x>\n</properties>\n";
        assertEquals("26.04", UbuntuFiles.versionIn(pom));
        assertEquals(pom.replace("<ci.ubuntu>26.04<", "<ci.ubuntu>28.04<"), UbuntuFiles.withVersion(pom, "28.04"), "only the property moves");
        assertThrows(ToolFailure.class, () -> UbuntuFiles.versionIn("<properties/>"));
        assertThrows(ToolFailure.class, () -> UbuntuFiles.versionIn(pom + pom), "two properties");
    }

    /**
     * Every copy the tool rewrites matches this repository today, holds {@code <ci.ubuntu>}, and
     * holds the new release after a rewrite: the patterns cannot drift from the workflows.
     */
    @Test
    void theListedCopiesAreThisRepositorysAndMove() throws Exception {
        String now = UbuntuFiles.versionIn(Files.readString(ROOT.resolve("pom.xml")));
        List<VersionCopies.Copy> copies = VersionCopies.parse(Files.readString(ROOT.resolve(VersionCopies.FILE))).get(UbuntuFiles.PROPERTY);
        assertTrue(copies != null && copies.size() >= 4, VersionCopies.FILE + " lists the workflows that run on Ubuntu");
        for (VersionCopies.Copy copy : copies) {
            String text = Files.readString(ROOT.resolve(copy.file()));
            List<String> found = VersionCopies.versionsIn(text, copy);
            assertFalse(found.isEmpty(), copy.file() + ": nothing matches " + copy.pattern());
            assertTrue(found.stream().allMatch(now::equals), copy.file() + ": " + found);
            String moved = VersionCopies.rewrite(text, copy, "99.04");
            assertTrue(VersionCopies.versionsIn(moved, copy).stream().allMatch("99.04"::equals), copy.file() + " after the rewrite");
            assertEquals(text.length(), moved.length(), copy.file() + ": only the numbers change");
        }
    }
}
