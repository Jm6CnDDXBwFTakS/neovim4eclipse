// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import common.EclipseRelease;
import common.ToolFailure;

/**
 * An Eclipse release's two names ({@code releng/tools/common/EclipseRelease}), and the smoke job's
 * matrix the Eclipse tool reads and rewrites.
 */
class EclipseReleaseTest {

    @Test
    void theNameAndTheNumberMapOntoEachOther() {
        assertEquals("4.34", EclipseRelease.ofName("2024-12").number());
        assertEquals("4.35", EclipseRelease.ofName("2025-03").number());
        assertEquals("4.41", EclipseRelease.ofName("2026-09").number());
        assertEquals("2026-09", EclipseRelease.ofNumber("4.41").name());
        assertEquals("2027-03", EclipseRelease.ofNumber("4.43").name());
        assertEquals("2024-12 (4.34)", EclipseRelease.ofNumber("4.34").toString());
        assertTrue(EclipseRelease.ofName("2025-12").compareTo(EclipseRelease.ofNumber("4.41")) < 0);
    }

    @Test
    void otherSpellingsAreRefused() {
        for (String bad : List.of("2024-11", "4.34", "2024-12-01", "24-12")) {
            assertThrows(ToolFailure.class, () -> EclipseRelease.ofName(bad), bad);
        }
        for (String bad : List.of("4.34.0", "2024-12", "3.8", "4.33")) {
            assertThrows(ToolFailure.class, () -> EclipseRelease.ofNumber(bad), bad);
        }
    }

    private static final String MATRIX = """
            matrix:
              include:
                # The oldest supported release, 2024-12.
                - eclipse: '4.34'
                  platform-url: https://example.org/R-4.34-1/eclipse-platform-4.34-linux-gtk-x86_64.tar.gz
                  platform-sha512: aa11
                # The newest.
                - eclipse: '4.41'
                  platform-url: https://example.org/R-4.41-2/eclipse-platform-4.41-linux-gtk-x86_64.tar.gz
                  platform-sha512: bb22
            steps:
            """;

    @Test
    void theSmokeEntriesAreReadInOrder() {
        List<EclipseRelease.SmokeEntry> e = EclipseRelease.smokeEntries(MATRIX);
        assertEquals(2, e.size());
        assertEquals(new EclipseRelease.SmokeEntry("4.41", "https://example.org/R-4.41-2/eclipse-platform-4.41-linux-gtk-x86_64.tar.gz",
                "bb22"), e.get(1));
    }

    @Test
    void oneEntryIsRewrittenAndEverythingElseKept() {
        String moved = EclipseRelease.withSmokeEntry(MATRIX, 1, new EclipseRelease.SmokeEntry("4.42", "https://example.org/new.tar.gz", "cc33"));
        assertEquals(MATRIX.replace("'4.41'", "'4.42'")
                .replace("https://example.org/R-4.41-2/eclipse-platform-4.41-linux-gtk-x86_64.tar.gz", "https://example.org/new.tar.gz")
                .replace("bb22", "cc33"), moved);
        assertThrows(ToolFailure.class, () -> EclipseRelease.withSmokeEntry(MATRIX, 2, e("4.42")));
    }

    private static EclipseRelease.SmokeEntry e(String number) {
        return new EclipseRelease.SmokeEntry(number, "u", "s");
    }
}
