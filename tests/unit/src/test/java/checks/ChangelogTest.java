// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import common.Changelog;
import common.ToolFailure;

/**
 * The changelog edits the upgrade tools share ({@code releng/tools/common/Changelog}): where a new
 * <i>Changed</i> entry goes, and how a tool's earlier entry is found and taken out.
 */
class ChangelogTest {

    @Test
    void theEntryGoesFirstIntoAnExistingChangedGroup() {
        String changelog = Changelog.withChangedEntry("""
                ## [Unreleased]

                ### Changed

                - Something else.

                ## [0.2.3] - 2026-11-06
                """, "New.");
        assertEquals("""
                ## [Unreleased]

                ### Changed

                - New.
                - Something else.

                ## [0.2.3] - 2026-11-06
                """, changelog);
    }

    @Test
    void aNewChangedGroupGoesAfterAddedAndBeforeFixed() {
        String changelog = Changelog.withChangedEntry("""
                ## [Unreleased]

                ### Added

                - A tool.

                ### Fixed

                - A bug.

                ## [0.2.3] - 2026-11-06
                """, "New.");
        assertEquals("""
                ## [Unreleased]

                ### Added

                - A tool.

                ### Changed

                - New.

                ### Fixed

                - A bug.

                ## [0.2.3] - 2026-11-06
                """, changelog);
        assertThrows(ToolFailure.class, () -> Changelog.withChangedEntry("# Changelog\n", "New."));
    }

    @Test
    void anEarlierEntryIsTakenOutWithItsTextOnOneLine() {
        Changelog.Removed r = Changelog.withoutEntry("""
                ## [Unreleased]

                ### Changed

                - Tested with Eclipse up to
                  2026-12 (4.42).
                - Something else.

                ## [0.2.3] - 2026-11-06

                - Tested with Eclipse up to 2026-09 (4.41).
                """, Pattern.compile("^- Tested with Eclipse"));
        assertEquals("Tested with Eclipse up to 2026-12 (4.42).", r.entry());
        assertEquals("""
                ## [Unreleased]

                ### Changed

                - Something else.

                ## [0.2.3] - 2026-11-06

                - Tested with Eclipse up to 2026-09 (4.41).
                """, r.changelog(), "only Unreleased is looked at");
    }

    @Test
    void withNoEarlierEntryNothingIsTakenOut() {
        String text = "## [Unreleased]\n\n## [0.2.3] - 2026-11-06\n\n- Tested with Eclipse up to 2026-09 (4.41).\n";
        Changelog.Removed r = Changelog.withoutEntry(text, Pattern.compile("^- Tested with Eclipse"));
        assertNull(r.entry());
        assertEquals(text, r.changelog());
    }
}
