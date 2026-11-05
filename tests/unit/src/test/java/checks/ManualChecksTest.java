// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import checks.ManualChecks.Card;

/**
 * {@link ManualChecks}: the checklist in {@code docs/MANUAL_CHECKS.md} follows its format, names
 * only files that exist, and holds nothing personal; and the parts of the tool that decide (the
 * parser, the globs, {@code due}, the HTML) do what its documentation says.
 */
class ManualChecksTest {

    private static final String SAMPLE = """
            # Manual checks

            Prose, and a heading that is not a card:

            ### Not a card

            ## Get Neovim

            ### `gn-one` First card

            - **On:** linux, macos
            - **Launch:** fresh
            - **Areas:** util/Neovim*.java, /pom.xml

            Why it is here.

            ```sh macos
            xattr -l nvim
            ```

            1. Open *Window → Preferences*.
            2. Press <kbd>Ctrl+S</kbd> on `a<b`
               and go on.

            **Expect:** it **works**.

            ## Release

            ### `rl-one` Every test

            - **On:** any
            - **Launch:** none
            - **Areas:** always

            **Expect:** passes.
            """;

    @Test
    void cardsAreReadWithTheirFactsStepsAndExpectedResult() {
        List<Card> cards = ManualChecks.parse(SAMPLE);
        assertEquals(List.of("gn-one", "rl-one"), cards.stream().map(Card::id).toList());
        Card one = cards.get(0);
        assertEquals("First card", one.title());
        assertEquals("Get Neovim", one.section());
        assertEquals(List.of("linux", "macos"), one.on());
        assertEquals("fresh", one.launch());
        assertEquals(List.of("util/Neovim*.java", "/pom.xml"), one.areas());
        assertTrue(one.steps().startsWith("Why it is here."), one.steps());
        assertTrue(one.steps().endsWith("and go on."), one.steps());
        assertEquals("it **works**.", one.expect());
        assertTrue(cards.get(1).always());
        assertEquals("Release", cards.get(1).section());
    }

    @Test
    void aCardOutsideTheFormatIsRefusedWithItsLine() {
        String noLaunch = SAMPLE.replace("- **Launch:** fresh\n", "");
        assertTrue(assertThrows(ManualChecks.FormatException.class, () -> ManualChecks.parse(noLaunch)).getMessage()
                .contains("line 9: gn-one has no **Launch:** line"));
        assertThrows(ManualChecks.FormatException.class, () -> ManualChecks.parse(SAMPLE.replace("linux, macos", "beos")));
        assertThrows(ManualChecks.FormatException.class, () -> ManualChecks.parse(SAMPLE.replace("fresh", "warm")));
        assertThrows(ManualChecks.FormatException.class, () -> ManualChecks.parse(SAMPLE.replace("**Expect:** passes.", "")));
        assertThrows(ManualChecks.FormatException.class, () -> ManualChecks.parse(SAMPLE.replace("`rl-one`", "`gn-one`")),
                "an id used twice");
        assertThrows(ManualChecks.FormatException.class, () -> ManualChecks.parse(SAMPLE.replace("`rl-one`", "`rl_one`")),
                "a card heading with a bad id is refused, not skipped");
    }

    @Test
    void globsAreInsideThePluginsSourcesUnlessTheyStartWithASlash() {
        String util = ManualChecks.PLUGIN_SOURCES + "util/";
        assertTrue(ManualChecks.matches("util/Neovim*.java", util + "NeovimDownload.java"));
        assertFalse(ManualChecks.matches("util/Neovim*.java", util + "sub/NeovimDownload.java"), "* stays in one name");
        assertFalse(ManualChecks.matches("util/Neovim*.java", "util/NeovimDownload.java"), "relative to the sources");
        assertTrue(ManualChecks.matches("/pom.xml", "pom.xml"));
        assertFalse(ManualChecks.matches("/pom.xml", "tests/unit/pom.xml"));
        assertTrue(ManualChecks.matches("dialogs/**", ManualChecks.PLUGIN_SOURCES + "dialogs/a/B.java"));
        assertTrue(ManualChecks.matches("/tests/**/X.java", "tests/X.java"), "** may be no folder at all");
        assertTrue(ManualChecks.matches("/tests/**/X.java", "tests/a/b/X.java"));
        assertFalse(ManualChecks.matches("util/A.java", util + "AxJava"), "a dot is a dot");
    }

    @Test
    void aCardIsDueWhenOneOfItsFilesChangedOrAlways() {
        List<Card> cards = ManualChecks.parse(SAMPLE);
        Map<Card, String> due = ManualChecks.due(cards, List.of("README.md", ManualChecks.PLUGIN_SOURCES + "util/NeovimOption.java"));
        assertEquals(List.of("gn-one", "rl-one"), due.keySet().stream().map(Card::id).toList());
        assertEquals(ManualChecks.PLUGIN_SOURCES + "util/NeovimOption.java", due.get(cards.get(0)), "why: the file");
        assertEquals("always", due.get(cards.get(1)));

        Map<Card, String> quiet = ManualChecks.due(cards, List.of("README.md", ManualChecks.PLUGIN_SOURCES + "util/StatusItem.java"));
        assertEquals(List.of("rl-one"), quiet.keySet().stream().map(Card::id).toList(), "only the release card");
    }

