// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
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
        assertTrue(json.contains("\"s\": \"<div class=\\\"snip\\\"><pre>java releng/tools/checks/ManualChecks.java launch gn-one</pre></div>"), json);
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

    // ---- The checks' own Eclipse ----

    /** The folder to copy is the program's, or on macOS the app bundle around it. */
    @Test
    void theInstallationIsTheProgramsFolderOrTheMacApp() {
        assertEquals(Path.of("/opt/eclipse"), ManualChecks.ChecksEclipse.installation(Path.of("/opt/eclipse/eclipse")));
        assertEquals(Path.of("/Applications/Eclipse.app"),
                ManualChecks.ChecksEclipse.installation(Path.of("/Applications/Eclipse.app/Contents/MacOS/eclipse")));
    }

    /** Only bundles and features are hard links; what p2 and Eclipse write is a copy of its own. */
    @Test
    void onlyPluginsAndFeaturesAreShared() {
        Path top = Path.of("");
        assertTrue(ManualChecks.ChecksEclipse.shared(Path.of("plugins/org.eclipse.swt.jar"), top));
        assertTrue(ManualChecks.ChecksEclipse.shared(Path.of("features/f_1/feature.xml"), top));
        assertFalse(ManualChecks.ChecksEclipse.shared(Path.of("configuration/config.ini"), top));
        assertFalse(ManualChecks.ChecksEclipse.shared(Path.of("p2/org.eclipse.equinox.p2.engine/x.profile.gz"), top));
        assertFalse(ManualChecks.ChecksEclipse.shared(Path.of("eclipse.ini"), top));
        Path mac = Path.of("Contents/Eclipse");
        assertTrue(ManualChecks.ChecksEclipse.shared(Path.of("Contents/Eclipse/plugins/a.jar"), mac));
        assertFalse(ManualChecks.ChecksEclipse.shared(Path.of("Contents/Eclipse/eclipse.ini"), mac));
        assertFalse(ManualChecks.ChecksEclipse.shared(Path.of("configuration/plugins/a"), top), "only the top plugins/");
    }

    @Test
    void copyTreeLinksTheSharedFilesAndCopiesTheRest(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("eclipse");
        Files.createDirectories(source.resolve("plugins/b_1"));
        Files.createDirectories(source.resolve("configuration"));
        Files.writeString(source.resolve("plugins/a.jar"), "a");
        Files.writeString(source.resolve("plugins/b_1/b.txt"), "b");
        Files.writeString(source.resolve("configuration/config.ini"), "c");
        Files.createDirectories(source.resolve("configuration/org.eclipse.osgi/1"));
        Files.writeString(source.resolve("configuration/org.eclipse.osgi/1/cache"), "rebuilt by Eclipse");
        Path copy = dir.resolve("copy/eclipse");
        long[] counts = ManualChecks.ChecksEclipse.copyTree(source, copy, Path.of(""));
        assertEquals(3, counts[0] + counts[1], "three files in all: the OSGi cache is left out");
        assertFalse(Files.exists(copy.resolve("configuration/org.eclipse.osgi")));
        assertEquals("b", Files.readString(copy.resolve("plugins/b_1/b.txt")));
        Files.writeString(copy.resolve("configuration/config.ini"), "changed");
        assertEquals("c", Files.readString(source.resolve("configuration/config.ini")), "a copy, not a link");
        if (counts[0] == 2) { // a file system with hard links (all the developer machines)
            assertTrue(Files.isSameFile(source.resolve("plugins/a.jar"), copy.resolve("plugins/a.jar")));
        }
    }

    /**
     * The symbolic links of a real copy: an absolute one into the source points into the copy, a
     * relative one stays relative; without the right to make links (Windows), each is copied as
     * what it points to, a file or a whole folder, and never as a hard link to the source's link.
     * Skipped where the test itself may not make links.
     */
    @Test
    void symbolicLinksNeverLeadBackIntoTheSource(@TempDir Path dir) throws IOException {
        Path source = dir.resolve("eclipse").toAbsolutePath();
        Files.createDirectories(source.resolve("plugins/lib"));
        Files.writeString(source.resolve("plugins/lib/real.txt"), "real");
        Files.writeString(source.resolve("eclipse.ini"), "-vmargs");
        try {
            Files.createSymbolicLink(source.resolve("plugins/abs.txt"), source.resolve("plugins/lib/real.txt"));
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("this account may not make symbolic links: " + e);
        }
        Files.createSymbolicLink(source.resolve("plugins/rel.txt"), Path.of("lib", "real.txt"));
        Files.createSymbolicLink(source.resolve("folder"), source.resolve("plugins/lib"));
        Files.createSymbolicLink(source.resolve("dangling"), source.resolve("gone"));

        Path linked = dir.resolve("linked").toAbsolutePath();
        ManualChecks.ChecksEclipse.copyTree(source, linked, Path.of(""));
        assertEquals(linked.resolve("plugins/lib/real.txt"), Files.readSymbolicLink(linked.resolve("plugins/abs.txt")));
        assertEquals(Path.of("lib", "real.txt"), Files.readSymbolicLink(linked.resolve("plugins/rel.txt")));
        assertEquals(linked.resolve("plugins/lib"), Files.readSymbolicLink(linked.resolve("folder")));

        Path copied = dir.resolve("copied").toAbsolutePath();
        ManualChecks.ChecksEclipse.copyTree(source, copied, Path.of(""), false);
        for (String name : List.of("plugins/abs.txt", "plugins/rel.txt")) {
            Path file = copied.resolve(name);
            assertFalse(Files.isSymbolicLink(file), name);
            assertEquals("real", Files.readString(file));
            assertFalse(Files.isSameFile(file, source.resolve("plugins/lib/real.txt")), name + ": not a hard link");
        }
        assertEquals("real", Files.readString(copied.resolve("folder/real.txt")), "a folder link is copied with its files");
        assertFalse(Files.exists(copied.resolve("dangling"), java.nio.file.LinkOption.NOFOLLOW_LINKS), "a link to nothing");
        Files.writeString(copied.resolve("plugins/abs.txt"), "changed");
        assertEquals("real", Files.readString(source.resolve("plugins/lib/real.txt")), "writing the copy leaves the source");
    }

    @Test
    void theInstalledVersionIsReadFromTheDirectorsListing() {
        String feature = ManualChecks.ChecksEclipse.FEATURE;
        assertEquals("0.2.1.202611051917", ManualChecks.ChecksEclipse.installedVersion(
                "INFO: noise\norg.eclipse.jdt.feature.group/3.20\n" + feature + "/0.2.1.202611051917\n").orElseThrow());
        assertTrue(ManualChecks.ChecksEclipse.installedVersion("org.eclipse.jdt.feature.group/3.20").isEmpty());
    }

    /** A new ZIP replaces the installed feature in one step; a first install has nothing to remove. */
    @Test
    void theDirectorReplacesAnInstalledFeature() {
        Path zip = Path.of("/r/site.zip").toAbsolutePath();
        List<String> first = ManualChecks.ChecksEclipse.installArguments(zip, false);
        assertTrue(first.contains("jar:" + zip.toUri() + "!/"));
        assertFalse(first.contains("-uninstallIU"));
        List<String> again = ManualChecks.ChecksEclipse.installArguments(zip, true);
        assertTrue(again.indexOf("-uninstallIU") < again.indexOf("-installIU"));
    }

    /** A workspace whose lock someone holds is in use; without a lock file, or once released, it is not. */
    @Test
    void aHeldWorkspaceLockMeansAnEclipseRunsThere(@TempDir Path workspace) throws IOException {
        assertFalse(ManualChecks.ChecksEclipse.inUse(workspace), "no lock file");
        Path lock = workspace.resolve(".metadata/.lock");
        Files.createDirectories(lock.getParent());
        Files.createFile(lock);
        assertFalse(ManualChecks.ChecksEclipse.inUse(workspace), "a lock file nobody holds: Eclipse was closed");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE); FileLock held = channel.lock()) {
            assertTrue(ManualChecks.ChecksEclipse.inUse(workspace));
        }
        assertFalse(ManualChecks.ChecksEclipse.inUse(workspace), "released");
    }

    /** A folder is copied only if it is an Eclipse: eclipse.ini and plugins/, also inside a macOS app. */
    @Test
    void onlyAnEclipseInstallationIsCopied(@TempDir Path dir) throws IOException {
        Path bin = dir.resolve("bin"); // the folder of a wrapper script named eclipse on PATH
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("eclipse"), "#!/bin/sh");
        assertFalse(ManualChecks.ChecksEclipse.isEclipse(bin));
        Path linux = dir.resolve("eclipse");
        Files.createDirectories(linux.resolve("plugins"));
        Files.writeString(linux.resolve("eclipse.ini"), "-vmargs");
        assertTrue(ManualChecks.ChecksEclipse.isEclipse(linux));
        assertEquals(Path.of(""), ManualChecks.ChecksEclipse.inner(linux));
        Path app = dir.resolve("Eclipse.app");
        Files.createDirectories(app.resolve("Contents/Eclipse/plugins"));
        Files.writeString(app.resolve("Contents/Eclipse/eclipse.ini"), "-vmargs");
        assertTrue(ManualChecks.ChecksEclipse.isEclipse(app));
        assertEquals(Path.of("Contents", "Eclipse"), ManualChecks.ChecksEclipse.inner(app));
    }

    @Test
    void theOsgiCacheIsLeftOutOfTheCopy() {
        Path top = Path.of("");
        assertTrue(ManualChecks.ChecksEclipse.skipped(Path.of("configuration/org.eclipse.osgi/1/data"), top));
        assertFalse(ManualChecks.ChecksEclipse.skipped(Path.of("configuration/config.ini"), top));
        assertTrue(ManualChecks.ChecksEclipse.skipped(Path.of("Contents/Eclipse/configuration/org.eclipse.osgi"),
                Path.of("Contents", "Eclipse")));
    }

    /** An absolute link into your Eclipse points into the copy instead; other links stay as they are. */
    @Test
    void absoluteLinksIntoTheSourcePointIntoTheCopy() {
        Path source = Path.of("/opt/eclipse").toAbsolutePath();
        Path copy = Path.of("/home/you/c").toAbsolutePath();
        assertEquals(copy.resolve("plugins/x"),
                ManualChecks.ChecksEclipse.linkTarget(source.resolve("plugins/x"), source, copy));
        assertEquals(Path.of("../lib/y"), ManualChecks.ChecksEclipse.linkTarget(Path.of("../lib/y"), source, copy));
        Path elsewhere = Path.of("/usr/lib/z").toAbsolutePath();
        assertEquals(elsewhere, ManualChecks.ChecksEclipse.linkTarget(elsewhere, source, copy));
    }

    /** A file changed after the ZIP was built makes it stale, whatever the commits' dates; a missing one is skipped. */
    @Test
    void theZipIsStaleWhenAFileChangedAfterItWasBuilt(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("A.java"), "a");
        Files.setLastModifiedTime(a, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        Path deleted = dir.resolve("Gone.java");
        assertTrue(ManualChecks.ChecksEclipse.newerThan(2_000_000, List.of(a, deleted)).isEmpty(), "built after A");
        assertEquals(a, ManualChecks.ChecksEclipse.newerThan(500_000, List.of(deleted, a)).orElseThrow());
    }

    /** Nothing a running checks' Eclipse uses changes before the check that none runs. */
    @Test
    void launchChecksForARunningEclipseBeforeItChangesAnything() {
        List<ManualChecks.Step> fresh = ManualChecks.plan("fresh", true, false);
        assertEquals(List.of(ManualChecks.Step.WORKSPACE_FREE, ManualChecks.Step.ASK_TO_EMPTY_XDG,
                ManualChecks.Step.NONE_RUNNING, ManualChecks.Step.EMPTY_XDG, ManualChecks.Step.PREPARE_COPY,
                ManualChecks.Step.START), fresh);
        assertTrue(ManualChecks.plan("fresh", true, true).indexOf(ManualChecks.Step.NONE_RUNNING)
                < ManualChecks.plan("fresh", true, true).indexOf(ManualChecks.Step.EMPTY_XDG), "--own-eclipse too");
        assertEquals(List.of(ManualChecks.Step.WORKSPACE_FREE, ManualChecks.Step.PREPARE_COPY, ManualChecks.Step.START),
                ManualChecks.plan("checks", true, false), "xdg is kept");
        assertEquals(List.of(ManualChecks.Step.WORKSPACE_FREE, ManualChecks.Step.START),
                ManualChecks.plan("fresh", false, true), "nothing to empty, nothing to install");
    }

    @Test
    void theBuildCommandIsTheOneThePlatformsShellTakes() {
        assertEquals("./mvnw verify -DskipTests", ManualChecks.ChecksEclipse.build("linux"));
        assertEquals(".\\mvnw.cmd verify -DskipTests", ManualChecks.ChecksEclipse.build("windows"));
    }

    /** The feature and the update site module the tool names exist under those names. */
    @Test
    void theFeatureAndTheSiteAreTheBuildsOwn() throws IOException {
        Path repository = repositoryRoot();
        String feature = ManualChecks.ChecksEclipse.FEATURE.replace(".feature.group", "");
        assertTrue(Files.readString(repository.resolve("features/" + feature + "/feature.xml")).contains("id=\"" + feature + "\""));
        assertTrue(Files.isRegularFile(repository.resolve(ManualChecks.ChecksEclipse.SITE_TARGET.getParent()).resolve("pom.xml")));
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
