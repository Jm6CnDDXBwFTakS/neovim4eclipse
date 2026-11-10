// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import common.ToolFailure;
import common.Version;

/**
 * The Maven tool's decisions that need no network and no Maven run ({@code releng/tools/maven}):
 * which versions count, where the archives are, what the wrapper's settings file must say after
 * the plug-in wrote it, the week-old rule, and how {@code gpg}'s answer is read.
 */
class MavenUpgradeTest {

    private static final String METADATA = """
            <metadata>
              <groupId>org.apache.maven</groupId>
              <artifactId>apache-maven</artifactId>
              <versioning>
                <latest>4.0.0-rc-7</latest>
                <release>4.0.0-rc-7</release>
                <versions>
                  <version>3.8.9</version>
                  <version>3.9.15</version>
                  <version>3.9.16</version>
                  <version>4.0.0-alpha-2</version>
                  <version>4.0.0-rc-7</version>
                </versions>
              </versioning>
            </metadata>
            """;

    private static final String SHA = "5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce";

    private static final String PROPERTIES = """
            wrapperVersion=3.3.4
            distributionType=only-script
            distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
            distributionSha256Sum=%s
            """.formatted(SHA);

    @Test
    void onlyPlainReleasesCountNotCandidates() {
        assertEquals(List.of(Version.parse("3.8.9"), Version.parse("3.9.15"), Version.parse("3.9.16")),
                MavenFiles.releases(METADATA)); // 4.0.0-rc-7 is Central's <release>, but not a release
    }

    @Test
    void mavenStaysOnItsLine() {
        List<Version> releases = MavenFiles.releases(METADATA);
        assertEquals(Optional.of(Version.parse("3.9.16")), MavenFiles.newestOn(releases, "3.9"));
        assertEquals(Optional.of(Version.parse("3.8.9")), MavenFiles.newestOn(releases, "3.8"));
        assertEquals(Optional.empty(), MavenFiles.newestOn(releases, "4.0"));
        assertTrue(MavenFiles.isOn(Version.parse("3.9.16"), "3.9"));
        assertFalse(MavenFiles.isOn(Version.parse("4.0.0"), "3.9"));
        assertThrows(ToolFailure.class, () -> MavenFiles.newestOn(releases, "3")); // a line is major.minor
    }

    @Test
    void aListWithoutAnyReleaseIsRefused() {
        assertThrows(ToolFailure.class, () -> MavenFiles.releases("<versions><version>4.0.0-rc-7</version></versions>"));
    }

    @Test
    void theArchivesAreOnCentral() {
        assertEquals("https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip",
                MavenFiles.mavenZip(Version.parse("3.9.16")));
        assertEquals("https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper-distribution/3.3.4/"
                + "maven-wrapper-distribution-3.3.4-only-script.zip", MavenFiles.wrapperZip(Version.parse("3.3.4")));
    }

    @Test
    void theSettingsFileIsReadAsMvnwReadsIt() {
        MavenFiles.WrapperSettings s = MavenFiles.settings(PROPERTIES);
        assertEquals(Version.parse("3.3.4"), s.wrapper());
        assertEquals(Version.parse("3.9.16"), s.maven());
        assertEquals("only-script", s.type());
        assertEquals(SHA, s.sha256());
        // Without the hash, ./mvnw would run whatever it downloaded: the tool refuses such a file.
        assertThrows(ToolFailure.class, () -> MavenFiles.settings(PROPERTIES.replaceAll("distributionSha256Sum=.*", "")));
    }

    @Test
    void theRepositorysOwnSettingsFileReads() throws IOException {
        Path file = Path.of("../../.mvn/wrapper/maven-wrapper.properties");
        MavenFiles.WrapperSettings s = MavenFiles.settings(Files.readString(file));
        assertEquals(MavenFiles.mavenZip(s.maven()), s.distributionUrl(), "the build downloads Maven from Central");
        assertTrue(MavenFiles.isOn(s.maven(), "3.9"), "the build is on maven.line");
    }

    @Test
    void whatThePluginWroteMustBeExactlyTheExpectedFile() {
        MavenFiles.WrapperSettings expected = MavenFiles.settings(PROPERTIES);
        assertEquals(Optional.empty(), MavenFiles.difference(PROPERTIES, expected));
        // A mirror in ~/.m2/settings.xml ends up in distributionUrl: every other machine would use it.
        Optional<String> mirror = MavenFiles.difference(PROPERTIES.replace("https://repo.maven.apache.org/maven2/",
                "https://mirror.example.org/maven2/"), expected);
        assertTrue(mirror.orElse("").contains("distributionUrl"), mirror.toString());
        assertTrue(MavenFiles.difference(PROPERTIES.replaceAll("distributionSha256Sum=.*\n", ""), expected).orElse("")
                .contains("distributionSha256Sum is missing"));
        assertTrue(MavenFiles.difference(PROPERTIES + "wrapperSha256Sum=abc\n", expected).orElse("").contains("wrapperSha256Sum was added"));
    }