    @Test
    void theHtmlKeepsTheSmallMarkdownTheChecklistUsesAndEscapesTheRest() {
        Card one = ManualChecks.parse(SAMPLE).get(0);
        String html = ManualChecks.html(one.steps());
        assertTrue(html.startsWith("<p>Why it is here.</p>"), html);
        assertTrue(html.contains("<div class=\"plat-only only-macos\"><div class=\"snip\"><pre>xattr -l nvim</pre></div></div>"), html);
        assertTrue(html.contains("<ol><li>Open <i>Window → Preferences</i>.</li>"), html);
        assertTrue(html.contains("<li>Press <kbd>Ctrl+S</kbd> on <code>a&lt;b</code> and go on.</li></ol>"), html);
        assertEquals("it <b>works</b>.", ManualChecks.inline(one.expect()));
        assertEquals("&lt;script&gt;x&lt;/script&gt;", ManualChecks.inline("<script>x</script>"));
    }

    @Test
    void jsonGroupsTheCardsBySectionWithTheLaunchCommandFirst() {
        String json = ManualChecks.json(ManualChecks.parse(SAMPLE));
        assertTrue(json.contains("{\"title\": \"Get Neovim\", \"checks\": ["), json);
        assertTrue(json.contains("\"id\": \"gn-one\", \"on\": [\"linux\", \"macos\"], \"launch\": \"fresh\""), json);
        assertTrue(json.contains("\"s\": \"<p>Launch: <code>java releng/tools/checks/ManualChecks.java launch gn-one</code></p>"), json);
        assertFalse(json.contains("launch rl-one"), "no launch line for a card without Eclipse");
        assertEquals("\"a\\\"b\\\\c\\n\"", ManualChecks.quote("a\"b\\c\n"));
    }

    @Test
    void setupMakesTheProjectAndKeepsWhatIsThere(@TempDir Path root) throws IOException {
        ManualChecks.setup(root);
        Path notes = root.resolve("projects/n4e-alpha/notes.txt");
        assertTrue(Files.readString(notes).startsWith("alphabet alpine"));
        assertTrue(Files.isDirectory(root.resolve("xdg")) && Files.isDirectory(root.resolve("ws2")));
        Files.writeString(notes, "changed");
        ManualChecks.setup(root);
        assertEquals("changed", Files.readString(notes), "a second setup leaves the files alone");
    }

    // ---- The repository's own checklist ----

    @Test
    void theChecklistFollowsTheFormat() throws IOException {
        List<Card> cards = ManualChecks.parse(checklist());
        assertFalse(cards.isEmpty());
        for (Card card : cards) {
            assertFalse(card.steps().isBlank() && card.launch().equals("fresh"), card.id() + ": steps for a fresh start");
            assertFalse(card.expect().isBlank(), card.id() + ": an expected result");
        }
    }

    /**
     * Every area glob matches a file of the repository: a card that names a renamed or deleted file
     * would quietly drop out of {@code due}.
     */
    @Test
    void everyAreaOfTheChecklistNamesAFileThatExists() throws IOException {
        Path root = repositoryRoot();
        List<String> files = new ArrayList<>();
        // Build output, Git's own files and other sessions' worktrees are not entered at all: a built
        // tree has hundreds of thousands of files under target/.
        Set<String> skipped = Set.of(".git", "target", "node_modules", ".claude");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                return skipped.contains(String.valueOf(dir.getFileName())) ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                files.add(root.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });
        assertEquals(List.of(), ManualChecks.unmatchedAreas(ManualChecks.parse(checklist()), files));
    }

    /**
     * The checklist is public, so it holds nothing about one person or machine: no home folder of a
     * real user, no e-mail address, no link to a private page. Examples it refuses:
     * {@code /home/pat/...}, {@code C:\Users\pat\...}, {@code pat@example.org}. Placeholders such as
     * {@code ~/n4e-checks} and {@code $HOME} are fine.
     */
    @Test
    void theChecklistHoldsNothingPersonal() throws IOException {
        String text = checklist();
        List<String> found = new ArrayList<>();
        for (Pattern personal : List.of(Pattern.compile("/home/[A-Za-z]"), Pattern.compile("/Users/[A-Za-z]"),
                Pattern.compile("(?i)[A-Z]:\\\\Users\\\\"), Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+"),
                Pattern.compile("(?i)claude\\.ai|anthropic|artifact/"))) {
            Matcher m = personal.matcher(text);
            while (m.find()) {
                found.add(m.group());
            }
        }
        assertEquals(List.of(), found);
    }

    private static String checklist() throws IOException {
        return Files.readString(repositoryRoot().resolve(ManualChecks.CHECKLIST));
    }

    /** The repository's root, found by walking up from the working directory. */
    private static Path repositoryRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(ManualChecks.CHECKLIST))) {
                return dir;
            }
        }
        throw new IllegalStateException("docs/MANUAL_CHECKS.md not found above " + Path.of("").toAbsolutePath());
    }
}
