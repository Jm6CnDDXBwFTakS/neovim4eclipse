// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.sync.SyncProbe;

/**
 * The comparison at the end of each UI test ({@code EndToEndTestBase.TextCheckAtEnd}): every open
 * editor's document against its Neovim buffer, line by line.
 *
 * <p>Steps, on the UI thread:
 * <ol>
 *   <li>Wait for the queued work and the UI ({@link UiTestSupport#settle}), so that the mode known
 *       is Neovim's.</li>
 *   <li>Back to normal mode: a test may end in insert or replace mode, and the text Eclipse typed
 *       reaches Neovim only at {@code Esc}. While Eclipse types, the {@code Esc} goes through the
 *       active editor's widget, as a user's would, so the editor ends the insert and pushes; in
 *       another mode (visual, a pending operator) straight to Neovim. Then settle again.</li>
 *   <li>For each document with a buffer in the running Neovim (a clone's document once), compare
 *       the lines Neovim should hold ({@link SyncProbe#linesNeovimShouldHave}) with
 *       {@code nvim_buf_get_lines}.</li>
 * </ol>
 *
 * <p>Example of a report: {@code buffer 3 (test.txt): line 2 is "beta" in Eclipse, "BETA" in Neovim
 * (3 lines in Eclipse, 3 in Neovim)}.
 */
final class TextCheck {

    private TextCheck() {
    }

    /**
     * @return what differs, one entry per document, joined; {@code null} when every open document
     *         equals its buffer, or no Neovim runs
     */
    static String differences(long timeoutMs) {
        NeovimSession session = Activator.getDefault().currentSession();
        if (session == null) {
            return null;
        }
        // Settled first: the mode the session knows is then the one Neovim is in, not one whose
        // report is still on its way.
        UiTestSupport.settle(session, timeoutMs);
        if (session.isTypingMode() || !"n".equals(session.getMode())) {
            String left = backToNormalMode(session, timeoutMs);
            if (left != null) {
                return left;
            }
            UiTestSupport.settle(session, timeoutMs);
        }
        EditorAttachManager manager = EditorAttachManager.getInstance();
        Set<IDocument> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> found = new ArrayList<>();
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorReference reference : page.getEditorReferences()) {
                    IEditorPart part = reference.getEditor(false); // an editor never shown has no document
                    ITextEditor text = part == null ? null : EditorAttachManager.textEditorOf(part);
                    IDocument document = text == null || text.getDocumentProvider() == null ? null
                            : EndToEndTestBase.documentOf(text);
                    if (document == null || !seen.add(document)) {
                        continue;
                    }
                    int bufnr = manager.bufferNumberOf(document);
                    if (bufnr > 0) {
                        String difference = compare(session, bufnr, document, reference.getName());
                        if (difference != null) {
                            found.add(difference);
                        }
                    }
                }
            }
        }
        return found.isEmpty() ? null : String.join("; ", found);
    }

    /** Presses {@code Esc}; returns why normal mode was not reached, or {@code null}. */
    private static String backToNormalMode(NeovimSession session, long timeoutMs) {
        String mode = session.getMode();
        NeovimEditorController active = EditorAttachManager.getInstance().getActiveController();
        StyledText widget = active == null || active.isClosed() ? null : EndToEndTestBase.widgetOf(active.getEditor());
        if (session.isTypingMode() && widget != null && !widget.isDisposed()) {
            // Eclipse types (insert or replace mode): the editor's own Esc ends that and pushes.
            UiTestSupport.type(widget, (char) SWT.ESC);
        } else {
            session.inputAsync("<Esc>");
        }
        return UiTestSupport.waitUntilOrGiveUp(timeoutMs, () -> "n".equals(session.getMode())) ? null
                : "Neovim did not return to normal mode from mode " + mode + " (now " + session.getMode() + ")";
    }

    private static String compare(NeovimSession session, int bufnr, IDocument document, String name) {
        List<String> eclipse = SyncProbe.linesNeovimShouldHave(document);
        Object answer = SessionProbe.lua(session, "return vim.api.nvim_buf_get_lines(" + bufnr + ", 0, -1, false)");
        List<String> neovim = new ArrayList<>();
        if (answer instanceof List<?> lines) {
            lines.forEach(line -> neovim.add(String.valueOf(line)));
        }
        if (neovim.equals(eclipse)) {
            return null;
        }
        int line = 0;
        while (line < eclipse.size() && line < neovim.size() && eclipse.get(line).equals(neovim.get(line))) {
            line++;
        }
        return "buffer " + bufnr + " (" + name + "): line " + (line + 1) + " is " + quoted(eclipse, line)
                + " in Eclipse, " + quoted(neovim, line) + " in Neovim (" + eclipse.size() + " lines in Eclipse, "
                + neovim.size() + " in Neovim)";
    }

    private static String quoted(List<String> lines, int line) {
        return line < lines.size() ? "\"" + lines.get(line) + "\"" : "missing";
    }
}
