// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import common.Changelog;
import common.EclipseRelease;
import common.ToolFailure;

/**
 * The Eclipse tool's reading of download.eclipse.org and its edits ({@code releng/tools/eclipse/},
 * {@link EclipseFiles}). In this package, since those functions are the tool's own. The downloads,
 * the unpacking and the installs only run programs, and are tried by running the tool.
 */
class EclipseUpgradeTest {

    private static final EclipseRelease E2025_12 = EclipseRelease.ofName("2025-12");

    // ------------------------------------------------------------ download.eclipse.org

    @Test
    void theNewestDropIsDataJsonsFirstRelease() {
        String json = """
                {"releases": [
                  {"path": "drops4/R-4.41-202608281142", "status": "success", "label": "4.41"},
                  {"path": "drops4/R-4.40-202606010713", "label": "4.40"}],
                 "stableBuilds": [{"path": "drops4/S-4.42M1-202610101800"}]}
                """;
        assertEquals("R-4.41-202608281142", EclipseFiles.newestDrop(json));
        assertThrows(ToolFailure.class, () -> EclipseFiles.newestDrop("{\"releases\": []}"));
        assertThrows(ToolFailure.class, () -> EclipseFiles.newestDrop("{\"releases\": [{\"path\": \"drops4/S-4.42M1-1\"}]}"),
                "a milestone is not a release");
    }

    @Test
    void anUpdatesPageNamesItsReleasesNewestDrop() {
        String page = "<a href='R-4.38-202511271200/'>…</a> <a href='R-4.38-202512010920/'>…</a> <a href='R-4.37-202509011200/'>";
        assertEquals("R-4.38-202512010920", EclipseFiles.dropIn(page, E2025_12), "a respin: the later build");
        assertThrows(ToolFailure.class, () -> EclipseFiles.dropIn("<a href='R-4.37-202509011200/'>", E2025_12));
    }

    @Test
    void aDropIsBuiltWithinTwoMonthsOfItsRelease() {
        EclipseFiles.checkDropDate("R-4.41-202608281142");
        EclipseFiles.checkDropDate("R-4.34-202411201800");
        EclipseFiles.checkDropDate("R-4.38-202512010920");
        EclipseFiles.checkDropDate("R-4.38-202601151200"); // a respin after the release date
        assertThrows(ToolFailure.class, () -> EclipseFiles.checkDropDate("R-4.42-202603011200"),
                "4.42 built in March would mean a skipped quarter: the name count is off");
        assertEquals(EclipseRelease.ofName("2026-09"), EclipseFiles.releaseOf("R-4.41-202608281142"));
    }

    @Test
    void aChecksumIsReadByItsFileName() {
        String sums = "dd4e" + "0".repeat(124) + " *eclipse-platform-4.41-linux-gtk-x86_64.tar.gz\n"
                + "AB12" + "0".repeat(124) + "  eclipse-platform-4.41-win32-win32-x86_64.zip\n";
        assertEquals(Optional.of("dd4e" + "0".repeat(124)), EclipseFiles.sha512In(sums, "eclipse-platform-4.41-linux-gtk-x86_64.tar.gz"));
        assertEquals(Optional.of("ab12" + "0".repeat(124)), EclipseFiles.sha512In(sums, "eclipse-platform-4.41-win32-win32-x86_64.zip"),
                "sha512sum's two spaces, upper case");
        assertEquals(Optional.empty(), EclipseFiles.sha512In(sums, "eclipse-platform-4.41-linux-gtk-aarch64.tar.gz"));
        assertEquals("https://download.eclipse.org/eclipse/downloads/drops4/R-4.34-202411201800/checksum/eclipse-4.34-SUMSSHA512",
                EclipseFiles.oldChecksums("R-4.34-202411201800"));
    }

    @Test
    void eachMachineHasItsPlatformDownload() {
        EclipseRelease r = EclipseRelease.ofNumber("4.41");
        assertEquals("eclipse-platform-4.41-linux-gtk-x86_64.tar.gz", EclipseFiles.ciPlatformFile(r));
        assertEquals("eclipse-platform-4.41-macosx-cocoa-aarch64.dmg",
                EclipseFiles.platformFile(r, EclipseFiles.os("Mac OS X"), EclipseFiles.arch("aarch64")));
        assertEquals("eclipse-platform-4.41-win32-win32-x86_64.zip",
                EclipseFiles.platformFile(r, EclipseFiles.os("Windows 11"), EclipseFiles.arch("amd64")));
        assertEquals("eclipse-platform-4.41-linux-gtk-aarch64.tar.gz", EclipseFiles.platformFile(r, EclipseFiles.os("Linux"), "aarch64"));
        assertThrows(ToolFailure.class, () -> EclipseFiles.platformFile(r, EclipseFiles.os("FreeBSD"), "x86_64"));
    }

