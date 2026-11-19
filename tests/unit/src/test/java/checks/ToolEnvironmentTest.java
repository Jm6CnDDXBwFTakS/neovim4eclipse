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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import common.EclipseCopy;
import common.Shell;

/**
 * The developer tools read an environment variable by its name ({@link Shell#env}, or
 * {@code System.getenv(name)}), never from the map {@code System.getenv()} returns. Why: Windows
 * ignores the case of a variable's name, and usually spells the search path {@code Path}.
 * {@code System.getenv("PATH")} finds it; the map's {@code get("PATH")} compares the keys exactly
 * and finds nothing, so every "is it on PATH" answered no in a console opened from the Start menu
 * (a shell that spells it {@code PATH}, as Git Bash does, hid that).
 *
 * <p>The same goes for a child's environment, {@code ProcessBuilder.environment()}: a tool sets a
 * variable there with {@link Shell#putEnv}, under the name it already has ({@link Shell#nameIn}),
 * since a {@code PATH} put beside {@code Path} gives the child two.
 */
class ToolEnvironmentTest {

    /**
     * A call of {@code System.getenv()} without a name (the map), or a variable read or set by its
     * exact name in a child's {@code ProcessBuilder.environment()}, which is such a map too: a
     * tool sets a child's variable with {@link Shell#putEnv}, under the name it has there.
     */
    private static final Pattern THE_MAP = Pattern.compile("System\\s*\\.\\s*getenv\\s*\\(\\s*\\)"
            + "|\\.\\s*environment\\s*\\(\\s*\\)\\s*\\.\\s*(put|putAll|get|getOrDefault|remove|containsKey)\\s*\\(");

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
        assertEquals(List.of(), wrong, "read a variable with Shell.env(name, orElse) or System.getenv(name), and set a child's"
                + " with Shell.putEnv: a map of the environment has no PATH on Windows, where the name is Path");
    }

    @Test
    void theRuleSeesTheMapAndNotALookupByName() {
        assertTrue(takesTheMap("for (String dir : System.getenv().getOrDefault(\"PATH\", \"\").split(File.pathSeparator)) {"));
        assertTrue(takesTheMap("Map<String, String> env = System.getenv();"));
        assertTrue(takesTheMap("builder.environment().put(\"XDG_DATA_HOME\", xdg.toString());"));
        assertTrue(takesTheMap("String options = builder.environment().getOrDefault(\"JAVA_TOOL_OPTIONS\", \"\");"));
        assertFalse(takesTheMap("Shell.putEnv(builder.environment(), Map.of(\"XDG_DATA_HOME\", xdg.toString()));"));
        assertFalse(takesTheMap("putEnv(pb.environment(), env);"));
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
    void aChildsVariableKeepsTheNameItHasOnWindows() {
        // Windows: PATH is the variable spelled Path; a second one beside it would not replace it.
        assertEquals("Path", Shell.nameIn(Map.of("Path", "C:\\bin"), "PATH", true));
        assertEquals("PATH", Shell.nameIn(Map.of("PATH", "C:\\bin"), "PATH", true));
        assertEquals("JAVA_HOME", Shell.nameIn(Map.of("Path", "C:\\bin"), "JAVA_HOME", true), "not there yet: the name as given");
        // Linux and macOS: path and PATH are two variables.
        assertEquals("PATH", Shell.nameIn(Map.of("path", "/elsewhere"), "PATH", false));
    }

    @Test
    void englishIsAddedToTheJavaOptionsThatAreThere() {
        Map<String, String> none = new HashMap<>();
        EclipseCopy.english(none, true);
        assertEquals(Map.of("JAVA_TOOL_OPTIONS", "-Duser.language=en -Duser.country=US"), none);

        Map<String, String> otherCase = new HashMap<>(Map.of("Java_Tool_Options", "-Xmx1g"));
        EclipseCopy.english(otherCase, true);
        assertEquals(Map.of("Java_Tool_Options", "-Xmx1g -Duser.language=en -Duser.country=US"), otherCase, "one variable on Windows");

        Map<String, String> linux = new HashMap<>(Map.of("java_tool_options", "-Xmx1g"));
        EclipseCopy.english(linux, false);
        assertEquals(Map.of("java_tool_options", "-Xmx1g", "JAVA_TOOL_OPTIONS", "-Duser.language=en -Duser.country=US"), linux,
                "another variable on Linux and macOS, which Java does not read");
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
