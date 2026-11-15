// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import common.Ask;
import common.EclipseCopy;
import common.ToolFailure;
import common.UpdateSite;
import common.Version;

/**
 * InstallCheck's decisions ({@code releng/tools/checks/InstallCheckTool}): which releases the site
 * lists, the director's arguments and their trust, the record the Release tool reads. The install
 * itself runs an Eclipse and the network, and is tried by running the tool.
 */
class InstallCheckTest {

    private static final String SITE = "https://jm6cnddxbwftaks.github.io/neovim4eclipse/";
    private static final Version V = Version.parse("0.2.4");

    @Test
    void theReleasesAreTheSitesChildren() {
        String composite = """
                <children size='3'>
                    <child location='releases/0.1.9'/>
                    <child location='releases/0.2.10'/>
                    <child location='releases/0.2.4'/>
                    <child location='nightly'/>
                    <child  location="releases/0.2.5/"/>
                </children>
                """;
        List<Version> releases = UpdateSite.releases(composite);
        assertEquals(List.of(Version.parse("0.1.9"), Version.parse("0.2.10"), V, Version.parse("0.2.5")), releases,
                "double quotes and a trailing / read too");
        assertEquals(Version.parse("0.2.10"), releases.stream().max(Version::compareTo).orElseThrow(), "by number, not by text");
    }

    @Test
    void theDirectorTrustsOnlyTheSiteAndTheReleaseKey() {
        List<String> args = InstallCheckTool.installArguments(SITE, V, "A91F53D8931B7FD0D14686186CC9E446147B063C");
        assertEquals(SITE + "releases/0.2.4/", after(args, "-repository"));
        assertEquals("https://jm6cnddxbwftaks.github.io/", after(args, "-trustedAuthorities"), "the host, with its /");
        assertEquals("a91f53d8931b7fd0d14686186cc9e446147b063c", after(args, "-trustedPGPKeys"), "in lower case, as p2 compares it");
        assertTrue(args.contains("-trustSignedContentOnly"));
        assertTrue(after(args, "-trustedCertificates").startsWith("-"), "no value: no self-made certificate is trusted");
        assertFalse(args.contains("-uninstallIU"), "the copy's plug-in goes in a run of its own, before");
        assertEquals(EclipseCopy.FEATURE, after(InstallCheckTool.uninstallArguments(), "-uninstallIU"));
    }

    @Test
    void theTrustChoicesLeaveThePrefsAndNothingElse() {
        String prefs = "eclipse.preferences.version=1\n"
                + "pgp.trustedPublicKeys=-----BEGIN PGP MESSAGE-----\\nVersion\\: BCPG v1.85\\n\\nuQINBGSil\n"
                + "trustAlways=false\n"
                + "trustedAuthorities=https\\://download.eclipse.org\n";
        assertEquals("eclipse.preferences.version=1\ntrustedAuthorities=https\\://download.eclipse.org\n",
                InstallCheckTool.withoutTrustChoices(prefs), "p2's own layout: the keys on one line; trustAlways goes too");
        assertEquals("a=1\n# trustAlways=true\n", InstallCheckTool.withoutTrustChoices(
                "a=1\n  trustAlways = true\ntrustAllAuthorities:true\n# trustAlways=true\ntrustedCertificates\tMCMG\n"),
                "blanks before the key, and =, : or a blank after it; a comment stays");
        assertEquals("a=x\\\n trustAlways=true\nb=2\n", InstallCheckTool.withoutTrustChoices("a=x\\\n trustAlways=true\nb=2\n"),
                "a line that goes on another entry's value is that value, not a key");
        assertEquals("a=1\r\nb=2",
                InstallCheckTool.withoutTrustChoices("a=1\r\npgp.trustedPublicKeys=x\\\r\n  y\\\\\\\n  z\r\nb=2"),
                "lines ending in an odd number of \\ go on; Windows line ends and a last line without one kept");
        assertEquals("a=x\\\\\nb=2\n", InstallCheckTool.withoutTrustChoices("a=x\\\\\nb=2\n"), "nothing to drop");
        assertEquals("other.pgp.trustedPublicKeys=1\n", InstallCheckTool.withoutTrustChoices("other.pgp.trustedPublicKeys=1\n"),
                "only the key itself");
    }

