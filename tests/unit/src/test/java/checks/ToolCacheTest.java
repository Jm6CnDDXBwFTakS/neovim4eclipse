// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import ci.LikeCi;
import common.ToolCache;

/**
 * The rule of {@link ToolCache}: {@code target/} is Maven's, every file a developer tool writes
 * goes to {@code .tool-cache/<tool>/}. These tests keep the rule true as tools are added, and keep
 * the literal copies of the folder's names equal to the constants.
 */
class ToolCacheTest {

    /**
     * What may follow {@code target/} in a tool's source: the Maven outputs the tools read (the
     * built update site, compiled classes, test reports, the UI tests' screenshots), and nothing,
     * for prose about the folder itself ("target/ is Maven's"). Anything else names a place a tool
     * would write into, which {@code clean} deletes: give it a folder in {@link ToolCache}.
     */
    private static final Set<String> MAVEN_OUTPUTS = Set.of("", "repository", "classes", "surefire-reports", "screenshots");

    /**
     * A mention of a folder under {@code target/}: the name after it (possibly empty), and the
     * character after that. Example: {@code root.resolve("target/like-ci")} gives {@code like-ci}.
     * Not {@code <target>/...}, a parameter's name in a comment, nor {@code .tool-cache/...}.
     */
    private static final Pattern UNDER_TARGET = Pattern.compile("(?<![\\w<.-])target/([\\w.-]*)(.?)");

    /**
     * A path element {@code target} that ends a string, where what goes under it is added later:
     * {@code Path.of("target", "x")}, {@code resolve("target")}, {@code MODULE + "/target"}.
     */
    private static final Pattern TARGET_ALONE = Pattern.compile("[\"/]target\"");

    /**
     * The one line that may spell such a {@code target}: ManualChecks reads the built ZIP from the
     * update site's {@code target} folder. A whole line, not a whole file, so that a new
     * {@code Path.of("target", ...)} in the same file is still found.
     */
    private static final Set<String> TARGET_ALONE_ALLOWED = Set.of(
            "static final Path SITE_TARGET = Path.of(\"releng\", \"io.github.jm6cnddxbwftaks.neovim4eclipse.updatesite\", \"target\");");

    private static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("AGENTS.md"))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "no AGENTS.md above the working folder");
        return dir;
    }

    /** The folders under {@code target/} that {@code line} names and that are no Maven output. */
    private static List<String> notMavens(String line) {
        List<String> found = new ArrayList<>();
        Matcher m = UNDER_TARGET.matcher(line);
        while (m.find()) {
            String folder = m.group(1);
            // "target/" + something: a folder named at run time, so no known output.
            boolean built = folder.isEmpty() && m.group(2).equals("\"");
            if (!MAVEN_OUTPUTS.contains(folder) || built) {
                found.add(folder);
            }
        }
        return found;
    }

    @Test
    void noToolWritesIntoTarget() throws IOException {
        Path tools = root().resolve("releng/tools");
        List<String> wrong = new ArrayList<>();
        try (Stream<Path> files = Files.walk(tools)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String name = tools.relativize(file).toString().replace('\\', '/');
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    for (String folder : notMavens(lines.get(i))) {
                        wrong.add(name + ":" + (i + 1) + ": target/" + folder);
                    }
                    if (TARGET_ALONE.matcher(lines.get(i)).find() && !TARGET_ALONE_ALLOWED.contains(lines.get(i).strip())) {
                        wrong.add(name + ":" + (i + 1) + ": target as a path element");
                    }
                }
            }
        }
        assertEquals(List.of(), wrong, "a tool's own files go to .tool-cache/<tool>/ (common/ToolCache), not under target/");
    }

    @Test
    void theRuleSeesAToolFolderUnderTarget() {
        // The pattern itself: the old places are found, Maven's outputs and prose are not.
        assertEquals(List.of("like-ci"), notMavens("root.resolve(\"target/like-ci\")"));
        assertEquals(List.of("marketplace"), notMavens("{@code target/marketplace/listing.html}"));
        assertEquals(List.of(""), notMavens("root.resolve(\"target/\" + tool)"));
        assertEquals(List.of(), notMavens("module.resolve(\"target/surefire-reports\")"));
        assertEquals(List.of(), notMavens("plugins/x/target/classes"));
        assertEquals(List.of(), notMavens("not under {@code target/}, so"));
        assertEquals(List.of(), notMavens("{@code <target>/eclipse/eclipse}"), "a parameter's name in a path is no folder");
        assertEquals(List.of(), notMavens("root.resolve(\".tool-cache/like-ci\")"));
        // The element alone, with what goes under it added later.
        assertTrue(TARGET_ALONE.matcher("Path.of(\"target\", \"like-ci\")").find());
        assertTrue(TARGET_ALONE.matcher("root.resolve(MODULE + \"/target\").resolve(\"x\")").find());
        assertTrue(!TARGET_ALONE.matcher("\"target-branch\"").find(), "a word that starts with target");
    }

    @Test
    void everyToolFolderIsOneFolderInsideTheCache() {
        for (String folder : List.of(ToolCache.TEST_PLUGINS, ToolCache.LIKE_CI, ToolCache.RELEASE_TOOL, ToolCache.ECLIPSE_UPGRADE,
                ToolCache.MAVEN_UPGRADE, ToolCache.NEOVIM_UPGRADE, ToolCache.DEPENDENCY_UPDATE, ToolCache.MARKETPLACE)) {
            assertTrue(folder.startsWith(ToolCache.FOLDER + "/") && !folder.substring(ToolCache.FOLDER.length() + 1).contains("/"),
                    folder + " is one folder inside " + ToolCache.FOLDER);
        }
    }

    @Test
    void theCopiesOfTheNamesAgree() throws IOException {
        Path root = root();
        // LikeCi runs alone on JDK 21 and cannot import ToolCache.
        assertEquals(ToolCache.LIKE_CI, LikeCi.LIKE_CI, "LikeCi.LIKE_CI");
        // The UI tests and the Lua spec find the fetched plug-ins without the tools' sources.
        String pinned = Files.readString(root.resolve(
                "tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests/src/io/github/jm6cnddxbwftaks/neovim4eclipse/editor/PinnedPlugins.java"));
        assertTrue(pinned.contains("static final String FOLDER = \"" + ToolCache.TEST_PLUGINS + "\";"), "PinnedPlugins.FOLDER");
        String spec = Files.readString(root.resolve("tests/unit/src/test/lua/jump_plugins_spec.lua"));
        assertTrue(spec.contains("local folder = '" + ToolCache.TEST_PLUGINS + "'"), "jump_plugins_spec.lua: local folder");
    }

    @Test
    void gitIgnoresTheCache() throws IOException {
        List<String> ignored = Files.readAllLines(root().resolve(".gitignore"));
        assertTrue(ignored.contains(ToolCache.FOLDER + "/"), ".gitignore has the line " + ToolCache.FOLDER + "/");
    }
}
