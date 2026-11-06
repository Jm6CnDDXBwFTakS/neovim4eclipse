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
 * it stays like the release it rehearses: the same Eclipses for the install test as the update
 * site's smoke job, the build at the release's version with the tests skipped, and no secrets.
 */
class RehearsalTest {

    private static final Pattern PLATFORM_URL = Pattern.compile("platform-url: (\\S+)");

    private static String workflow(String name) throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve(".github/workflows/" + name))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "no .github/workflows/" + name + " above the working folder");
        return Files.readString(dir.resolve(".github/workflows/" + name));
    }

    private static List<String> platformUrls(String yml) {
        Matcher m = PLATFORM_URL.matcher(yml);
        return m.results().map(r -> r.group(1)).toList();
    }

    @Test
    void theRehearsalInstallsIntoTheSameEclipsesAsTheUpdateSite() throws IOException {
        List<String> site = platformUrls(workflow("update-site.yml"));
        assertFalse(site.isEmpty(), "update-site.yml names no Eclipse to install into");
        assertEquals(site, platformUrls(workflow("rehearse-release.yml")),
                "rehearse-release.yml's smoke matrix differs from update-site.yml's: move both together");
    }

    @Test
    void theRehearsalUsesNoSecretAndPublishesNothing() throws IOException {
        String rehearsal = workflow("rehearse-release.yml");
        assertFalse(rehearsal.contains("secrets."), "the rehearsal reads a secret");
        assertFalse(rehearsal.contains("environment:"), "the rehearsal names an environment (release holds the key)");
        assertFalse(rehearsal.matches("(?s).*\\b(contents|pages|id-token): write.*"), "the rehearsal asks for write access");
        assertFalse(rehearsal.contains("gh release create"), "the rehearsal creates a release");
        assertTrue(rehearsal.contains("uses: ./.github/workflows/build.yml"), "the rehearsal no longer builds with build.yml");
    }
}
