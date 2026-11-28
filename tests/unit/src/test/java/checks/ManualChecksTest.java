// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import checks.ManualChecksTool.Card;
import common.EclipseCopy;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.RepositoryFiles;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * {@link ManualChecksTool}: the checklist in {@code docs/MANUAL_CHECKS.md} follows its format, names
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
    void aBuildFileThatOnlyChangedItsVersionCountsForNoCard() {
        String bump = """
                --- a/pom.xml
                +++ b/pom.xml
                @@ -11 +11 @@
                -  <version>0.2.2-SNAPSHOT</version>
                +  <version>0.2.3-SNAPSHOT</version>
                """;
        assertTrue(ManualChecksTool.onlyTheVersionChanged(bump));
        assertTrue(ManualChecksTool.onlyTheVersionChanged("-Bundle-Version: 0.2.2.qualifier\n+Bundle-Version: 0.2.3.qualifier\n"));
        assertFalse(ManualChecksTool.onlyTheVersionChanged(bump + "+  <tycho.version>5.0.1</tycho.version>\n"), "a line added");
        assertFalse(ManualChecksTool.onlyTheVersionChanged("-  <eclipse-repo.url>…/2024-12</eclipse-repo.url>\n"
                + "+  <eclipse-repo.url>…/2025-03</eclipse-repo.url>\n"), "not a version number");
        assertFalse(ManualChecksTool.onlyTheVersionChanged(""), "no change at all is not a version change");
        assertTrue(ManualChecksTool.VERSIONED_FILES.containsAll(List.of("pom.xml", "MANIFEST.MF", "feature.xml")));
    }

    @Test
    void cardsAreReadWithTheirFactsStepsAndExpectedResult() {
        List<Card> cards = ManualChecksTool.parse(SAMPLE);
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
        assertTrue(assertThrows(ManualChecksTool.FormatException.class, () -> ManualChecksTool.parse(noLaunch)).getMessage()
                .contains("line 9: gn-one has no **Launch:** line"));
        assertThrows(ManualChecksTool.FormatException.class, () -> ManualChecksTool.parse(SAMPLE.replace("linux, macos", "beos")));
        assertThrows(ManualChecksTool.FormatException.class, () -> ManualChecksTool.parse(SAMPLE.replace("fresh", "warm")));
        assertThrows(ManualChecksTool.FormatException.class, () -> ManualChecksTool.parse(SAMPLE.replace("**Expect:** passes.", "")));
        assertThrows(ManualChecksTool.FormatException.class, () -> ManualChecksTool.parse(SAMPLE.replace("`rl-one`", "`gn-one`")),
                "an id used twice");
        assertThrows(ManualChecksTool.FormatException.class, () -> ManualChecksTool.parse(SAMPLE.replace("`rl-one`", "`rl_one`")),
                "a card heading with a bad id is refused, not skipped");
    }

    @Test
    void globsAreInsideThePluginsSourcesUnlessTheyStartWithASlash() {
        String util = ManualChecksTool.PLUGIN_SOURCES + "util/";
        assertTrue(ManualChecksTool.matches("util/Neovim*.java", util + "NeovimDownload.java"));
        assertFalse(ManualChecksTool.matches("util/Neovim*.java", util + "sub/NeovimDownload.java"), "* stays in one name");
        assertFalse(ManualChecksTool.matches("util/Neovim*.java", "util/NeovimDownload.java"), "relative to the sources");
        assertTrue(ManualChecksTool.matches("/pom.xml", "pom.xml"));
        assertFalse(ManualChecksTool.matches("/pom.xml", "tests/unit/pom.xml"));
        assertTrue(ManualChecksTool.matches("dialogs/**", ManualChecksTool.PLUGIN_SOURCES + "dialogs/a/B.java"));
        assertTrue(ManualChecksTool.matches("/tests/**/X.java", "tests/X.java"), "** may be no folder at all");
        assertTrue(ManualChecksTool.matches("/tests/**/X.java", "tests/a/b/X.java"));
        assertFalse(ManualChecksTool.matches("util/A.java", util + "AxJava"), "a dot is a dot");
    }

    @Test
    void aCardIsDueWhenOneOfItsFilesChangedOrAlways() {
        List<Card> cards = ManualChecksTool.parse(SAMPLE);
        Map<Card, String> due = ManualChecksTool.due(cards, List.of("README.md", ManualChecksTool.PLUGIN_SOURCES + "util/NeovimOption.java"));
        assertEquals(List.of("gn-one", "rl-one"), due.keySet().stream().map(Card::id).toList());
        assertEquals(ManualChecksTool.PLUGIN_SOURCES + "util/NeovimOption.java", due.get(cards.get(0)), "why: the file");
        assertEquals("always", due.get(cards.get(1)));

        Map<Card, String> quiet = ManualChecksTool.due(cards, List.of("README.md", ManualChecksTool.PLUGIN_SOURCES + "util/StatusItem.java"));
        assertEquals(List.of("rl-one"), quiet.keySet().stream().map(Card::id).toList(), "only the release card");
    }

    @Test
    void theHtmlKeepsTheSmallMarkdownTheChecklistUsesAndEscapesTheRest() {
        Card one = ManualChecksTool.parse(SAMPLE).get(0);
        String html = ManualChecksTool.html(one.steps());
        assertTrue(html.startsWith("<p>Why it is here.</p>"), html);
        assertTrue(html.contains("<div class=\"plat-only only-macos\"><div class=\"snip\"><pre>xattr -l nvim</pre></div></div>"), html);
        assertTrue(html.contains("<ol><li>Open <i>Window → Preferences</i>.</li>"), html);
        assertTrue(html.contains("<li>Press <kbd>Ctrl+S</kbd> on <code>a&lt;b</code> and go on.</li></ol>"), html);
        assertEquals("it <b>works</b>.", ManualChecksTool.inline(one.expect()));
        assertEquals("&lt;script&gt;x&lt;/script&gt;", ManualChecksTool.inline("<script>x</script>"));
    }

    /** A step's ⇒ line is its own block under the step, its continuation lines joined to it. */
    @Test
    void aStepsResultIsABlockUnderTheStep() {
        assertEquals("<ol><li>Press <kbd>Esc</kbd>.<div class=\"result\">The line <code>:e</code> closes.</div></li>"
                + "<li>Next.</li></ol>",
                ManualChecksTool.html("1. Press <kbd>Esc</kbd>.\n   \u21D2 The line `:e`\n   closes.\n2. Next.\n"));
    }

    @Test
    void jsonGroupsTheCardsBySectionWithTheLaunchCommandFirst() {
        String json = ManualChecksTool.json(ManualChecksTool.parse(SAMPLE));
        assertTrue(json.contains("{\"title\": \"Get Neovim\", \"checks\": ["), json);
        assertTrue(json.contains("\"id\": \"gn-one\", \"on\": [\"linux\", \"macos\"], \"launch\": \"fresh\""), json);
        assertTrue(json.contains("\"s\": \"<div class=\\\"snip\\\"><pre>java releng/tools/checks/ManualChecks.java launch gn-one</pre></div>"), json);
        assertFalse(json.contains("launch rl-one"), "no launch line for a card without Eclipse");
        assertEquals("\"a\\\"b\\\\c\\n\"", ManualChecksTool.quote("a\"b\\c\n"));
    }

    @Test
    void setupMakesTheProjectAndKeepsWhatIsThere(@TempDir Path root) throws IOException {
        ManualChecksTool.setup(root);
        Path notes = root.resolve("projects/n4e-alpha/notes.txt");
        assertTrue(Files.readString(notes).startsWith("alphabet alpine"));
        assertTrue(Files.isDirectory(root.resolve("xdg")) && Files.isDirectory(root.resolve("ws2")));
        Files.writeString(notes, "changed");
        ManualChecksTool.setup(root);
        assertEquals("changed", Files.readString(notes), "a second setup leaves the files alone");
        Path prompt = root.resolve("projects/n4e-alpha/float-prompt.lua");
        Files.delete(prompt); // a root made before the card fl-prompt needed it
        ManualChecksTool.setup(root);
        assertTrue(Files.readString(prompt).contains("nvim_open_win"), "a missing file is added");
    }

    /**
     * The card fl-prompt's float-prompt.lua, sourced in a headless Neovim, opens a focused floating
     * prompt buffer; Enter closes it and shows the text typed. Example: typing {@code abc} and
     * Enter shows {@code float prompt: abc}. The person checks only what no test sees (an app
     * switch, an input method).
     */
    @Test
    @Tag("integration")
    void theFloatPromptOpensAFloatingPromptThatEnterCloses(@TempDir Path root) throws Exception {
        String out = sourcedInAlpha(root, "float-prompt.lua", """
                local win = vim.api.nvim_get_current_win()
                local opened = vim.api.nvim_win_get_config(win).relative ~= '' and vim.bo.buftype == 'prompt'
                vim.api.nvim_feedkeys('iabc' .. vim.keycode('<CR>'), 'tx', false)
                io.stdout:write(tostring(opened), ' ', tostring(vim.api.nvim_win_is_valid(win)), ' ', vim.fn.execute('messages'):match('float prompt: %w+') or 'none', '\\n')
                """);
        assertTrue(out.contains("true false float prompt: abc"), "a float with a prompt, closed by Enter, the text shown: " + out);
    }

    /**
     * The card ime-compose's blink.lua highlights words in the window and takes the highlight away
     * again, over and over, so that the text is redrawn while a person composes on the {@code :}
     * line; sourced a second time it stops, leaving no highlight and no timer running.
     */
    @Test
    @Tag("integration")
    void blinkHighlightsUntilSourcedAgain(@TempDir Path root) throws Exception {
        String out = sourcedInAlpha(root, "blink.lua", """
                local function timers()
                  local n = 0
                  vim.uv.walk(function(h)
                    if h:get_type() == 'timer' and h:is_active() then n = n + 1 end
                  end)
                  return n
                end
                local limit = tonumber(os.getenv('TEST_WAIT_MS'))
                local on = vim.wait(limit, function() return #vim.fn.getmatches() > 0 end)
                local off = vim.wait(limit, function() return #vim.fn.getmatches() == 0 end)
                local running = timers()
                vim.cmd.source('blink.lua') -- stops it
                io.stdout:write('on ', tostring(on), ' off ', tostring(off), ' ran ', running, ' then ', timers(), ' ', #vim.fn.getmatches(), '\\n')
                """);
        assertTrue(out.contains("on true off true ran 1 then 0 0"), "blinking, then stopped with nothing left: " + out);
    }

    /**
     * Sources {@code file} (one {@code setup} writes) in a headless Neovim started in n4e-alpha,
     * then runs {@code check}, and gives what it printed. Run in the project, by relative names, as
     * the cards do ({@code :source float-prompt.lua}): no path to escape, whatever the temp folder's
     * name holds (a space in a Windows user name).
     */
    private static String sourcedInAlpha(Path root, String file, String check) throws Exception {
        TestNeovim.require();
        ManualChecksTool.setup(root);
        Path alpha = root.resolve("projects/n4e-alpha");
        Files.writeString(alpha.resolve("check.lua"), check);
        ProcessBuilder builder = new ProcessBuilder(TestNeovim.PATH, "--clean", "--headless", "notes.txt", "-c", "source " + file,
                "-c", "luafile check.lua", "-c", "qa!").directory(alpha.toFile()).redirectErrorStream(true);
        NvimExecutable.prepareEnvironment(builder.environment(), PlatformUtil.isWindows());
        builder.environment().put("TEST_WAIT_MS", Long.toString(TestWait.millis()));
        Process nvim = builder.start();
        nvim.getOutputStream().close(); // no input: a script that waited for a key would hang
        // Read on another thread, and wait with TestWait's limit: a Neovim that hangs fails the
        // test instead of hanging the build (as LuaScriptsInNeovimTest runs its specs). Three
        // limits: the check itself may wait two.
        CompletableFuture<byte[]> read = CompletableFuture.supplyAsync(() -> {
            try {
                return nvim.getInputStream().readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        if (!nvim.waitFor(3 * TestWait.millis(), TimeUnit.MILLISECONDS)) {
            nvim.destroyForcibly();
            fail("Neovim did not finish sourcing " + file);
        }
        String out = new String(read.get(TestWait.millis(), TimeUnit.MILLISECONDS), StandardCharsets.UTF_8);
        assertEquals(0, nvim.exitValue(), out);
        return out;
    }

    // ---- The checks' own Eclipse ----

    /** A new ZIP replaces the installed feature in one step; a first install has nothing to remove. */
    @Test
    void theDirectorReplacesAnInstalledFeature() {
        Path zip = Path.of("/r/site.zip").toAbsolutePath();
        List<String> first = ManualChecksTool.ChecksEclipse.installArguments(zip, false);
        assertTrue(first.contains("jar:" + zip.toUri() + "!/"));
        assertFalse(first.contains("-uninstallIU"));
        List<String> again = ManualChecksTool.ChecksEclipse.installArguments(zip, true);
        assertTrue(again.indexOf("-uninstallIU") < again.indexOf("-installIU"));
    }

    /** --workspace names a folder under the root, so the running check sees an Eclipse there. */
    @Test
    void aWorkspaceIsAFolderNameUnderTheRoot() {
        assertTrue(ManualChecksTool.Options.isWorkspaceName("ws2"));
        assertTrue(ManualChecksTool.Options.isWorkspaceName("ws-3.b_c"));
        for (String outside : new String[] { "/tmp/ws", "../ws", "a/b", "a\\b", "..", ".", "", "C:ws" }) {
            assertFalse(ManualChecksTool.Options.isWorkspaceName(outside), outside);
        }
    }

    /** A file changed after the ZIP was built makes it stale, whatever the commits' dates; a missing one is skipped. */
    @Test
    void theZipIsStaleWhenAFileChangedAfterItWasBuilt(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("A.java"), "a");
        Files.setLastModifiedTime(a, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        Path deleted = dir.resolve("Gone.java");
        assertTrue(ManualChecksTool.ChecksEclipse.newerThan(2_000_000, List.of(a, deleted)).isEmpty(), "built after A");
        assertEquals(a, ManualChecksTool.ChecksEclipse.newerThan(500_000, List.of(deleted, a)).orElseThrow());
    }

    /** Nothing a running checks' Eclipse uses changes before the check that none runs. */
    @Test
    void launchChecksForARunningEclipseBeforeItChangesAnything() {
        List<ManualChecksTool.Step> fresh = ManualChecksTool.plan("fresh", true, false);
        assertEquals(List.of(ManualChecksTool.Step.WORKSPACE_FREE, ManualChecksTool.Step.ASK_TO_EMPTY_XDG,
                ManualChecksTool.Step.NONE_RUNNING, ManualChecksTool.Step.EMPTY_XDG, ManualChecksTool.Step.PREPARE_COPY,
                ManualChecksTool.Step.START), fresh);
        assertTrue(ManualChecksTool.plan("fresh", true, true).indexOf(ManualChecksTool.Step.NONE_RUNNING)
                < ManualChecksTool.plan("fresh", true, true).indexOf(ManualChecksTool.Step.EMPTY_XDG), "--own-eclipse too");
        assertEquals(List.of(ManualChecksTool.Step.WORKSPACE_FREE, ManualChecksTool.Step.PREPARE_COPY, ManualChecksTool.Step.START),
                ManualChecksTool.plan("checks", true, false), "xdg is kept");
        assertEquals(List.of(ManualChecksTool.Step.WORKSPACE_FREE, ManualChecksTool.Step.START),
                ManualChecksTool.plan("fresh", false, true), "nothing to empty, nothing to install");
    }

    /** The feature and the update site module the tool names exist under those names. */
    @Test
    void theFeatureAndTheSiteAreTheBuildsOwn() throws IOException {
        Path repository = repositoryRoot();
        String feature = EclipseCopy.FEATURE.replace(".feature.group", "");
        assertTrue(Files.readString(repository.resolve("features/" + feature + "/feature.xml")).contains("id=\"" + feature + "\""));
        assertTrue(Files.isRegularFile(repository.resolve(ManualChecksTool.ChecksEclipse.SITE_TARGET.getParent()).resolve("pom.xml")));
    }

    // ---- The repository's own checklist ----

    @Test
    void theChecklistFollowsTheFormat() throws IOException {
        List<Card> cards = ManualChecksTool.parse(checklist());
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
        // Git's list (RepositoryFiles): no build output, and no copies such as other tools' worktrees.
        List<String> files = RepositoryFiles.list(root);
        assertEquals(List.of(), ManualChecksTool.unmatchedAreas(ManualChecksTool.parse(checklist()), files));
    }

    /**
     * The checklist is public, so it holds nothing about one person or machine: no home folder of a
     * real user, no e-mail address, no link to a private page. Examples it refuses:
     * {@code /home/pat/...}, {@code C:\Users\pat\...}, {@code pat@example.org}. Placeholders such as
     * {@code ~/var/n4e-checks} and {@code $HOME} are fine.
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
        return Files.readString(repositoryRoot().resolve(ManualChecksTool.CHECKLIST));
    }

    /** The repository's root, found by walking up from the working directory. */
    private static Path repositoryRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(ManualChecksTool.CHECKLIST))) {
                return dir;
            }
        }
        throw new IllegalStateException("docs/MANUAL_CHECKS.md not found above " + Path.of("").toAbsolutePath());
    }
}