    // ------------------------------------------------------------ the Platform's files

    @Test
    void theJavaAnEclipseNeedsAndAJavaHas() {
        assertEquals(OptionalInt.of(21), EclipseFiles.requiredJava("-vmargs\n-Dosgi.requiredJavaVersion=21\n-Xms256m\n"));
        assertEquals(OptionalInt.empty(), EclipseFiles.requiredJava("-vmargs\n-Xms256m\n"));
        assertEquals(21, EclipseFiles.javaRelease("IMPLEMENTOR=\"Debian\"\nJAVA_VERSION=\"21.0.5\"\n"));
        assertEquals(8, EclipseFiles.javaRelease("JAVA_VERSION=\"1.8.0_412\"\n"));
        assertEquals(11, EclipseFiles.javaRelease("JAVA_VERSION=\"11.0.2\"\n"));
    }

    @Test
    void theTestJarsVersionsComeFromThePluginsFolder() {
        List<String> plugins = List.of("org.eclipse.text_3.14.200.v20240915-0736.jar", "org.eclipse.equinox.common_3.19.200.v20241004-0654.jar",
                "org.eclipse.core.commands_3.12.200.v20240627-1019.jar", "org.eclipse.swt_3.128.0.v20241113-2009.jar",
                "org.eclipse.swt.gtk.linux.x86_64_3.128.0.v20241113-2009.jar", "org.eclipse.jface_3.35.100.v20241003-1431.jar",
                "org.eclipse.jface.text_3.26.0.v20241115-1108.jar", "org.eclipse.core.runtime_3.32.0.v20241003-0436.jar",
                "org.eclipse.osgi_3.22.0.v20241030-2121.jar", "org.eclipse.ui.ide_3.22.400.v20241108-1031", "org.eclipse.textx_9.9.9.jar");
        Map<String, String> v = EclipseFiles.testJarVersions(plugins);
        assertEquals(List.copyOf(EclipseFiles.TEST_JARS.keySet()), List.copyOf(v.keySet()));
        assertEquals("3.14.200", v.get("eclipse.text.version"));
        assertEquals("3.128.0", v.get("eclipse.swt.version"), "the host bundle, not the platform fragment");
        assertEquals("3.35.100", v.get("eclipse.jface.version"), "jface, not jface.text");
        assertThrows(ToolFailure.class, () -> EclipseFiles.testJarVersions(plugins.subList(1, plugins.size())), "no org.eclipse.text");
    }

    // ------------------------------------------------------------ the edits

    @Test
    void aPomPropertyIsSetOnceOrRefused() {
        String pom = "<p>\n  <eclipse-repo.url>https://download.eclipse.org/releases/2024-12</eclipse-repo.url>\n"
                + "  <eclipse.text.version>3.14.200</eclipse.text.version>\n</p>\n";
        assertEquals(pom.replace("2024-12", "2025-12"),
                EclipseFiles.withProperty(pom, "eclipse-repo.url", EclipseFiles.repoUrl(E2025_12)));
        assertThrows(ToolFailure.class, () -> EclipseFiles.withProperty(pom, "eclipse.osgi.version", "1"));
        assertThrows(ToolFailure.class, () -> EclipseFiles.withProperty(pom + pom, "eclipse.text.version", "1"));
    }

    @Test
    void aSecondRaiseBeforeTheReleaseKeepsTheReleasedFloor() {
        String once = Changelog.withChangedEntry("## [Unreleased]\n\n## [0.2.10] - 2026-11-20\n",
                EclipseFiles.oldestEntry(EclipseRelease.ofName("2024-12"), EclipseRelease.ofName("2025-06")));
        Changelog.Removed earlier = Changelog.withoutEntry(once, EclipseFiles.OLDEST_ENTRY);
        assertEquals(Optional.of(EclipseRelease.ofName("2024-12")), EclipseFiles.wasIn(earlier.entry()));
        String twice = Changelog.withChangedEntry(earlier.changelog(),
                EclipseFiles.oldestEntry(EclipseFiles.wasIn(earlier.entry()).orElseThrow(), E2025_12));
        assertEquals(Changelog.withChangedEntry("## [Unreleased]\n\n## [0.2.10] - 2026-11-20\n",
                EclipseFiles.oldestEntry(EclipseRelease.ofName("2024-12"), E2025_12)), twice, "one entry, from the released 2024-12");
        assertTrue(EclipseFiles.newestEntry(EclipseRelease.ofName("2026-12")).startsWith("Each release is now also installed into Eclipse 2026-12 (4.42)"));
        assertTrue(EclipseFiles.NEWEST_ENTRY.matcher("- " + EclipseFiles.newestEntry(E2025_12)).find());
    }
}
