// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.IFileEditorInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;

/**
 * Eclipse's problems as Neovim diagnostics, in a plain text editor (no Java tooling): a problem
 * marker on the file reaches Neovim, Neovim's own {@code ]d} goes to it and the message line shows its
 * text, and the list follows the marker going away and the preference. A builder makes such markers;
 * the test makes one itself ({@code IResource.createMarker}), and the editor's annotation model shows
 * it as JDT's problems would show. The conversion is {@code DiagnosticPositionsTest}, the sending
 * {@code DiagnosticsSyncTest}, Neovim's side {@code diagnostics_spec.lua}.
 */
class DiagnosticsEndToEndTest extends EndToEndTestBase {

    /** How many of Eclipse's problems Neovim has for the attached buffer. */
    private int problemsInNeovim() {
        CompletableFuture<String> answer = new CompletableFuture<>();
        session.evalAsync("luaeval(\"#vim.diagnostic.get(0, { namespace = "
                + "vim.api.nvim_create_namespace('neovim4eclipse') })\")", answer::complete);
        try {
            return Integer.parseInt(answer.get(TIMEOUT_MS, TimeUnit.MILLISECONDS).trim());
        } catch (Exception e) {
            throw new AssertionError("Neovim did not tell its diagnostics", e);
        }
    }

    private void waitForProblems(int count) {
        waitUntil(count + " problems in Neovim", TIMEOUT_MS, () -> problemsInNeovim() == count,
                () -> "; it has " + problemsInNeovim());
    }

    private IFile file() {
        return ((IFileEditorInput) editor.getEditorInput()).getFile();
    }

    /** An error marker on {@code "gamma"}, line 3 of the test file ("alpha\nbeta\ngamma\n"). */
    private IMarker errorOnGamma() throws Exception {
        IMarker marker = file().createMarker(IMarker.PROBLEM);
        marker.setAttributes(Map.of(
                IMarker.SEVERITY, IMarker.SEVERITY_ERROR,
                IMarker.MESSAGE, "gamma cannot be resolved",
                IMarker.CHAR_START, 11,
                IMarker.CHAR_END, 16,
                IMarker.LINE_NUMBER, 3));
        return marker;
    }

    @AfterEach
    void removeMarkers() throws Exception {
        // The test file is shared by every test of every class: leave it without problems.
        file().deleteMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
        Activator.getDefault().getPreferenceStore().setToDefault(PreferenceConstants.SEND_PROBLEMS);
    }

    @Test
    void neovimsBracketDGoesToAProblemAndShowsItsMessage() throws Exception {
        waitForProblems(0);
        errorOnGamma();
        waitForProblems(1);

        typeSlowly("gg]d");

        waitUntil("the cursor on line 3", TIMEOUT_MS, () -> cursorLine() == 3, () -> "; it is on " + cursorLine());
        waitUntil("the caret on line 3", TIMEOUT_MS, () -> widget.getLineAtOffset(widget.getCaretOffset()) == 2);
        waitUntil("the message", TIMEOUT_MS, () -> "gamma cannot be resolved".equals(messageText(editor)),
                () -> "; the message line shows \"" + messageText(editor) + "\"");
    }

    @Test
    void aFixedProblemLeavesNeovimToo() throws Exception {
        IMarker marker = errorOnGamma();
        waitForProblems(1);

        marker.delete();

        waitForProblems(0);
    }

    @Test
    void turningThePreferenceOffClearsThemAndOnSendsThemAgain() throws Exception {
        errorOnGamma();
        waitForProblems(1);
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();

        store.setValue(PreferenceConstants.SEND_PROBLEMS, false);
        waitForProblems(0);

        store.setValue(PreferenceConstants.SEND_PROBLEMS, true);
        waitForProblems(1);
        assertEquals(1, problemsInNeovim());
    }
}
