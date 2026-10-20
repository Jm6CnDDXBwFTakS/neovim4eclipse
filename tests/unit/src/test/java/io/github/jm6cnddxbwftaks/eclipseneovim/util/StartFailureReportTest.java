// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

/** When the "Neovim not started" notice is shown, and what it says. */
class StartFailureReportTest {

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

    // What it says

    @Test
    void theMessageNamesTheExecutableAndTheInnermostCause() {
        Exception error = new IllegalStateException("could not start the session",
                new IOException("Cannot run program \"nvim\": error=2, No such file or directory"));
        String message = StartFailureReport.message("/usr/bin/nvim", error);
        assertTrue(message.startsWith("Eclipse Neovim could not start Neovim"), message);
        assertTrue(message.contains("\nExecutable: /usr/bin/nvim\n"), message);
        assertTrue(message.contains("\nError: Cannot run program \"nvim\": error=2, No such file or directory\n"),
                message);
        assertTrue(message.contains("Neovim preference page"), message);
    }

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
}