    @Test
    void thePluginRunsAtTheWrappersExactVersion() {
        List<String> goal = MavenFiles.wrapperGoal(Version.parse("3.3.4"), Version.parse("3.9.16"), SHA);
        assertTrue(goal.contains("org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper"), goal.toString());
        assertTrue(goal.contains("-N") && goal.contains("-C"), "root POM only, and a checksum mismatch fails: " + goal);
        assertTrue(goal.contains("-Dmaven=3.9.16") && goal.contains("-DdistributionSha256Sum=" + SHA), goal.toString());
    }

    @Test
    void aReleaseUnderAWeekOldIsYoung() {
        Instant now = Instant.parse("2026-11-10T12:00:00Z");
        assertTrue(MavenFiles.isYoung(now.minus(Duration.ofDays(6)), now));
        assertFalse(MavenFiles.isYoung(now.minus(Duration.ofDays(7)), now));
    }

    @Test
    void aGoodSignatureByAKnownKeyNamesItsSigner() {
        String status = """
                [GNUPG:] NEWSIG
                [GNUPG:] KEY_CONSIDERED 84789D24DF77A32433CE1F079EB80E92EB2135B1 0
                [GNUPG:] SIG_ID abc 2026-05-13 1778708341
                [GNUPG:] GOODSIG 9EB80E92EB2135B1 Slawomir Jaranowski <sjaranowski@apache.org>
                [GNUPG:] VALIDSIG 84789D24DF77A32433CE1F079EB80E92EB2135B1 2026-05-13 1778708341 0 4 0 1 10 00 84789D24DF77A32433CE1F079EB80E92EB2135B1
                [GNUPG:] TRUST_UNDEFINED 0 pgp
                """;
        assertEquals(Optional.of("Slawomir Jaranowski <sjaranowski@apache.org>, key 84789D24DF77A32433CE1F079EB80E92EB2135B1"),
                Signatures.signer(status));
        // A key that expired after it signed still counts: release managers' keys expire.
        assertTrue(Signatures.signer(status.replace("GOODSIG", "EXPKEYSIG")).isPresent());
    }

    @Test
    void gpgIsGivenOnlyPlainNamesInItsOwnFolder() {
        // Git for Windows' gpg (MSYS) misreads C:\Users\… as a relative path: no argument may be absolute.
        for (List<String> command : List.of(Signatures.importCommand(), Signatures.verifyCommand("apache-maven-3.9.16-bin.zip"))) {
            for (String arg : command) {
                assertFalse(arg.startsWith("/") || arg.contains("\\") || arg.matches("(?i)[a-z]:.*"), arg + " in " + command);
            }
            assertEquals(List.of("--homedir", "."), command.subList(1, 3), command.toString());
        }
        assertEquals(List.of("apache-maven-3.9.16-bin.zip.asc", "apache-maven-3.9.16-bin.zip"),
                Signatures.verifyCommand("apache-maven-3.9.16-bin.zip").subList(7, 9));
        for (String bad : List.of("/tmp/a.zip", "C:\\a.zip", "sub/a.zip", "--import")) {
            assertThrows(IllegalArgumentException.class, () -> Signatures.verifyCommand(bad), bad);
        }
    }

    @Test
    void aBadOrUnknownOrRevokedSignatureIsRefused() {
        assertEquals(Optional.empty(), Signatures.signer("[GNUPG:] BADSIG 9EB80E92EB2135B1 Someone\n"));
        assertEquals(Optional.empty(), Signatures.signer("[GNUPG:] ERRSIG 9EB80E92EB2135B1 1 10 00 1778708341 9\n[GNUPG:] NO_PUBKEY 9EB80E92EB2135B1\n"));
        assertEquals(Optional.empty(), Signatures.signer("""
                [GNUPG:] REVKEYSIG 9EB80E92EB2135B1 Someone
                [GNUPG:] VALIDSIG 84789D24DF77A32433CE1F079EB80E92EB2135B1 2026-05-13 1778708341 0 4 0 1 10 00 84789D24DF77A32433CE1F079EB80E92EB2135B1
                """));
        assertEquals(Optional.empty(), Signatures.signer("")); // gpg said nothing: no check passed
        assertEquals(Optional.empty(), Signatures.signer("""
                [GNUPG:] EXPSIG 9EB80E92EB2135B1 Someone
                [GNUPG:] VALIDSIG 84789D24DF77A32433CE1F079EB80E92EB2135B1 2026-05-13 1778708341 0 4 0 1 10 00 84789D24DF77A32433CE1F079EB80E92EB2135B1
                """), "an expired signature");
    }

    @Test
    void onlyADetachedSignatureIsChecked() {
        assertTrue(Signatures.isDetachedSignature("-----BEGIN PGP SIGNATURE-----\n\niQIz…\n-----END PGP SIGNATURE-----\n"));
        // A signed message carries its own data: an old gpg would check that instead of the archive.
        assertFalse(Signatures.isDetachedSignature("-----BEGIN PGP MESSAGE-----\n…"));
        assertFalse(Signatures.isDetachedSignature("<html>Not Found</html>"));
    }
}
