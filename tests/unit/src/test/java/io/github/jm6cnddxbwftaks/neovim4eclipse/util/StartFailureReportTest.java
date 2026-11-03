// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage.Point;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable.Kind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable.Resolved;

/** When the "Neovim not started" notice is shown, and what it says. */
class StartFailureReportTest {

    /** A version lookup that must not be asked: the plug-in's own Neovim is never probed again. */
    private static final Function<Path, NeovimVersion> NOT_ASKED = p -> {
        throw new AssertionError("asked for the version of " + p);
    };

    /** A blank preference with no dedicated Neovim that checks out, and with one. */
    private static final Resolved NO_DEDICATED = new Resolved("/n/wa/bin/nvim.exe", Kind.NO_DEDICATED);
    private static final Resolved DEDICATED = new Resolved("/n/wa/bin/nvim.exe", Kind.DEDICATED);

    private final StartFailureReport report = new StartFailureReport();

    // Once per executable

    @Test
    void theFirstFailureIsReported() {
        assertTrue(report.shouldReport("nvim"));
    }

    @Test
    void theSameExecutableFailingAgainIsNot() {
        report.shouldReport("nvim");
        assertFalse(report.shouldReport("nvim"));
        assertFalse(report.shouldReport("nvim"));
    }

    @Test
    void anotherExecutableIsReportedAndThenTheFirstAgain() {
        report.shouldReport("nvim");
        assertTrue(report.shouldReport("/opt/nvim/bin/nvim")); // the preference changed
        assertTrue(report.shouldReport("nvim")); // and back: only the last one reported counts
    }

    @Test
    void aSuccessfulStartMakesTheNextFailureNewsAgain() {
        report.shouldReport("nvim");
        report.started();
        assertTrue(report.shouldReport("nvim"));
    }

    @Test
    void anEmptyPreferenceIsAnExecutableLikeAnyOther() {
        assertTrue(report.shouldReport(""));
        assertFalse(report.shouldReport(""));
    }

    // What it says: the dedicated Neovim (a blank preference)

    @Test
    void aMissingDedicatedNeovimIsNamedAsNotInstalledAndGetNeovimInstallsIt() {
        HeadedMessage message = StartFailureReport.message(NO_DEDICATED, new IOException("x"), List.of(),
                Optional.empty(), false, NOT_ASKED);
        assertEquals(StartFailureReport.INTRO, message.intro());
        assertEquals(List.of("Reason", "Fix"), headings(message));
        Point reason = message.point("Reason").orElseThrow();
        assertEquals("The dedicated Neovim is not installed.", reason.text());
        assertFalse(reason.hasCopy(), "no program to name");
        Point fix = message.point("Fix").orElseThrow();
        assertEquals("Get Neovim installs Neovim " + NeovimVersion.MINIMUM + " into the plug-in's folder. It "
                + "downloads the release from github.com, or takes a release archive file.", fix.text());
        assertEquals(NvimExecutable.dedicatedFolder().toString(), fix.copy());
        assertFalse(message.hasFooter(), message.footer());
        assertFalse(message.plain().contains("PATH"), "only the dedicated Neovim runs: " + message.plain());
    }

    /** An install that is not the release (an older one, or damaged) is the reason, whatever the error. */
    @Test
    void aDedicatedNeovimThatIsNotTheReleaseIsTheReasonAndGetNeovimReplacesIt() {
        HeadedMessage message = StartFailureReport.message(NO_DEDICATED, new IOException("x"), List.of(),
                Optional.of(new PluginNeovim.Unverified(Path.of("/n/la/bin/nvim"))), false, NOT_ASKED);
        assertEquals(List.of("Reason", "Fix"), headings(message), "no Error row: the reason says it all");
        assertEquals("The dedicated Neovim's files differ from Neovim " + NeovimVersion.MINIMUM + "'s release. The "
                + "install may be damaged.", message.point("Reason").orElseThrow().text(),
                "no version: such an install is not run");
        assertEquals("Get Neovim replaces it with Neovim " + NeovimVersion.MINIMUM + ".",
                message.point("Fix").orElseThrow().text());
    }

