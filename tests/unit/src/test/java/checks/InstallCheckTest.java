// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import common.Ask;
import common.EclipseCopy;
import common.ToolFailure;
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
                </children>
                """;
        List<Version> releases = InstallCheckTool.releases(composite);
        assertEquals(List.of(Version.parse("0.1.9"), Version.parse("0.2.10"), V), releases);
        assertEquals(Version.parse("0.2.10"), releases.stream().max(Version::compareTo).orElseThrow(), "by number, not by text");
    }

    @Test
    void theDirectorTrustsOnlyTheSiteAndTheReleaseKey() {
        List<String> args = InstallCheckTool.installArguments(SITE, V, "A91F53D8931B7FD0D14686186CC9E446147B063C", false);
        assertEquals(SITE + "releases/0.2.4/", after(args, "-repository"));
        assertEquals("https://jm6cnddxbwftaks.github.io/", after(args, "-trustedAuthorities"), "the host, with its /");
        assertEquals("a91f53d8931b7fd0d14686186cc9e446147b063c", after(args, "-trustedPGPKeys"), "in lower case, as p2 compares it");
        assertTrue(args.contains("-trustSignedContentOnly"));
        assertFalse(args.contains("-uninstallIU"), "nothing to replace");
        assertEquals(EclipseCopy.FEATURE,
                after(InstallCheckTool.installArguments(SITE, V, "A91F", true), "-uninstallIU"), "replaces the copy's plug-in");
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

    /** An offer is never a stop: without a terminal (as here, in a test JVM) it prints the command and goes on. */
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
