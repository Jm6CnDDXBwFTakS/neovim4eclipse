// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * The Eclipse half of {@code Ctrl-O} and {@code Ctrl-I} past Neovim's jumps
 * ({@link NavigationHistorySteps}) that needs a workbench but no Neovim: a closed workbench window
 * leaves nothing behind. Which places count as gone is {@code LocalFilesTest} (tests/unit); the
 * steps themselves are {@code HistoryStepsTest} and {@code EditorEndToEndTest}.
 */
class NavigationHistoryStepsTest {

    private static final long TIMEOUT_MS = TestWait.millis();

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
