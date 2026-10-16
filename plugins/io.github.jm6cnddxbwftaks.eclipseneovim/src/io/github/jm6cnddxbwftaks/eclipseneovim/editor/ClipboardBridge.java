// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Display;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.ClipboardPort;

/**
 * Copies text yanked in Neovim to the system clipboard (SWT implementation of {@link ClipboardPort}).
 *
 * <p>One-way only: Neovim to OS. Pasting <em>from</em> the OS clipboard into Neovim is not
 * implemented (use Eclipse's own paste in insert mode); see ARCHITECTURE.md.
 */
public final class ClipboardBridge implements ClipboardPort {

    public static final ClipboardBridge INSTANCE = new ClipboardBridge();

    private static final Logger LOG = Logger.getLogger(ClipboardBridge.class.getName());

    private ClipboardBridge() {
    }

    /** Safe from any thread: the SWT work is queued to the UI thread. */
    @Override
    public void copyToSystem(List<String> lines, boolean linewise) {
        if (lines == null) {
            return;
        }
        // A whole-line copy ends with a line break, as in every other application.
        final String text = String.join(System.lineSeparator(), lines) + (linewise ? System.lineSeparator() : "");
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            Clipboard clipboard = null;
            try {
                clipboard = new Clipboard(display);
                clipboard.setContents(new Object[] { text }, new TextTransfer[] { TextTransfer.getInstance() });
            } catch (Exception e) {
                LOG.log(Level.FINE, "Clipboard copy failed", e);
            } finally {
                if (clipboard != null && !clipboard.isDisposed()) {
                    clipboard.dispose();
                }
            }
        });
    }
}
