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
    void theUpgradeToolsCommitOnlyOnAWorkBranch() {
        Version making = Version.parse("0.2.11");
        assertTrue(Git.isWorkBranch("release/0.2.11", making));
        assertTrue(Git.isWorkBranch("fix/maven-tool", making));
        assertTrue(Git.isWorkBranch("feature/maven-tool", making));
        assertFalse(Git.isWorkBranch("release/0.2.10", making), "an earlier release's branch is finished");
        assertFalse(Git.isWorkBranch("release/0.2.12", making), "a branch the POM does not name");
        assertFalse(Git.isWorkBranch("main", making), "main moves only at a release");
        assertFalse(Git.isWorkBranch("", making), "a detached HEAD");
        assertFalse(Git.isWorkBranch("rehearse/0.2.11", making));
        // The Release tool deletes merged topic branches, never the release branch through this rule.
        assertTrue(Git.isTopicBranch("fix/maven-tool") && Git.isTopicBranch("feature/maven-tool"));
        assertFalse(Git.isTopicBranch("release/0.2.11"));
    }

    @Test
    void anUpgradeToolStopsOffTheCurrentReleasesBranches(@TempDir Path dir) throws Exception {
        assumeTrue(repository(dir), "git is not installed");
        Shell shell = new Shell(dir);
        Git git = new Git(shell);
        Version making = Version.parse("0.2.11");
        // -C, not -c: git may have named the first branch main already (init.defaultBranch).
        assertTrue(shell.capture(List.of("git", "switch", "-q", "-C", "main")).ok());
        ToolFailure onMain = assertThrows(ToolFailure.class, () -> git.checkWorkBranch("move Maven", making.snapshot()));
        assertTrue(onMain.getMessage().startsWith("this is main; move Maven on release/0.2.11, or on a fix/ or feature/ branch"),
                onMain.getMessage());
        assertTrue(shell.capture(List.of("git", "switch", "-q", "-c", "release/0.2.10")).ok());
        assertThrows(ToolFailure.class, () -> git.checkWorkBranch("move Maven", making.snapshot()));
        assertTrue(shell.capture(List.of("git", "switch", "-q", "--detach")).ok());
        ToolFailure detached = assertThrows(ToolFailure.class, () -> git.checkWorkBranch("move Maven", making.snapshot()));
        assertTrue(detached.getMessage().startsWith("this is a detached HEAD;"), detached.getMessage());
        assertTrue(shell.capture(List.of("git", "switch", "-q", "-c", "release/0.2.11")).ok());
        git.checkWorkBranch("move Maven", making.snapshot()); // no failure
        // After a release the POM says 0.2.11 on main: the branch is what is wrong, and the message says so.
        assertTrue(shell.capture(List.of("git", "switch", "-q", "main")).ok());
        ToolFailure released = assertThrows(ToolFailure.class, () -> git.checkWorkBranch("move Maven", "0.2.11"));
        assertTrue(released.getMessage().startsWith("this is main; move Maven on the release branch,"), released.getMessage());
        assertTrue(shell.capture(List.of("git", "switch", "-q", "-c", "fix/y")).ok());
        git.checkWorkBranch("move Maven", "0.2.11"); // a fix branch needs no release in the POM
        // 0.2.10 is released (its tag exists): a checkout still at 0.2.10-SNAPSHOT is finished work, on any branch.
        assertTrue(shell.capture(List.of("git", "tag", "v0.2.10")).ok());
        for (String branch : List.of("release/0.2.10", "fix/z")) {
            assertTrue(shell.capture(List.of("git", "switch", "-q", "-C", branch)).ok());
            ToolFailure finished = assertThrows(ToolFailure.class, () -> git.checkWorkBranch("move Maven", "0.2.10-SNAPSHOT"));
            assertTrue(finished.getMessage().contains("0.2.10, which is released (tag v0.2.10)"), finished.getMessage());
        }
    }

    @Test
    void theProjectVersionIsReadFromTheRootPom() throws IOException {
        assertEquals("0.2.11-SNAPSHOT", Version.inPom("""
                <parent><artifactId>other</artifactId><version>9.9.9</version></parent>
                <artifactId>neovim4eclipse</artifactId>
                <version>0.2.11-SNAPSHOT</version>
                """));
        assertThrows(ToolFailure.class, () -> Version.inPom("<project/>"));
        Version.ofSnapshot(Version.inPom(Files.readString(Path.of("../../pom.xml")))); // the repository's own, a -SNAPSHOT
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

    /**
     * A build that failed before any test ran, as the 0.2.15 Release run's "Set version" step did:
     * the cause is in Maven's first error lines, about ten lines above the log's end, and the advice
     * and the runner's cleanup after them name nothing. The report is those error lines alone, and
     * only those after {@code BUILD FAILURE}: an {@code [ERROR]} line of an earlier Maven call that
     * passed is no cause.
     */
    @Test
    void aFailedBuildsReportNamesMavensErrorNotItsAdvice() {
        String job = "Build / Build (tested on main)\tSet version 0.2.15\t2026-11-12T06:51:16.1114309Z ";
        String log = String.join("\n",
                job + "[ERROR] a line an earlier Maven call logged, and passed",
                job + "[INFO] BUILD SUCCESS",
                job + "[WARNING] The POM for org.eclipse.tycho:tycho-versions-plugin:jar:5.0.4 is missing, no dependency information available",
                job + "[INFO] BUILD FAILURE",
                job + "[ERROR] Plugin org.eclipse.tycho:tycho-versions-plugin:5.0.4 or one of its dependencies could not be resolved:",
                job + "[ERROR] \torg.eclipse.tycho:tycho-versions-plugin:jar:5.0.4 was not found in https://repo.maven.apache.org/maven2",
                job + "[ERROR] -> [Help 1]",
                job + "[ERROR] ",
                job + "[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.",
                job + "[ERROR] Re-run Maven using the -X switch to enable full debug logging.",
                job + "[ERROR] For more information about the errors and possible solutions, please read the following articles:",
                job + "[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/PluginResolutionException",
                job + "[ERROR] After correcting the problems, you can resume the build with the command",
                job + "[ERROR]   mvn <args> -rf :neovim4eclipse",
                job + "##[error]Process completed with exit code 1.",
                job + "Post job cleanup.",
                job + "[command]/usr/bin/git version");
        assertEquals(String.join("\n",
                "Build / Build (tested on main): [ERROR] Plugin org.eclipse.tycho:tycho-versions-plugin:5.0.4 or one of its dependencies could not be resolved:",
                "Build / Build (tested on main): [ERROR] \torg.eclipse.tycho:tycho-versions-plugin:jar:5.0.4 was not found in https://repo.maven.apache.org/maven2"),
                GitHub.failureSummary(log, 40));

        String signing = "Sign with PGP\tSign\t2026-11-12T06:59:00.0Z ";
        assertEquals(String.join("\n", signing + "gpg: using key 0123", signing + "gpg: signing failed: No secret key"),
                GitHub.failureSummary(String.join("\n",
                        job + "[ERROR] a line a Maven call that passed logged",
                        job + "[INFO] BUILD SUCCESS",
                        signing + "gpg: using key 0123",
                        signing + "gpg: signing failed: No secret key",
                        signing + "##[error]Process completed with exit code 2.",
                        signing + "Post job cleanup.",
                        signing + "[command]/usr/bin/git version"), 2),
                "no Maven call failed: the last lines before the runner's cleanup, which show the step that did");

        String pom = "Build\tSet version 0.2.16\t2026-11-12T06:51:16.1Z ";
        assertEquals(String.join("\n",
                "Build: [ERROR] [ERROR] Some problems were encountered while processing the POMs:",
                "Build: [ERROR] Non-resolvable parent POM for x:y:1: not found",
                "Build: [ERROR] The build could not read 1 project -> [Help 1]"),
                GitHub.failureSummary(String.join("\n",
                        pom + "[INFO] Scanning for projects...",
                        pom + "[ERROR] [ERROR] Some problems were encountered while processing the POMs:",
                        pom + "[ERROR] Non-resolvable parent POM for x:y:1: not found",
                        pom + "[ERROR] The build could not read 1 project -> [Help 1]",
                        pom + "[ERROR] ",
                        pom + "[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.",
                        pom + "##[error]Process completed with exit code 1."), 40),
                "a POM Maven cannot read stops it with no BUILD FAILURE line; its error lines still count");

        String macos = "All tests on macOS\tBuild, unit and UI tests\t2026-11-12T06:51:16.1Z ";
        String windows = "All tests on Windows\tBuild, unit and UI tests\t2026-11-12T06:51:16.1Z ";
        assertEquals(String.join("\n",
                "All tests on macOS: [ERROR] Plugin x could not be resolved",
                "All tests on Windows: [ERROR] Plugin y could not be resolved"),
                GitHub.failureSummary(String.join("\n",
                        macos + "[INFO] BUILD FAILURE",
                        macos + "[ERROR] Plugin x could not be resolved",
                        windows + "[ERROR] a line a Maven call that passed logged",
                        windows + "[INFO] BUILD FAILURE",
                        windows + "[ERROR] Plugin y could not be resolved"), 40),
                "each failed job starts again: the next job's passing Maven call is no cause");
    }

    /**
     * A run whose tests failed, as a surefire log ends: the failing test and Maven's summary, then
     * the goal that failed (which names the module) and the advice under it, which is left out.
     */
    @Test
    void aFailedTestRunsReportEndsWithTheGoalThatFailed() {
        String job = "Build\tBuild, unit and UI tests\t2026-11-12T06:51:16.1Z ";
        String goal = "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.4:test (default-test)"
                + " on project unit: There are test failures.";
        String log = String.join("\n",
                job + "[ERROR] checks.ReleaseToolTest.anEdit(Path) -- Time elapsed: 0.497 s <<< FAILURE!",
                job + "org.opentest4j.AssertionFailedError: expected: <one",
                job + "\tat org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)",
                job + "[ERROR] Failures: ",
                job + "[ERROR]   ReleaseToolTest.anEdit:275 expected: <one",
                job + "[ERROR] Tests run: 1086, Failures: 1, Errors: 0, Skipped: 3",
                job + "[INFO] BUILD FAILURE",
                job + goal,
                job + "[ERROR] ",
                job + "[ERROR] Please refer to tests/unit/target/surefire-reports for the individual test results.",
                job + "[ERROR] -> [Help 1]");
        assertEquals(String.join("\n",
                "Build: [ERROR] checks.ReleaseToolTest.anEdit(Path) -- Time elapsed: 0.497 s <<< FAILURE!",
                "Build: org.opentest4j.AssertionFailedError: expected: <one",
                "Build: [ERROR] Failures: ",
                "Build: [ERROR]   ReleaseToolTest.anEdit:275 expected: <one",
                "Build: " + goal), GitHub.failureSummary(log, 40));
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
