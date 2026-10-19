// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Display;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.ClipboardPort;

/**
 * The system clipboard through SWT (the SWT implementation of {@link ClipboardPort}): text yanked in
 * Neovim is copied to it, and Neovim's {@code "+p} and {@code "*p} read it.
 *
 * <p><b>Threads.</b> SWT's {@link Clipboard} may only be used on the UI thread, but Neovim's
 * copies and pastes arrive on the RPC thread. A copy is queued ({@code asyncExec}) and returns at
 * once. A paste must return the text, so it queues the read and waits for it, at most
 * {@value #PASTE_TIMEOUT_MS} ms. The wait is bounded because Neovim waits for the paste too: if the
 * UI thread were itself waiting for Neovim, neither would ever go on. After the timeout the paste
 * returns nothing, Neovim reports an empty register, and both carry on.
 *
 * <p><b>Two clipboards on Linux.</b> X11 and Wayland also have the <em>primary selection</em>
 * (the text last selected, pasted with the middle mouse button). SWT reaches it as
 * {@link DND#SELECTION_CLIPBOARD}, on GTK only; elsewhere {@code "*} uses the one clipboard, as in Vim.
 */
public final class ClipboardBridge implements ClipboardPort {

    /** How long a paste waits for the UI thread to read the clipboard. */
    static final long PASTE_TIMEOUT_MS = 2000;

    public static final ClipboardBridge INSTANCE = new ClipboardBridge(PASTE_TIMEOUT_MS);

    private static final Logger LOG = Logger.getLogger(ClipboardBridge.class.getName());

    private final long pasteTimeoutMs;

    /** @param pasteTimeoutMs see {@link #PASTE_TIMEOUT_MS}; tests use a shorter one */
    ClipboardBridge(long pasteTimeoutMs) {
        this.pasteTimeoutMs = pasteTimeoutMs;
    }

    /** Safe from any thread: the SWT work is queued to the UI thread. */
    @Override
    public void copy(Selection selection, String text) {
        if (text == null) {
            return;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        try {
            display.asyncExec(() -> withClipboard(display, clipboard -> {
                clipboard.setContents(new Object[] { text }, new TextTransfer[] { TextTransfer.getInstance() },
                        clipboardType(selection));
                return null;
            }));
        } catch (SWTException e) {
            LOG.log(Level.FINE, "Clipboard copy failed (display disposed)", e);
        }
    }

    /** Safe from any thread; blocks at most {@link #PASTE_TIMEOUT_MS} ms (no wait on the UI thread itself). */
    @Override
    public Optional<String> paste(Selection selection) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return Optional.empty();
        }
        if (display.getThread() == Thread.currentThread()) {
            return Optional.ofNullable(read(display, selection));
        }
        CompletableFuture<String> text = new CompletableFuture<>();
        try {
            display.asyncExec(() -> {
                try {
                    text.complete(read(display, selection));
                } catch (RuntimeException e) {
                    text.completeExceptionally(e);
                }
            });
            return Optional.ofNullable(text.get(pasteTimeoutMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            LOG.warning(() -> "The UI thread did not read the clipboard within " + pasteTimeoutMs
                    + " ms; Neovim pastes nothing");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | SWTException e) {
            LOG.log(Level.FINE, "Clipboard paste failed", e);
        }
        return Optional.empty();
    }

    /** On the UI thread: the clipboard's text, or {@code null} if it holds none. */
    private static String read(Display display, Selection selection) {
        return withClipboard(display,
                clipboard -> (String) clipboard.getContents(TextTransfer.getInstance(), clipboardType(selection)));
    }

    /** SWT's name for the selection. The primary selection exists only on GTK (Linux). */
    private static int clipboardType(Selection selection) {
        return selection == Selection.PRIMARY && "gtk".equals(SWT.getPlatform())
                ? DND.SELECTION_CLIPBOARD
                : DND.CLIPBOARD;
    }

    /** Runs {@code action} with a fresh {@link Clipboard} and disposes it (each holds OS resources). */
    private static <T> T withClipboard(Display display, Function<Clipboard, T> action) {
        Clipboard clipboard = null;
        try {
            clipboard = new Clipboard(display);
            return action.apply(clipboard);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Clipboard access failed", e);
            return null;
        } finally {
            if (clipboard != null && !clipboard.isDisposed()) {
                clipboard.dispose();
            }
        }
    }
}