    /**
     * It matches the release, yet it failed: only the error can say more, on a row of its own; Get Neovim
     * reinstalls it, and the Error Log has the full error.
     */
    @Test
    void aMatchingDedicatedNeovimThatFailsShowsTheErrorAndOffersAReinstall() {
        HeadedMessage message = StartFailureReport.message(DEDICATED, wrapped(new TimeoutException()), List.of(),
                Optional.empty(), true, NOT_ASKED);
        assertEquals(List.of("Reason", "Error", "Fix"), headings(message));
        assertEquals("The dedicated Neovim matches Neovim " + NeovimVersion.MINIMUM + "'s release, and it did "
                + "not start.", message.point("Reason").orElseThrow().text());
        assertEquals("TimeoutException", message.point("Error").orElseThrow().text());
        Point fix = message.point("Fix").orElseThrow();
        assertEquals("Get Neovim reinstalls Neovim " + NeovimVersion.MINIMUM + ". The Eclipse Error Log (Window > "
                + "Show View > Error Log) has the full error.", fix.text());
        assertEquals(NvimExecutable.dedicatedFolder().toString(), fix.copy());
    }

    @Test
    void getNeovimIsOfferedForTheDedicatedNeovimAlwaysAndForAProgramOnlyWhileTheDedicatedOneDoesNotCheckOut() {
        Resolved program = new Resolved("/opt/nvim/bin/nvim", Kind.PROGRAM);
        assertTrue(StartFailureReport.offersGetNeovim(NO_DEDICATED, false), "install or replace it");
        assertTrue(StartFailureReport.offersGetNeovim(DEDICATED, true), "reinstall it");
        assertTrue(StartFailureReport.offersGetNeovim(program, false), "install it, then clear the preference");
        assertFalse(StartFailureReport.offersGetNeovim(program, true), "it is installed: clear the preference");
    }

    /** One row per entry, so each full path has its own Copy; the heading and the text only on the first. */
    @Test
    void theMessageNamesWhatThePluginsFolderNoLongerUses() {
        HeadedMessage message = StartFailureReport.message(NO_DEDICATED, new IOException("x"),
                List.of("current", "nvim-linux-arm64_0.12.6"), Optional.empty(), false, NOT_ASKED);
        List<Point> points = message.points();
        Point first = points.get(points.size() - 2);
        Point second = points.get(points.size() - 1);
        assertEquals("Unused", first.heading());
        assertEquals(NvimProbe.UNUSED_HEADING, first.text());
        assertEquals(NvimExecutable.dedicatedFolder().resolve("current").toString(), first.copy());
        assertEquals("", second.heading());
        assertEquals("", second.text());
        assertEquals(NvimExecutable.dedicatedFolder().resolve("nvim-linux-arm64_0.12.6").toString(), second.copy());
    }

    @Test
    void noUnusedPointWithoutUnusedEntries() {
        HeadedMessage message = StartFailureReport.message(NO_DEDICATED, new IOException("x"), List.of(),
                Optional.empty(), false, NOT_ASKED);
        assertFalse(message.plain().contains(NvimProbe.UNUSED_HEADING), message.plain());
    }

    // What it says: a program named in the preference (unsupported)

    @Test
    void aMissingProgramIsNamedWithItsPathToCopy() {
        String program = "/opt/missing/bin/nvim";
        HeadedMessage message = StartFailureReport.message(new Resolved(program, Kind.MISSING), new IOException("x"),
                List.of(), Optional.empty(), false, NOT_ASKED);
        assertEquals(List.of("Program", "Reason", "Fix"), headings(message));
        assertEquals(program, message.point("Program").orElseThrow().copy());
        Point reason = message.point("Reason").orElseThrow();
        assertEquals("The file is missing.", reason.text());
        assertFalse(reason.hasCopy(), "the path is on the Program row");
        Point fix = message.point("Fix").orElseThrow();
        assertEquals("Clear the Neovim program preference to use the dedicated Neovim, which can be installed with "
                + "Get Neovim. Otherwise, point the preference to a working Neovim " + NeovimVersion.MINIMUM + " or "
                + "newer. The plug-in runs that program to check for Neovim, so be sure it is Neovim.", fix.text());
        assertFalse(fix.hasCopy());
    }

    @Test
    void theProgramFixSaysTheDedicatedNeovimIsInstalledWhenItChecksOut() {
        assertTrue(StartFailureReport.programFix(true).startsWith("Clear the Neovim program preference to use the "
                + "dedicated Neovim, which is installed. Otherwise, point the preference to "),
                StartFailureReport.programFix(true));
    }

    @Test
    void aFolderIsNamedAsNotAProgram() {
        assertEquals(List.of("Reason: It is a folder, not a program."), said(Kind.FOLDER, NOT_ASKED));
    }

