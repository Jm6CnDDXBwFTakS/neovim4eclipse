// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;

/**
 * The quickfix lists of Eclipse's problems and tasks ({@code :Eproblems}, {@code :Etasks};
 * docs/design/DIAGNOSTICS.md, section 5), in plain text editors with markers the test makes (no Java
 * tooling): the first jump lands on the problem's column in an open file, {@code ]q} opens a closed
 * file's editor on the line and then moves to the column, the list follows marker changes while it
 * is current and leaves another list alone, {@code :copen} shows the Problems view, and
 * {@code :Etasks} lists the tasks with the user's tags. Which entries count and their order is
 * {@code QuickfixEntriesTest}; Neovim's side {@code quickfix_spec.lua}.
 */
class QuickfixEndToEndTest extends EndToEndTestBase {

    private IFile file() {
        return ((IFileEditorInput) editor.getEditorInput()).getFile();
    }

    private static IMarker marker(IFile file, String type, int severity, String message, int start, int line)
            throws Exception {
        IMarker marker = file.createMarker(type);
        marker.setAttributes(Map.of(IMarker.SEVERITY, severity, IMarker.MESSAGE, message,
                IMarker.CHAR_START, start, IMarker.CHAR_END, start + 1, IMarker.LINE_NUMBER, line));
        return marker;
    }

    /** The current quickfix list's entries' texts, as Neovim has them. */
    @SuppressWarnings("unchecked")
    private List<String> texts() {
        return (List<String>) SessionProbe.lua(session,
                "return vim.tbl_map(function(i) return i.text end, vim.fn.getqflist())");
    }

    private void waitForTexts(List<String> expected) {
        waitUntil("the list " + expected, TIMEOUT_MS, () -> expected.equals(texts()), () -> "; it is " + texts());
    }

    /** Neovim's cursor as {line (1-based), byte column (0-based)}. */
    private List<?> cursor() {
        return (List<?>) SessionProbe.lua(session, "return vim.api.nvim_win_get_cursor(0)");
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    /** Waits until the quickfix job has nothing scheduled, sleeping or running. */
    private static void waitForTheJob() {
        waitUntil("the quickfix job to be done", TIMEOUT_MS,
                () -> Job.getJobManager().find(QuickfixLists.class).length == 0);
    }

    private void closeAllButTheTestFile() {
        for (IEditorReference ref : page().getEditorReferences()) {
            IEditorPart part = ref.getEditor(false);
            if (part != null && part != editor) {
                page().closeEditor(part, false);
            }
        }
    }

    @AfterEach
    void forgetTheLists() {
        // One Neovim serves every test: start the next one with an empty stack and no task tags.
        waitForTheJob();
        SessionProbe.lua(session, "vim.fn.setqflist({}, 'f') vim.g.neovim4eclipse_task_tags = nil return 0");
        closeAllButTheTestFile();
    }

    /**
     * {@code :Eproblems} jumps to the first problem, in the open test file, at its column; {@code ]q}
     * opens the closed file's editor on its problem's line, at its first non-blank, and once that
     * editor is there the cursor moves on to the column.
     */
    @Test
    void eproblemsJumpsToTheColumnAndBracketQOpensAClosedFileThenFindsItsColumn() throws Exception {
        IFile closed = createFile("z.txt", "one\n    two three\n"); // sorts after test.txt
        marker(file(), IMarker.PROBLEM, IMarker.SEVERITY_ERROR, "mma is wrong", 13, 3); // "gamma": g at 11
        marker(closed, IMarker.PROBLEM, IMarker.SEVERITY_WARNING, "three is odd", 12, 2);

        session.inputAsync(":Eproblems<CR>");

        waitForTexts(List.of("mma is wrong", "three is odd"));
        waitUntil("the cursor on the error's column", TIMEOUT_MS, () -> List.of(3, 2).equals(
                cursor().stream().map(QuickfixEndToEndTest::number).toList()), () -> "; it is " + cursor());

        session.inputAsync("]q");
        waitUntil("z.txt's editor", TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && !c.isClosed() && c.getEditor().getTitle().equals("z.txt");
        });
        waitUntil("the cursor on the warning's column", TIMEOUT_MS, () -> List.of(2, 8).equals(
                cursor().stream().map(QuickfixEndToEndTest::number).toList()), () -> "; it is " + cursor());
        ITextEditor opened = EditorAttachManager.getInstance().getActiveController().getEditor();
        waitUntil("the caret there too", TIMEOUT_MS,
                () -> widgetOf(opened).getCaretOffset() == 12, () -> "; it is at " + widgetOf(opened).getCaretOffset());
    }

