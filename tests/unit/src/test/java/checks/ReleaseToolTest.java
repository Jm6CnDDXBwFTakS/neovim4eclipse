// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import common.Ask;
import common.Folders;
import common.Git;
import common.GitHub;
import common.Settings;
import common.Shell;
import common.ToolFailure;
import common.Version;
import release.Options;
import release.ReleaseFiles;
import release.ReleaseState;

/**
 * The decisions of the Release tool ({@code releng/tools/release/}): its text edits, its version
 * rules, which step comes next, when it may act without asking. The git and GitHub parts only run
 * commands; these are the parts that could quietly do the wrong thing. It also checks that the
 * tool's settings still name what the workflows and the README contain.
 */
class ReleaseToolTest {

    private static final Version V = Version.parse("0.2.3");

    private static final String CHANGELOG = """
            # Changelog

            ## [Unreleased]

            ### Fixed

            - A fix.

            ## [0.2.2] - 2026-11-06

            ### Changed

            - Builds.
            """;

    private static Path repo() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("releng/tools/release/settings.properties"))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "no releng/tools/release/settings.properties above the working folder");
        return dir;
    }

    // ------------------------------------------------------------ versions

    @Test
    void aVersionIsThreePlainNumbersAndNamesItsBranchTagAndSnapshot() {
        assertEquals("release/0.2.3", V.branch());
        assertEquals("v0.2.3", V.tag());
        assertEquals("0.2.3-SNAPSHOT", V.snapshot());
        assertEquals(Version.parse("0.2.4"), V.nextPatch());
        assertEquals(V, Version.ofSnapshot("0.2.3-SNAPSHOT"));
        for (String bad : List.of("0.2", "v0.2.3", "0.2.03", "0.2.3-SNAPSHOT", "0.2.3 ", "1.2.3;rm")) {
            assertThrows(ToolFailure.class, () -> Version.parse(bad), bad);
        }
        assertThrows(ToolFailure.class, () -> Version.ofSnapshot("0.2.3"));
        assertTrue(Version.parse("0.2.10").compareTo(Version.parse("0.2.9")) > 0, "numbers, not text");
    }

    // ------------------------------------------------------------ the changelog

    @Test
    void prepareDatesTheUnreleasedSectionAndOpensANewOne() {
        String dated = ReleaseFiles.datedChangelog(CHANGELOG, V, LocalDate.of(2026, 11, 20));
        assertTrue(dated.contains("## [Unreleased]\n\n## [0.2.3] - 2026-11-20\n\n### Fixed\n\n- A fix.\n\n## [0.2.2]"), dated);
        assertFalse(ReleaseFiles.unreleasedHasEntries(dated));
        assertTrue(ReleaseFiles.hasRelease(dated, V));
        assertTrue(dated.endsWith("- Builds.\n"), "the rest is untouched");
    }

    @Test
    void prepareRefusesAnEmptyUnreleasedOrADatedVersion() {
        String empty = CHANGELOG.replace("### Fixed\n\n- A fix.\n\n", "\n");
        assertFalse(ReleaseFiles.unreleasedHasEntries(empty));
        assertThrows(ToolFailure.class, () -> ReleaseFiles.datedChangelog(empty, V, LocalDate.of(2026, 11, 20)));
        String dated = ReleaseFiles.datedChangelog(CHANGELOG, V, LocalDate.of(2026, 11, 20));
        assertThrows(ToolFailure.class, () -> ReleaseFiles.datedChangelog(dated.replace("## [Unreleased]", "## [Unreleased]\n- more"),
                V, LocalDate.of(2026, 11, 21)));
    }

    @Test
    void prepareKeepsWindowsLineEnds() {
        String crlf = CHANGELOG.replace("\n", "\r\n");
        String dated = ReleaseFiles.datedChangelog(crlf, V, LocalDate.of(2026, 11, 20));
        assertTrue(dated.contains("## [Unreleased]\r\n\r\n## [0.2.3] - 2026-11-20\r\n"), dated);
        assertFalse(dated.replace("\r\n", "").contains("\n"), "no lone line feed");
    }

    @Test
    void aVersionsSectionIsFoundByItsBracketedNumber() {
        String changelog = CHANGELOG.replace("## [0.2.2]", "## [0.2.30]");
        assertFalse(ReleaseFiles.hasRelease(changelog, Version.parse("0.2.3")), "0.2.3 is not 0.2.30");
        assertTrue(ReleaseFiles.hasRelease(changelog, Version.parse("0.2.30")));
    }

    @Test
    void theRepositorysChangelogHasTheUnreleasedHeading() throws IOException {
        assertTrue(Files.readString(repo().resolve("CHANGELOG.md")).lines().anyMatch(l -> l.equals("## [Unreleased]")),
                "prepare dates the line \"## [Unreleased]\"");
    }

    // ------------------------------------------------------------ the README

    @Test
    void theReadmesStatusLineIsRewritten() throws IOException {
        String readme = Files.readString(repo().resolve("README.md"));
        String version = ReleaseFiles.readmeVersion(readme); // exactly one status line today
        Version.parse(version);
        String rewritten = ReleaseFiles.readmeWithVersion(readme, Version.parse("9.8.7"));
        assertEquals("9.8.7", ReleaseFiles.readmeVersion(rewritten));
        assertEquals(readme, rewritten.replace("**Status: 9.8.7, ", "**Status: " + version + ", "), "only the version changes");
        assertThrows(ToolFailure.class, () -> ReleaseFiles.readmeVersion("no status here"));
        assertThrows(ToolFailure.class, () -> ReleaseFiles.readmeVersion("**Status: 0.1.0, a\n**Status: 0.1.1, b"));
    }

    // ------------------------------------------------------------ which test run counts

    @Test
    void aTestRunCountsForLaterCommitsThatChangeOnlyTheReleaseNotes() {
        assertTrue(ReleaseFiles.testsCover("abc", "abc", true, List.of()));
        assertTrue(ReleaseFiles.testsCover("abc", "def", true, List.of("CHANGELOG.md", "README.md")));
        assertFalse(ReleaseFiles.testsCover("abc", "def", true, List.of("CHANGELOG.md", "pom.xml")), "code changed");
        assertFalse(ReleaseFiles.testsCover("abc", "def", false, List.of("CHANGELOG.md")), "not an earlier commit");
        assertFalse(ReleaseFiles.testsCover("", "def", true, List.of()), "never ran");
    }

    // ------------------------------------------------------------ the next step

    @Test
    void theNextStepFollowsWhatIsDone() {
        assertEquals("check", new ReleaseState(V, false, false, false, false, false, false).nextStep());
        assertEquals("prepare", new ReleaseState(V, true, false, false, false, false, false).nextStep());
        assertEquals("check", new ReleaseState(V, false, true, false, false, false, false).nextStep(), "prepared but untested");
        assertEquals("promote", new ReleaseState(V, true, true, false, false, false, false).nextStep());
        assertEquals("tag", new ReleaseState(V, true, true, true, false, false, false).nextStep());
        assertEquals("tag", new ReleaseState(V, true, true, true, true, false, false).nextStep(), "tagged, the Release not out yet");
        assertEquals("next", new ReleaseState(V, true, true, true, true, true, false).nextStep());
        assertEquals("done", new ReleaseState(V, true, true, true, true, true, true).nextStep());
        assertEquals("Next step: java releng/tools/release/Release.java promote", new ReleaseState(V, true, true, false, false, false, false).nextLine("releng/tools/release/Release.java"));
        assertEquals("Release " + V + " is done.", new ReleaseState(V, true, true, true, true, true, true).nextLine("releng/tools/release/Release.java"));
    }

    // ------------------------------------------------------------ the command line and the gate

    @Test
    void theCommandLineNamesOneStepAndItsFlags() {
        Options o = Options.parse("promote", "--ci-on-branch", "--no-rehearsal");
        assertEquals("promote", o.step());
        assertTrue(o.ciOnBranch());
        assertFalse(o.rehearse());
        assertEquals(Ask.Mode.ASK, o.mode());
        assertEquals("status", Options.parse().step());
        assertEquals(Version.parse("0.3.0"), Options.parse("next", "--next", "0.3.0").next());
        assertEquals(Ask.Mode.DRY_RUN, Options.parse("--dry-run", "--yes").mode(), "a dry run never acts");
        assertEquals(Ask.Mode.DRY_RUN, Options.parse("--yes", "--dry-run").mode());
        for (String[] bad : List.of(new String[] {"publish"}, new String[] {"tag", "next"}, new String[] {"--force"}, new String[] {"next", "--next"})) {
            assertThrows(ToolFailure.class, () -> Options.parse(bad), String.join(" ", bad));
        }
    }

    @Test
    void aRunWithoutATerminalPrintsTheOneStepToApprove() {
        assertEquals("java releng/tools/release/Release.java promote --ci-on-branch --yes",
                Options.parse("go", "--ci-on-branch").rerunWithYes("promote"));
        assertEquals("java releng/tools/release/Release.java tag --yes", Options.parse("tag", "--dry-run").rerunWithYes("tag"));
        assertEquals("java releng/tools/release/Release.java next --next 0.3.0 --yes",
                Options.parse("next", "--next", "0.3.0", "--yes").rerunWithYes("next"));
    }

    @Test
    void theGateAsksOnATerminalAndStopsWithoutOne() {
        assertEquals(Ask.Decision.PROMPT, Ask.decide(Ask.Mode.ASK, true));
        assertEquals(Ask.Decision.STOP, Ask.decide(Ask.Mode.ASK, false), "never waits for an answer that cannot come");
        assertEquals(Ask.Decision.DO, Ask.decide(Ask.Mode.YES, false));
        assertEquals(Ask.Decision.SKIP, Ask.decide(Ask.Mode.DRY_RUN, true));
        assertTrue(Ask.isYes(" Y "));
        assertTrue(Ask.isYes("yes"));
        for (String no : new String[] {"", "n", "ye", "yes please", null}) {
            assertFalse(Ask.isYes(no), String.valueOf(no));
        }
    }

    // ------------------------------------------------------------ settings, runs, files

    @Test
    void localSettingsOverrideTheTrackedOnes(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("settings.properties"), "tests=--all\ncommit.trailer=\n");
        assertEquals("--all", Settings.load(dir, Map.of()).get("tests"));
        Files.writeString(dir.resolve("local.properties"), "tests=\n");
        assertEquals("", Settings.load(dir, Map.of()).get("tests"));
        assertEquals("x", Settings.load(dir, Map.of("tests", "x")).get("tests"), "the command line wins");
        Path tool = Files.createDirectories(dir.resolve("tool"));
        Files.writeString(tool.resolve("settings.properties"), "tests=--tool\n");
        assertEquals("--tool", Settings.load(List.of(dir, tool), Map.of()).get("tests"), "a tool's own folder after the common one");
        assertEquals("", Settings.load(List.of(dir, tool), Map.of()).get("commit.trailer"), "the common one's keys stay");
        assertThrows(ToolFailure.class, () -> Settings.of("").get("missing"));
        assertEquals("fallback", Settings.of("").get("missing", "fallback"));
    }

    @Test
    void theSettingsNameWhatTheWorkflowsContain() throws IOException {
        Path repo = repo();
        Settings s = Settings.load(List.of(repo.resolve(Settings.COMMON), repo.resolve("releng/tools/release")), Map.of());
        assertEquals("", Settings.load(List.of(repo.resolve(Settings.COMMON)), Map.of("commit.trailer", "")).get("commit.trailer"));
        for (String key : List.of("build.workflow", "release.workflow", "rehearsal.workflow")) {
            assertTrue(Files.isRegularFile(repo.resolve(".github/workflows").resolve(s.get(key))), key + " = " + s.get(key));
        }
        String build = Files.readString(repo.resolve(".github/workflows").resolve(s.get("build.workflow")));
        assertTrue(build.contains("'" + s.get("tested.job") + "'"), "build.yml no longer names the reused job \"" + s.get("tested.job") + "\"");
        String rehearsal = Files.readString(repo.resolve(".github/workflows").resolve(s.get("rehearsal.workflow")));
        assertTrue(rehearsal.contains("branches: ['" + s.get("rehearse.prefix") + "**']"), "the rehearsal no longer starts on pushes to "
                + s.get("rehearse.prefix"));
        assertTrue(Files.isRegularFile(repo.resolve(s.get("signing.key"))), s.get("signing.key"));
        assertTrue(s.get("site.url").startsWith("https://") && s.get("site.url").endsWith("/"), s.get("site.url"));
    }

    @Test
    void aRunLineFromGhIsRead() {
        GitHub.Run run = GitHub.parseRun("42186112399\tcompleted\tsuccess\thttps://github.com/o/r/actions/runs/42186112399\tpush\tmain").orElseThrow();
        assertEquals(42186112399L, run.id());
        assertTrue(run.passed());
        assertFalse(GitHub.parseRun("42\tin_progress\t\thttps://x\tpush\tmain").orElseThrow().passed());
        assertTrue(GitHub.parseRun("not a run").isEmpty());
    }

    /**
     * A repository of one commit in {@code dir}, with a user of its own; false when git is not
     * installed. Line ends are kept as written ({@code core.autocrlf false}): with git's Windows
     * default, {@code true}, a file reset to its committed text comes back as {@code one\r\n}, and
     * the tests compare texts, not line ends.
     */
    private static boolean repository(Path dir) throws Exception {
        Shell shell = new Shell(dir);
        for (List<String> c : List.of(List.of("git", "init", "-q"), List.of("git", "config", "user.name", "Test"),
                List.of("git", "config", "user.email", "test@example.invalid"), List.of("git", "config", "core.autocrlf", "false"))) {
            if (!shell.capture(c).ok()) {
                return false;
            }
        }
        Files.writeString(dir.resolve("a.txt"), "one\n");
        return shell.capture(List.of("git", "add", "a.txt")).ok() && shell.capture(List.of("git", "commit", "-q", "-m", "first")).ok();
    }

    @Test
    void anEditThatFailsLeavesTheTreeAsItWas(@TempDir Path dir) throws Exception {
        assumeTrue(repository(dir), "git is not installed");
        Git git = new Git(new Shell(dir));
        Ask yes = new Ask(Ask.Mode.YES, "rerun");
        assertThrows(ToolFailure.class, () -> git.editAndCommit(yes, "Edit", List.of(), "second", () -> {
            try {
                Files.writeString(dir.resolve("a.txt"), "two\n");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            throw new ToolFailure("the edit failed halfway");
        }));
        assertTrue(git.isClean(), "the half edit is gone");
        assertEquals("one\n", Files.readString(dir.resolve("a.txt")));
    }

    @Test
    void anEditIsCommittedInUtc(@TempDir Path dir) throws Exception {
        assumeTrue(repository(dir), "git is not installed");
        Git git = new Git(new Shell(dir));
        assertTrue(git.editAndCommit(new Ask(Ask.Mode.YES, "rerun"), "Edit", List.of(), "second", () -> {
            try {
                Files.writeString(dir.resolve("a.txt"), "two\n");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return List.of("a.txt");
        }));
        assertTrue(git.isClean());
        assertEquals("second +0000", git.out("log", "-1", "--format=%s %ad", "--date=format:%z"));
        assertFalse(git.editAndCommit(new Ask(Ask.Mode.DRY_RUN, "rerun"), "Edit", List.of(), "third", () -> {
            throw new AssertionError("a dry run edits nothing");
        }));
    }

    @Test
    void aFailedRunsReportNamesTheFailingTest() {
        String job = "All tests on Windows\tBuild, unit and UI tests\t2026-11-06T18:21:22.2351960Z ";
        String log = String.join("\n",
                job + "[INFO] Tests run: 5, Failures: 0",
                job + "[ERROR] Tests run: 19, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in checks.ReleaseToolTest",
                job + "[ERROR] checks.ReleaseToolTest.anEdit(Path) -- Time elapsed: 0.497 s <<< FAILURE!",
                job + "org.opentest4j.AssertionFailedError: ",
                job + "expected: <one",
                job + "\tat org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)",
                job + "[ERROR] Failures: ",
                job + "[ERROR]   ReleaseToolTest.anEdit:275 expected: <one",
                job + "[ERROR] Tests run: 1086, Failures: 1, Errors: 0, Skipped: 3",
                job + "[ERROR] -> [Help 1]",
                job + "##[error]Process completed with exit code 1.");
        assertEquals(String.join("\n",
                "All tests on Windows: [ERROR] checks.ReleaseToolTest.anEdit(Path) -- Time elapsed: 0.497 s <<< FAILURE!",
                "All tests on Windows: org.opentest4j.AssertionFailedError: ",
                "All tests on Windows: expected: <one",
                "All tests on Windows: [ERROR] Failures: ",
                "All tests on Windows: [ERROR]   ReleaseToolTest.anEdit:275 expected: <one"), GitHub.failureSummary(log, 40));
        assertEquals("b\nc", GitHub.failureSummary("a\nb\nc", 2), "no test named: the last lines, as before");
        assertEquals("job: [ERROR] red <<< FAILURE!", GitHub.failureSummary("job\tstep\t2026-11-06T18:21:22.1Z "
                + "[\u001B[1;31mERROR\u001B[m] red\u001B[2K <<< FAILURE!", 5),
                "colour codes and other control sequences are dropped");
        assertEquals("a\tb", GitHub.failureSummary("a\u0008\tb", 5), "control characters but tabs dropped, in the plain tail too");
    }

    @Test
    void theRunFollowedIsTheOneThePushStarted() {
        GitHub.Run failed = new GitHub.Run(41, "completed", "failure", "u41", "push", "rehearse/0.2.3");
        GitHub.Run fresh = new GitHub.Run(42, "queued", "", "u42", "push", "rehearse/0.2.3");
        assertEquals(Optional.empty(), GitHub.newestExcept(List.of(failed), Set.of(41L)), "not listed yet: wait, never the old run");
        assertEquals(Optional.of(fresh), GitHub.newestExcept(List.of(fresh, failed), Set.of(41L)));
        assertEquals(Optional.of(failed), GitHub.newestExcept(List.of(failed), Set.of()), "nothing started: the run that is there");
    }

    @Test
    void anEntryOutsideTheFolderIsRefused(@TempDir Path dir) throws Exception {
        Path zip = dir.resolve("site.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("content.jar"));
            out.write(1);
            out.putNextEntry(new ZipEntry("../escape.txt"));
            out.write(2);
        }
        assertThrows(ToolFailure.class, () -> Folders.unzip(zip, dir.resolve("site")));
        assertFalse(Files.exists(dir.resolve("escape.txt")));
        assertTrue(Files.isRegularFile(dir.resolve("site/content.jar")), "the entries before it are unpacked");
    }
}