    @Test
    void theCopyForgetsTheKeysYourEclipseKeeps(@TempDir Path dir) throws IOException {
        assertEquals(0, InstallCheckTool.forgetTrustChoices(dir.resolve("copy")), "no p2 folder: nothing to do");

        Path settings = Path.of("p2", "org.eclipse.equinox.p2.engine", "profileRegistry", "SDKProfile.profile", ".data",
                ".settings", "org.eclipse.equinox.p2.engine.prefs");
        Path yours = dir.resolve("yours").resolve(settings);
        Path copied = dir.resolve("copy").resolve(settings);
        Files.createDirectories(yours.getParent());
        Files.createDirectories(copied.getParent());
        String text = "eclipse.preferences.version=1\npgp.trustedPublicKeys=KEYS\ntrustAlways=true\nother=1\n";
        Files.writeString(yours, text);
        Files.createLink(copied, yours); // as EclipseCopy may link a file the copy only reads
        Files.createDirectories(dir.resolve("copy/p2/org.eclipse.equinox.p2.engine/profileRegistry/Other.profile"));

        assertEquals(1, InstallCheckTool.forgetTrustChoices(dir.resolve("copy")), "one profile with prefs");
        assertEquals("eclipse.preferences.version=1\nother=1\n", Files.readString(copied));
        assertEquals(text, Files.readString(yours), "your Eclipse keeps its keys: the link is broken, not written through");
        assertEquals(0, InstallCheckTool.forgetTrustChoices(dir.resolve("copy")), "a second time: nothing left");
    }

    private static final String KEY = "a91f53d8931b7fd0d14686186cc9e446147b063c";
    private static final String SIGNED = """
            The following IUs are fetched from https://jm6cnddxbwftaks.github.io/neovim4eclipse/releases/0.2.4/
              io.github.jm6cnddxbwftaks.neovim4eclipse 0.2.4
              io.github.jm6cnddxbwftaks.neovim4eclipse.feature.feature.group 0.2.4
              io.github.jm6cnddxbwftaks.neovim4eclipse.feature.feature.jar 0.2.4
                zipped=Instruction[true,null]
            The following artifacts are signed by PGP key a91f53d8931b7fd0d14686186cc9e446147b063c
              osgi.bundle,io.github.jm6cnddxbwftaks.neovim4eclipse,0.2.4 -> /c/plugins/io.github.jm6cnddxbwftaks.neovim4eclipse_0.2.4.jar
              org.eclipse.update.feature,io.github.jm6cnddxbwftaks.neovim4eclipse.feature,0.2.4 -> /c/features/f_0.2.4
            Operation completed in 2392 ms.
            """;

    @Test
    void aReleaseSignedByTheReleaseKeyIsTrusted() {
        assertEquals(Optional.empty(), InstallCheckTool.trustProblem(SIGNED, KEY.toUpperCase(java.util.Locale.ROOT), V));
    }

    /** What p2 accepts anyway must still fail the check: a certificate, another key, nothing at all. */
    @Test
    void anythingElseInTheTrustReportFails() {
        String certificate = SIGNED.replace("Operation completed", """
                The artifacts below are signed by the following unanchored trust chain
                  CN=someone
                    osgi.bundle,evil,1.0.0 -> /c/plugins/evil.jar
                Operation completed""");
        assertTrue(InstallCheckTool.trustProblem(certificate, KEY, V).orElseThrow().contains("unanchored"));
        assertTrue(InstallCheckTool.trustProblem(SIGNED.replace("signed by PGP key a91f", "signed by PGP key 0000"), KEY, V).isPresent(),
                "another key");
        assertTrue(InstallCheckTool.trustProblem(SIGNED + "The following artifacts are unsigned\n  osgi.bundle,x,1 -> /x\n", KEY, V)
                .isPresent(), "unsigned");
        // A jar signed by a certificate p2 trusts without asking is in no section: the release key's lines are missing.
        String noBundle = SIGNED.replaceAll("(?m)^  osgi\\.bundle.*\\n", "");
        assertTrue(InstallCheckTool.trustProblem(noBundle, KEY, V).orElseThrow().contains("the plug-in"), noBundle);
        assertTrue(InstallCheckTool.trustProblem(SIGNED.replace(",0.2.4 ->", ",0.2.3 ->"), KEY, V).isPresent(), "another version");
    }

    /**
     * p2 lists nothing a key it already trusts signed (Eclipse's own keys, say), so a unit fetched
     * beyond the release's own fails the check even with every release line in place.
     */
    @Test
    void aUnitBeyondTheReleaseFails() {
        String extra = SIGNED.replace("  io.github.jm6cnddxbwftaks.neovim4eclipse 0.2.4\n",
                "  io.github.jm6cnddxbwftaks.neovim4eclipse 0.2.4\n  org.example.extra 1.0.0\n");
        assertTrue(InstallCheckTool.trustProblem(extra, KEY, V).orElseThrow().contains("org.example.extra"), extra);
        String older = SIGNED.replace("feature.feature.jar 0.2.4", "feature.feature.jar 0.2.3");
        assertTrue(InstallCheckTool.trustProblem(older, KEY, V).orElseThrow().contains("0.2.3"), "another version of a release unit");
    }

    /** Only a folder this tool made, an empty one, or the default is deleted to start afresh. */
    @Test
    void onlyItsOwnFolderIsDeleted(@TempDir Path dir) throws IOException {
        assertTrue(InstallCheckTool.mayDelete(dir.resolve("new")), "not there yet");
        assertTrue(InstallCheckTool.mayDelete(dir), "empty");
        Files.writeString(dir.resolve("notes.txt"), "mine");
        assertFalse(InstallCheckTool.mayDelete(dir), "someone else's files");
        Files.writeString(dir.resolve(InstallCheckTool.MARK), "");
        assertTrue(InstallCheckTool.mayDelete(dir), "marked");
    }

