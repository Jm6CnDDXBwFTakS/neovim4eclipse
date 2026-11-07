// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * The Eclipse half of {@code Ctrl-O} and {@code Ctrl-I} past Neovim's jumps
 * ({@link NavigationHistorySteps}), the parts that need no Neovim: which places count as gone, and
 * that a closed workbench window leaves nothing behind. The steps themselves are
 * {@code HistoryStepsTest} and {@code EditorEndToEndTest}.
 */
class NavigationHistoryStepsTest {

    private static final long TIMEOUT_MS = TestWait.millis();

    @Test
    void aMissingLocalFileIsGoneAndAnExistingOneIsNot() throws Exception {
        // A folder made by hand: JUnit's @TempDir is in a package the fragment does not import.
        Path folder = Files.createTempDirectory("neovim4eclipse-history");
        Path there = Files.writeString(folder.resolve("there.txt"), "x");
        try {
            assertFalse(NavigationHistorySteps.isMissingLocalFile(there.toUri()));
            assertTrue(NavigationHistorySteps.isMissingLocalFile(folder.resolve("gone.txt").toUri()));
        } finally {
            Files.delete(there);
            Files.delete(folder);
        }
    }

    @Test
    void anythingButALocalFileIsNeverGone() {
        assertFalse(NavigationHistorySteps.isMissingLocalFile(null));
        assertFalse(NavigationHistorySteps.isMissingLocalFile(URI.create("https://example.org/gone.txt")));
        assertFalse(NavigationHistorySteps.isMissingLocalFile(URI.create("jar:file:/lib/a.jar!/gone.txt")));
    }

    /**
     * {@code Path.of} refuses a {@code file:} URI with a host outside Windows (on Windows it is a
     * UNC path, and checked); the step must not break off with that exception.
     */
    @Test
    void aFileUriWithAHostIsTakenAsThere() {
        assumeFalse(System.getProperty("os.name", "").startsWith("Windows"), "a UNC path on Windows");
        assertFalse(NavigationHistorySteps.isMissingLocalFile(URI.create("file://server/share/notes.txt")));
    }

    /** The steps kept for a page go with it: a closed window keeps no page, editor or history alive. */
    @Test
    void aClosedWindowsStepsAreDropped() throws Exception {
        IWorkbenchWindow window = PlatformUI.getWorkbench().openWorkbenchWindow(null);
        IWorkbenchPage page = window.getActivePage();
        try {
            NavigationHistorySteps.afterSwitch(page, new NavigationHistorySteps.Trail() {
                @Override
                public void wentNowhere(long first, int steps) {
                    // not reached: no Back step is made
                }

                @Override
                public void forget() {
                    // nothing to forget
                }
            });
            waitUntil("steps kept for the new window's page", TIMEOUT_MS,
                    () -> NavigationHistorySteps.hasStepsFor(page));
        } finally {
            window.close();
        }
        assertFalse(NavigationHistorySteps.hasStepsFor(page), "the closed page's steps are gone");

        // Work posted before the close that runs after it (afterSwitch's deferred check, a Ctrl-O)
        // makes no steps for the closed page again.
        NavigationHistorySteps.afterSwitch(page, new NavigationHistorySteps.Trail() {
            @Override
            public void wentNowhere(long first, int steps) {
                // not reached
            }

            @Override
            public void forget() {
                // nothing to forget
            }
        });
        Display display = Display.getCurrent();
        for (int i = 0; i < 3; i++) { // afterSwitch posts twice, one inside the other
            while (display.readAndDispatch()) {
                // run what is queued
            }
        }
        assertFalse(NavigationHistorySteps.hasStepsFor(page), "no steps kept for a closed page");
    }
}
