// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import common.Shell;

/**
 * The developer tools read an environment variable by its name ({@link Shell#env}, or
 * {@code System.getenv(name)}), never from the map {@code System.getenv()} returns. Why: Windows
 * ignores the case of a variable's name, and usually spells the search path {@code Path}.
 * {@code System.getenv("PATH")} finds it; the map's {@code get("PATH")} compares the keys exactly
 * and finds nothing, so every "is it on PATH" answered no in a console opened from the Start menu
 * (a shell that spells it {@code PATH}, as Git Bash does, hid that).
 */
class ToolEnvironmentTest {

    /** A call of {@code System.getenv()} without a name: the map. */
    private static final Pattern THE_MAP = Pattern.compile("System\\s*\\.\\s*getenv\\s*\\(\\s*\\)");

    private static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("AGENTS.md"))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "no AGENTS.md above the working folder");
        return dir;
    }

    /** Whether {@code line} is code that takes the map; a comment may name it. */
    private static boolean takesTheMap(String line) {
        String code = line.strip();
        boolean comment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*");
        return !comment && THE_MAP.matcher(code).find();
    }

    @Test
    void noToolReadsAVariableFromTheEnvironmentMap() throws IOException {
        Path releng = root().resolve("releng");
        List<String> wrong = new ArrayList<>();
        try (Stream<Path> files = Files.walk(releng)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (takesTheMap(lines.get(i))) {
                        wrong.add(releng.relativize(file).toString().replace('\\', '/') + ":" + (i + 1));
                    }
                }
            }
        }
        assertEquals(List.of(), wrong, "read a variable with Shell.env(name, orElse) or System.getenv(name):"
                + " the map System.getenv() returns has no PATH on Windows, where the name is Path");
    }

    @Test
    void theRuleSeesTheMapAndNotALookupByName() {
        assertTrue(takesTheMap("for (String dir : System.getenv().getOrDefault(\"PATH\", \"\").split(File.pathSeparator)) {"));
        assertTrue(takesTheMap("Map<String, String> env = System.getenv();"));
        assertFalse(takesTheMap("String path = System.getenv(\"PATH\");"));
        assertFalse(takesTheMap("String value = System.getenv(name);"));
        assertFalse(takesTheMap("// System.getenv() returns does not, and would give no PATH at all there."), "a comment may name it");
        assertFalse(takesTheMap("     * {@code System.getenv()} returns: Windows ignores the case"), "so may a doc comment");
    }

    @Test
    void aVariableThatIsNotSetGivesTheFallback() {
        assertEquals("fallback", Shell.env("NEOVIM4ECLIPSE_NO_SUCH_VARIABLE", "fallback"));
        assertEquals(System.getenv("PATH"), Shell.env("PATH", null));
    }

    @Test
    void onWindowsTheNamesCaseDoesNotMatter() {
        assumeTrue(Shell.WINDOWS, "only Windows ignores the case of a variable's name");
        assumeTrue(System.getenv("PATH") != null, "no PATH in this environment");
        // However the console spelled it (Path, PATH), each spelling reads the same value.
        assertEquals(System.getenv("PATH"), Shell.env("Path", ""));
        assertEquals(System.getenv("PATH"), Shell.env("path", ""));
    }
}
