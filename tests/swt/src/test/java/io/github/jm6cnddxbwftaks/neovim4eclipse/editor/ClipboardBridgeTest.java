// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.ClipboardPort.Selection;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * The real system clipboard through SWT: what is copied can be pasted, a paste from the RPC thread
 * waits for the UI thread, and gives up when the UI thread is busy. The test thread is the UI thread.
 */
class ClipboardBridgeTest {

    private static final long TIMEOUT_MS = TestWait.millis();

    /**
     * A paste from another thread waits for the UI thread at most this long. The test's limit, not
     * the plug-in's 2 s ({@link ClipboardBridge#PASTE_TIMEOUT_MS}): on a slow machine the UI thread
     * may take longer than 2 s to get to the read, and the paste would then fail with nothing wrong.
     */
    private final ClipboardBridge bridge = new ClipboardBridge(TIMEOUT_MS);

    @Test
    void copiedTextCanBePasted() {
        String expected = "copied " + System.nanoTime(); // unique, so an earlier run cannot pass it
        bridge.copy(Selection.CLIPBOARD, expected);
        waitUntil("the copy", () -> bridge.paste(Selection.CLIPBOARD).equals(Optional.of(expected)));
    }

    @Test
    void aPasteFromAnotherThreadWaitsForTheUiThread() throws Exception {
        bridge.copy(Selection.CLIPBOARD, "from the UI thread");
        waitUntil("the copy", () -> bridge.paste(Selection.CLIPBOARD).equals(Optional.of("from the UI thread")));
        CompletableFuture<Optional<String>> pasted = CompletableFuture.supplyAsync(() -> bridge.paste(Selection.CLIPBOARD));
        waitUntil("the paste", pasted::isDone); // the event loop must run for the read to happen

        assertEquals(Optional.of("from the UI thread"), pasted.get());
    }

    @Test
    void aPasteGivesUpWhenTheUiThreadIsBusy() throws Exception {
        ClipboardBridge impatient = new ClipboardBridge(200);
        try (ExpectedLog log = ExpectedLog.of(ClipboardBridge.class)) {
            CompletableFuture<Optional<String>> pasted = CompletableFuture.supplyAsync(() -> impatient.paste(Selection.CLIPBOARD));

            // The UI thread does not run its event loop, as if it were waiting for Neovim itself.
            Optional<String> result = pasted.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            assertEquals(Optional.empty(), result, "nothing, instead of a Neovim that waits forever");
            log.assertLogged(Level.WARNING, "The UI thread did not read the clipboard");
            runQueuedWork(); // the read the paste queued, so that it does not run in the next test
        }
    }

    @Test
    void thePrimarySelectionIsSeparateOnLinux() {
        assumeTrue("gtk".equals(SWT.getPlatform()), "only GTK has a primary selection");
        bridge.copy(Selection.CLIPBOARD, "clipboard");
        bridge.copy(Selection.PRIMARY, "primary");

        waitUntil("both copies", () -> bridge.paste(Selection.PRIMARY).equals(Optional.of("primary")));
        assertEquals(Optional.of("clipboard"), bridge.paste(Selection.CLIPBOARD));
    }

    @Test
    void elsewhereStarIsTheOneClipboard() {
        assumeTrue(!"gtk".equals(SWT.getPlatform()), "Windows and macOS have one clipboard");
        bridge.copy(Selection.PRIMARY, "star");

        waitUntil("the copy", () -> bridge.paste(Selection.CLIPBOARD).equals(Optional.of("star")));
    }

    /** Runs the event loop until {@code condition} holds (copies are queued to the event loop). */
    private static void waitUntil(String what, BooleanSupplier condition) {
        Display display = Display.getDefault();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out after " + TIMEOUT_MS + " ms waiting for: " + what);
            }
            if (!display.readAndDispatch()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("interrupted");
                }
            }
        }
    }

    /**
     * Runs the work queued for the UI thread so far: SWT runs {@code asyncExec} work in order, so
     * once a marker queued now has run, everything queued before it has too.
     */
    private static void runQueuedWork() {
        boolean[] ran = { false };
        Display.getDefault().asyncExec(() -> ran[0] = true);
        waitUntil("the work queued for the UI thread", () -> ran[0]);
    }
}