    @Test
    void p2sVersionMayCarryAQualifier() {
        assertTrue(InstallCheckTool.isVersion("0.2.4", V));
        assertTrue(InstallCheckTool.isVersion("0.2.4.v20261106", V));
        assertFalse(InstallCheckTool.isVersion("0.2.40", V));
        assertFalse(InstallCheckTool.isVersion("0.2.3", V));
    }

    @Test
    void theStartupLogIsLeftOutOfTheDirectorsOutput() {
        String output = """
                Nov 06, 2026 6:14:04 PM org.apache.aries.spifly.BaseActivator log
                INFO: Registered provider org.slf4j.simple.SimpleServiceProvider of service org.slf4j.spi.SLF4JServiceProvider
                Installing io.github.jm6cnddxbwftaks.neovim4eclipse.feature.feature.group 0.2.4.
                The following artifacts are signed by PGP key a91f53d8931b7fd0d14686186cc9e446147b063c
                Operation completed in 1890 ms.
                """;
        assertEquals(List.of("Installing io.github.jm6cnddxbwftaks.neovim4eclipse.feature.feature.group 0.2.4.",
                "The following artifacts are signed by PGP key a91f53d8931b7fd0d14686186cc9e446147b063c",
                "Operation completed in 1890 ms."), InstallCheckTool.directorLines(output));
    }

    @Test
    void theRecordNamesTheVersion() {
        assertEquals(Optional.of(V), InstallCheckTool.recorded("0.2.4 1794007146\n"));
        assertEquals(Optional.empty(), InstallCheckTool.recorded(""));
        assertEquals(Optional.empty(), InstallCheckTool.recorded("v0.2.4 1794007146"));
    }

    /** The printed command to start the copy repeats where this run found Eclipse and its folder. */
    @Test
    void theStartCommandKeepsTheEclipseAndTheFolder() {
        assertEquals("java releng/tools/checks/InstallCheck.java 0.2.4 --yes",
                InstallCheckTool.Options.parse(new String[] {"0.2.4"}).startCommand(V));
        assertEquals("java releng/tools/checks/InstallCheck.java 0.2.4 --eclipse /opt/eclipse/current/eclipse --yes",
                InstallCheckTool.Options.parse(new String[] {"--eclipse", "/opt/eclipse/current/eclipse", "0.2.4", "--yes"})
                        .startCommand(V));
        assertEquals("java releng/tools/checks/InstallCheck.java 0.2.4 --eclipse \"C:\\Program Files\\Eclipse\\eclipse.exe\""
                + " --root /tmp/ic --yes",
                InstallCheckTool.Options.parse(new String[] {"0.2.4", "--eclipse", "C:\\Program Files\\Eclipse\\eclipse.exe",
                        "--root", "/tmp/ic"}).startCommand(V, true),
                "on Windows a path with a space in double quotes");
        assertEquals("java releng/tools/checks/InstallCheck.java 0.2.4 --root '/tmp/a$b c' --yes",
                InstallCheckTool.Options.parse(new String[] {"0.2.4", "--root", "/tmp/a$b c"}).startCommand(V, false),
                "in a POSIX shell single quotes, so $ does not expand");
        assertEquals("'it'\\''s'", InstallCheckTool.Options.shellWord("it's", false));
    }

    @Test
    void theCommandLineTakesOneVersion() {
        InstallCheckTool.Options o = InstallCheckTool.Options.parse(new String[] {"0.2.4", "--no-launch"});
        assertEquals(Optional.of(V), o.version());
        assertFalse(o.launch());
        assertEquals(Ask.Mode.ASK, o.mode());
        assertEquals(Optional.empty(), InstallCheckTool.Options.parse(new String[0]).version(), "the site's newest");
        for (String[] bad : new String[][] {{"0.2"}, {"0.2.4", "0.2.5"}, {"--root"}, {"--bogus"}}) {
            assertThrows(ToolFailure.class, () -> InstallCheckTool.Options.parse(bad), String.join(" ", bad));
        }
    }

    /**
     * An offer is never a stop: without a terminal (as here, in a test JVM) it prints the command
     * and goes on. ({@code --yes} accepts it; the Release tool asks its InstallCheck offer anyway.)
     */
    @Test
    void anOfferWithoutATerminalOnlyRecommends() {
        assertFalse(new Ask(Ask.Mode.ASK, "").offer("Check it", "java x"));
        assertFalse(new Ask(Ask.Mode.DRY_RUN, "").offer("Check it", "java x"));
        assertTrue(new Ask(Ask.Mode.YES, "").offer("Check it", "java x"));
    }

    private static String after(List<String> args, String flag) {
        return args.get(args.indexOf(flag) + 1);
    }
}
