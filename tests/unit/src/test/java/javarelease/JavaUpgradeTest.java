// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package javarelease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import common.Changelog;
import common.ToolFailure;
import common.VersionCopies;

/**
 * The Java tool's rules ({@code releng/tools/javarelease/}): which releases it raises to, its
 * edits of the POM and the changelog, and that the copies it rewrites are this repository's.
 * The smoke install and the test run on another JDK are not run here; the tool itself does them.
 */
class JavaUpgradeTest {

    private static final Path ROOT = Path.of("../..");

    @Test
    void onlyLongTermSupportReleasesCount() {
        for (int lts : new int[] {17, 21, 25, 29}) {
            assertTrue(JavaFiles.isLts(lts), lts + " is an LTS release");
        }
        for (int other : new int[] {11, 16, 22, 23, 24, 26, 27, 28}) {
            assertFalse(JavaFiles.isLts(other), other + " is not one the tool raises to (11 was LTS, before the every-fourth rule)");
        }
    }

    @Test
    void theToolOnlyRaisesToAHigherLongTermSupportRelease() {
        JavaFiles.checkTarget(21, 25);
        assertThrows(ToolFailure.class, () -> JavaFiles.checkTarget(21, 21), "the release in use");
        assertThrows(ToolFailure.class, () -> JavaFiles.checkTarget(25, 21), "lower");
        assertThrows(ToolFailure.class, () -> JavaFiles.checkTarget(21, 24), "not LTS");
    }

    @Test
    void thePomPropertyIsReadAndSet() {
        String pom = "<properties>\n    <java.release>21</java.release>\n    <x>21</x>\n</properties>\n";
        assertEquals(21, JavaFiles.releaseIn(pom));
        assertEquals(pom.replace("<java.release>21<", "<java.release>25<"), JavaFiles.withRelease(pom, 25), "only the property moves");
        assertThrows(ToolFailure.class, () -> JavaFiles.releaseIn("<properties/>"));
        assertThrows(ToolFailure.class, () -> JavaFiles.releaseIn(pom + pom), "two properties");
    }

    @Test
    void aJdksReleaseFileGivesItsFeatureRelease() {
        assertEquals(OptionalInt.of(25), JavaFiles.featureRelease("IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"25.0.1\"\n"));
        assertEquals(OptionalInt.of(21), JavaFiles.featureRelease("JAVA_VERSION=\"21\"\n"));
        assertEquals(OptionalInt.empty(), JavaFiles.featureRelease("JAVA_RUNTIME_VERSION=\"25.0.1+8\"\n"), "not the JAVA_VERSION line");
    }

    @Test
    void aSecondRaiseBeforeAReleaseReplacesTheFirstEntry() {
        String changelog = "# Changelog\n\n## [Unreleased]\n\n## [0.3.0] - 2026-12-01\n";
        String once = Changelog.withChangedEntry(changelog, JavaFiles.changelogEntry(25));
        assertTrue(once.contains("- **Java 25 or newer** is now required."), once);
        Changelog.Removed earlier = Changelog.withoutEntry(once, JavaFiles.ENTRY);
        String twice = Changelog.withChangedEntry(earlier.changelog(), JavaFiles.changelogEntry(29));
        assertTrue(twice.contains("**Java 29 or newer**") && !twice.contains("**Java 25 or newer**"),
                "users go from the released Java straight to the newest: " + twice);
    }

    /**
     * Every copy the tool rewrites matches this repository today, holds {@code <java.release>},
     * and holds the new release after a rewrite: the patterns cannot drift from the files.
     */
    @Test
    void theListedCopiesAreThisRepositorysAndMove() throws Exception {
        int now = JavaFiles.releaseIn(Files.readString(ROOT.resolve("pom.xml")));
        List<VersionCopies.Copy> copies = VersionCopies.parse(Files.readString(ROOT.resolve(VersionCopies.FILE))).get(JavaFiles.PROPERTY);
        assertTrue(copies != null && copies.size() >= 5, VersionCopies.FILE + " lists the Java release's copies");
        for (VersionCopies.Copy copy : copies) {
            String text = Files.readString(ROOT.resolve(copy.file()));
            List<String> found = VersionCopies.versionsIn(text, copy);
            assertFalse(found.isEmpty(), copy.file() + ": nothing matches " + copy.pattern());
            assertTrue(found.stream().allMatch(v -> v.equals(Integer.toString(now))), copy.file() + ": " + found);
            String moved = VersionCopies.rewrite(text, copy, "99");
            assertTrue(VersionCopies.versionsIn(moved, copy).stream().allMatch(v -> v.equals("99")), copy.file() + " after the rewrite");
            assertEquals(text.length() + found.size() * (2 - Integer.toString(now).length()), moved.length(),
                    copy.file() + ": only the numbers change");
        }
    }

    /** The --java folder as the tool prints it again: absolute, without the separator Tab leaves at its end. */
    @Test
    void theHomeIsAbsoluteWithoutTheLastSeparator() {
        org.junit.jupiter.api.Assumptions.assumeFalse(common.Shell.WINDOWS, "a POSIX path");
        java.nio.file.Path home = JavaFiles.home("jdk-25/");
        assertTrue(home.isAbsolute(), home.toString());
        assertEquals(java.nio.file.Path.of("").toAbsolutePath().resolve("jdk-25"), home);
        assertFalse(home.toString().endsWith("/"));
    }
}
