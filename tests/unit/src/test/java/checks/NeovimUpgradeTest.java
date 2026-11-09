// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import common.Changelog;
import common.ToolFailure;
import common.Version;
import common.VersionCopies;
import neovim.NeovimFiles;

/**
 * The Neovim tool's edits ({@code releng/tools/neovim/}): the copies of the version it rewrites
 * ({@code releng/version-copies.txt}) and the changelog entry it writes. The downloads, the plug-in
 * compile and the archive check only run programs, and are tried by running the tool.
 */
class NeovimUpgradeTest {

    private static final Version FROM = Version.parse("0.12.6");
    private static final Version TO = Version.parse("0.13.0");

    // ------------------------------------------------------------ version-copies.txt

    @Test
    void theListIsReadBySection() {
        Map<String, List<VersionCopies.Copy>> s = VersionCopies.parse("""
                # a comment
                [neovim]
                README.md  \\bNeovim (\\d+\\.\\d+\\.\\d+)
                docs/A.md  only (\\d+\\.\\d+\\.\\d+) is
                [java]
                .java-version  ^(\\d+)$
                """);
        assertEquals(List.of("neovim", "java"), List.copyOf(s.keySet()));
        assertEquals("docs/A.md", s.get("neovim").get(1).file());
        assertEquals("only (\\d+\\.\\d+\\.\\d+) is", s.get("neovim").get(1).pattern().pattern(), "spaces inside the pattern are kept");
        assertThrows(ToolFailure.class, () -> VersionCopies.parse("README.md  x"), "a line outside a section");
        assertThrows(ToolFailure.class, () -> VersionCopies.parse("[n]\nREADME.md  Neovim \\d+"), "no group for the version");
        assertThrows(ToolFailure.class, () -> VersionCopies.parse("[n]\nREADME.md  (unclosed"), "not a regular expression");
    }

    @Test
    void onlyTheMatchedVersionsAreRewritten() {
        VersionCopies.Copy copy = VersionCopies.parse("[neovim]\nREADME.md  \\bNeovim (\\d+\\.\\d+\\.\\d+)").get("neovim").get(0);
        String text = "Get Neovim 0.12.6… installs it.\nAs Neovim sends it (checked on 0.12.6).\nNeovim 0.12.6 or newer.\n";
        assertEquals(List.of("0.12.6", "0.12.6"), VersionCopies.versionsIn(text, copy));
        assertEquals("Get Neovim 0.13.0… installs it.\nAs Neovim sends it (checked on 0.12.6).\nNeovim 0.13.0 or newer.\n",
                VersionCopies.rewrite(text, copy, "0.13.0"));
    }

    // ------------------------------------------------------------ the changelog entry

    private static final String ENTRY = NeovimFiles.entry(FROM, TO);

    @Test
    void theEntryNamesBothVersionsAndIsWrapped() {
        assertTrue(ENTRY.contains("Neovim 0.13.0") && ENTRY.contains("it was 0.12.6"), ENTRY);
        String changelog = Changelog.withChangedEntry("## [Unreleased]\n\n## [0.2.3] - 2026-11-06\n", ENTRY);
        for (String line : changelog.lines().toList()) {
            assertTrue(line.length() <= 100, "longer than 100: " + line);
        }
        assertTrue(changelog.startsWith("## [Unreleased]\n\n### Changed\n\n- *Get Neovim* installs Neovim 0.13.0"), changelog);
        assertTrue(changelog.contains("\n  "), "continued with two spaces: " + changelog);
        assertTrue(changelog.endsWith("start.\n\n## [0.2.3] - 2026-11-06\n"), changelog);
    }

    @Test
    void aSecondMoveBeforeTheReleaseReplacesTheFirstsEntry() {
        String once = NeovimFiles.withNeovimEntry("## [Unreleased]\n\n## [0.2.4] - 2026-11-20\n", FROM, Version.parse("0.12.7"));
        String twice = NeovimFiles.withNeovimEntry(once, Version.parse("0.12.7"), Version.parse("0.12.8"));
        assertEquals(Changelog.withChangedEntry("## [Unreleased]\n\n## [0.2.4] - 2026-11-20\n",
                NeovimFiles.entry(FROM, Version.parse("0.12.8"))), twice, "one entry, from the released 0.12.6");
        assertTrue(!twice.contains("0.12.7"), twice);
    }
}