    /**
     * A marker added while our list is current joins it, in a closed file (seen through the markers'
     * changes) as in the open one (through its editor's problems); with another list current, nothing
     * changes.
     */
    @Test
    void theListFollowsTheMarkersWhileItIsCurrentAndLeavesAnotherListAlone() throws Exception {
        IFile closed = createFile("z.txt", "z1\nz2\n");
        marker(file(), IMarker.PROBLEM, IMarker.SEVERITY_ERROR, "first", 0, 1);
        waitUntil("the problem in Neovim's diagnostics", TIMEOUT_MS, () -> number(SessionProbe.lua(session,
                "return #vim.diagnostic.get(0)")) == 1); // its update is done before the list exists
        session.inputAsync(":Eproblems!<CR>");
        waitForTexts(List.of("first"));
        waitForTheJob();

        marker(closed, IMarker.PROBLEM, IMarker.SEVERITY_WARNING, "in a closed file", 3, 2);
        waitForTexts(List.of("first", "in a closed file"));
        marker(file(), IMarker.PROBLEM, IMarker.SEVERITY_WARNING, "second", 6, 2);
        waitForTexts(List.of("first", "second", "in a closed file"));

        session.inputAsync(":vimgrep /alpha/j %<CR>");
        waitForTexts(List.of("alpha"));
        marker(file(), IMarker.PROBLEM, IMarker.SEVERITY_INFO, "third", 11, 3);
        waitForTheJob();
        settle();
        assertEquals(List.of("alpha"), texts(), ":vimgrep's list is the user's");

        session.inputAsync(":colder<CR>");
        settle();
        file().findMarkers(IMarker.PROBLEM, false, IResource.DEPTH_ZERO)[0].delete(); // "first"
        waitForTexts(List.of("second", "third", "in a closed file"));
    }

    /** {@code :Eproblems warning} lists errors and warnings, not infos. */
    @Test
    void aSeverityLeavesOutTheLessSevere() throws Exception {
        marker(file(), IMarker.PROBLEM, IMarker.SEVERITY_INFO, "info", 0, 1);
        marker(file(), IMarker.PROBLEM, IMarker.SEVERITY_WARNING, "warning", 6, 2);
        session.inputAsync(":Eproblems! warning<CR>");
        waitForTexts(List.of("warning"));
    }

    /** {@code :copen} with our list current shows the Problems view. */
    @Test
    void copenShowsTheProblemsView() throws Exception {
        marker(file(), IMarker.PROBLEM, IMarker.SEVERITY_ERROR, "shown", 0, 1);
        session.inputAsync(":Eproblems!<CR>");
        waitForTexts(List.of("shown"));
        IViewPart before = page().findView(IPageLayout.ID_PROBLEM_VIEW);
        if (before != null) {
            page().hideView(before);
        }
        try {
            session.inputAsync(":copen<CR>");
            waitUntil("the Problems view", TIMEOUT_MS, () -> {
                IViewPart view = page().findView(IPageLayout.ID_PROBLEM_VIEW);
                return view != null && page().isPartVisible(view);
            });
        } finally {
            IViewPart view = page().findView(IPageLayout.ID_PROBLEM_VIEW);
            if (view != null) {
                page().hideView(view);
            }
            page().activate(editor);
        }
    }

    /** {@code :Etasks} lists the tasks whose tag is in {@code vim.g.neovim4eclipse_task_tags}. */
    @Test
    void etasksListsTheTasksWithTheUsersTags() throws Exception {
        marker(file(), IMarker.TASK, IMarker.SEVERITY_INFO, "JM6 look here", 6, 2);
        marker(file(), IMarker.TASK, IMarker.SEVERITY_INFO, "TODO someone else's", 11, 3);
        SessionProbe.lua(session, "vim.g.neovim4eclipse_task_tags = 'JM6' return 0");

        session.inputAsync(":Etasks<CR>");

        waitForTexts(List.of("JM6 look here"));
        waitUntil("the jump to it", TIMEOUT_MS, () -> List.of(2, 0).equals(
                cursor().stream().map(QuickfixEndToEndTest::number).toList()), () -> "; it is " + cursor());
        assertEquals("Eclipse tasks (JM6)", SessionProbe.lua(session, "return vim.fn.getqflist({ title = 0 }).title"));
    }
}
