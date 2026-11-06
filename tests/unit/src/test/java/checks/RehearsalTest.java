// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The release rehearsal ({@code .github/workflows/rehearse-release.yml}) is worth only as much as
 * it stays like the release it rehearses: the same Eclipses (and pinned SHA-512s) for the install
 * test as the update site's smoke job, the build at the release's version with the tests skipped, and no secrets.
 */
class RehearsalTest {

    /** A smoke matrix entry's Eclipse download and its pinned SHA-512, one after the other. */
    private static final Pattern PLATFORM = Pattern.compile("platform-url: (\\S+)\\s+platform-sha512: ([0-9a-f]{128})\\s");

    private static String workflow(String name) throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve(".github/workflows/" + name))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "no .github/workflows/" + name + " above the working folder");
        return Files.readString(dir.resolve(".github/workflows/" + name));
    }

    /** Each smoke matrix entry as "url sha512", in order. */
    private static List<String> platforms(String yml) {
        Matcher m = PLATFORM.matcher(yml);
        return m.results().map(r -> r.group(1) + " " + r.group(2)).toList();
    }

    @Test
    void theRehearsalInstallsIntoTheSameEclipsesAsTheUpdateSite() throws IOException {
        String siteYml = workflow("update-site.yml");
        List<String> site = platforms(siteYml);
        assertFalse(site.isEmpty(), "update-site.yml names no Eclipse to install into");
        assertEquals(siteYml.split("platform-url:", -1).length - 1, site.size(),
                "update-site.yml: every platform-url needs a platform-sha512 on the next line");
        assertEquals(site, platforms(workflow("rehearse-release.yml")),
                "rehearse-release.yml's smoke matrix (URLs, SHA-512s) differs from update-site.yml's: move both together");
    }

    @Test
    void theRehearsalUsesNoSecretAndPublishesNothing() throws IOException {
        String rehearsal = workflow("rehearse-release.yml");
        assertFalse(rehearsal.contains("secrets."), "the rehearsal reads a secret");
        assertFalse(rehearsal.contains("environment:"), "the rehearsal names an environment (release holds the key)");
        // Any scope (contents, actions, packages, ...) at write, or write-all.
        assertFalse(Pattern.compile("(?m)^\\s*[\\w-]+:\\s*write\\b|write-all").matcher(rehearsal).find(),
                "the rehearsal asks for write access");
        assertFalse(rehearsal.contains("gh release create"), "the rehearsal creates a release");
        // Started by hand or by a push to rehearse/, never by a pull request or a tag: a fork's
        // pull request must not run it, and a tag is the real release.
        assertFalse(rehearsal.contains("pull_request") || rehearsal.contains("tags:"),
                "the rehearsal runs on pull requests or tags");
        assertTrue(rehearsal.contains("uses: ./.github/workflows/build.yml"), "the rehearsal no longer builds with build.yml");
    }
}
