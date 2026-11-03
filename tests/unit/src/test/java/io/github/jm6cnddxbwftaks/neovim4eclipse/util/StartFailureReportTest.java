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
        String message = StartFailureReport.message("/usr/bin/nvim", error, List.of(), Optional.empty());
        assertTrue(message.startsWith("Neovim for Eclipse could not start Neovim"), message);
        assertTrue(message.contains("\nExecutable: /usr/bin/nvim\n"), message);
        assertTrue(message.contains("\nError: Cannot run program \"nvim\": error=2, No such file or directory\n"),
                message);
        assertTrue(message.contains("Neovim preference page (Window > Preferences > Neovim)"), message);
        assertTrue(message.contains("leave the Neovim preference blank"), message);
        assertTrue(message.contains(NvimExecutable.dedicatedFolder().toString()), message);
        assertTrue(message.contains("Install Neovim " + NeovimVersion.MINIMUM + " or newer"), message);
        assertTrue(message.contains(StartFailureReport.RELEASES_URL), message);
        assertTrue(message.contains("Get Neovim " + NeovimVersion.MINIMUM), message);
        assertFalse(message.contains(NvimProbe.UNUSED_HEADING), message);
    }

    @Test
    void theMessageNamesWhatThePluginsFolderNoLongerUses() {
        String message = StartFailureReport.message("nvim", new IOException("x"),
                List.of("current", "nvim-linux-arm64_0.12.6"), Optional.empty());
        assertTrue(message.endsWith("\n\n" + NvimProbe.UNUSED_HEADING + "\n  "
                + NvimExecutable.dedicatedFolder().resolve("current") + "\n  "
                + NvimExecutable.dedicatedFolder().resolve("nvim-linux-arm64_0.12.6")), message);
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

    /** After an update raised the minimum, the plug-in's own old copy is named first, with its fix. */
    @Test
    void theMessageNamesAnOutdatedPluginNeovim() {
        Path old = Path.of("/n/la/bin/nvim");
        String message = StartFailureReport.message("/usr/bin/nvim", new IOException("x"), List.of(),
                Optional.of(new PluginNeovim.Outdated(old, new NeovimVersion(0, 11, 0))));
        assertTrue(message.contains("\n\nThe plug-in's Neovim 0.11.0 is too old: Get Neovim " + NeovimVersion.MINIMUM
                + " replaces it (" + old + ").\nInstall Neovim"), message);
    }
}