    @Test
    void aRelativePathIsNamedAsNotAbsolute() {
        assertEquals(List.of("Reason: The path is not absolute."), said(Kind.NOT_ABSOLUTE, NOT_ASKED));
    }

    @Test
    void aProgramWithoutANeovimVersionIsNamedSoAndTheErrorFollows() {
        assertEquals(List.of("Reason: It reports no Neovim version.", "Error: error=193, not valid"),
                said(Kind.PROGRAM, wrapped(new IOException("error=193, not valid")), p -> null));
    }

    @Test
    void anOldNeovimIsNamedWithItsVersion() {
        assertEquals(List.of("Reason: It is Neovim 0.12.4. Neovim for Eclipse needs " + NeovimVersion.MINIMUM
                + " or newer."), said(Kind.PROGRAM, p -> new NeovimVersion(0, 12, 4)));
    }

    @Test
    void aCurrentNeovimThatFailsIsNamedWithItsVersionAndTheErrorFollows() {
        NeovimVersion current = NeovimVersion.MINIMUM;
        assertEquals(List.of("Reason: It is Neovim " + current + ", and it did not start.", "Error: TimeoutException"),
                said(Kind.PROGRAM, wrapped(new TimeoutException()), p -> current));
    }

    /** The version is asked of the program the preference names, as its path. */
    @Test
    void theVersionIsAskedOfTheProgramItself() {
        Path asked = Path.of("/opt/nvim/bin/nvim");
        said(Kind.PROGRAM, p -> {
            assertTrue(FilePaths.same(asked, p), p.toString());
            return NeovimVersion.MINIMUM;
        });
    }

    // The innermost cause

    @Test
    void rootMessageWalksDownToTheLastCause() {
        Throwable error = new RuntimeException("outer", new RuntimeException("middle", new IOException("inner")));
        assertEquals("inner", StartFailureReport.rootMessage(error));
    }

    @Test
    void anExceptionWithoutACauseGivesItsOwnMessage() {
        assertEquals("alone", StartFailureReport.rootMessage(new IOException("alone")));
    }

    @Test
    void aCauseWithoutAMessageIsNamedByItsClass() {
        Throwable error = new RuntimeException("waiting for Neovim", new TimeoutException());
        assertEquals("TimeoutException", StartFailureReport.rootMessage(error));
    }

    /** As the start reports it: the session's own exception around the cause. */
    private static Throwable wrapped(Throwable cause) {
        return new IllegalStateException("could not start the session", cause);
    }

    private static List<String> headings(HeadedMessage message) {
        return message.points().stream().map(Point::heading).toList();
    }

    /** The Reason and any Error row for a program of this kind, each as {@code "heading: text"}. */
    private static List<String> said(Kind kind, Throwable error, Function<Path, NeovimVersion> versionOf) {
        Resolved resolved = new Resolved(Path.of("/opt/nvim/bin/nvim").toString(), kind);
        return StartFailureReport.message(resolved, error, List.of(), Optional.empty(), false, versionOf).points()
                .stream()
                .filter(p -> p.heading().equals("Reason") || p.heading().equals("Error"))
                .map(p -> p.heading() + ": " + p.text()).toList();
    }

    private static List<String> said(Kind kind, Function<Path, NeovimVersion> versionOf) {
        return said(kind, new IOException("x"), versionOf);
    }

    /** A blank preference without a verified dedicated Neovim opens Get Neovim instead, saying why. */
    @Test
    void withoutAVerifiedDedicatedNeovimGetNeovimOpensInstead() {
        Path own = Path.of("/n/la/bin/nvim");
        NvimExecutable.Resolved missing = new NvimExecutable.Resolved(own.toString(), Kind.NO_DEDICATED);
        assertEquals(Optional.of("The dedicated Neovim is not installed."),
                StartFailureReport.getNeovimInstead(missing, Optional.empty()));
        assertEquals(Optional.of("The dedicated Neovim's files differ from Neovim " + NeovimVersion.MINIMUM
                + "'s release. The install may be damaged."),
                StartFailureReport.getNeovimInstead(missing, Optional.of(new PluginNeovim.Unverified(own))));
        assertEquals(Optional.empty(), StartFailureReport.getNeovimInstead(
                new NvimExecutable.Resolved(own.toString(), Kind.DEDICATED), Optional.empty()),
                "verified but did not start: the notice, with its error");
        assertEquals(Optional.empty(), StartFailureReport.getNeovimInstead(
                new NvimExecutable.Resolved("/usr/bin/nvim", Kind.PROGRAM), Optional.empty()),
                "a named program: the notice");
    }
}
