// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import ci.LikeCi;

/**
 * {@code releng/tools/ci/LikeCi.java} runs on a developer's machine what the Build workflow runs on
 * GitHub, which is its whole point: when one changes and the other does not, a local run no
 * longer predicts CI. So the workflow must still contain LikeCi's commands, word for word.
 */
class LikeCiTest {

    private static String buildYml() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve(".github/workflows/build.yml"))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "no .github/workflows/build.yml above the working folder");
        // Folded YAML lines (run: >) are joined with single spaces, as the runner sees them.
        return Files.readString(dir.resolve(".github/workflows/build.yml")).replaceAll("\\s*\\n\\s*", " ");
    }

    @Test
    void theWorkflowRunsLikeCisCommands() throws IOException {
        String build = buildYml();
        assertTrue(build.contains(LikeCi.INSTALL), "build.yml no longer installs Neovim with: " + LikeCi.INSTALL);
        assertTrue(build.contains("./mvnw " + LikeCi.UI), "build.yml no longer runs: ./mvnw " + LikeCi.UI);
        assertTrue(build.contains("./mvnw " + LikeCi.INTEGRATION), "build.yml no longer runs: ./mvnw " + LikeCi.INTEGRATION);
        assertTrue(build.contains("xvfb-run -a ./mvnw " + LikeCi.UI), "the Linux build job no longer runs under xvfb-run -a");
    }
}
