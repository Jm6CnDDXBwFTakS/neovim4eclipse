// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.ctrlKey;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.link.LinkedModeModel;
import org.eclipse.jface.text.link.LinkedPosition;
import org.eclipse.jface.text.link.LinkedPositionGroup;
import org.eclipse.jface.text.link.LinkedModeUI;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.INavigationLocation;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * Real editor, real Neovim: types keys into the editor widget of test.txt and checks the document.
 * The setup, and how keys are delivered, is in {@link EndToEndTestBase}.
 */
class EditorEndToEndTest extends EndToEndTestBase {

    @Test
    void ddDeletesTheFirstLine() {
        type(widget, "gg");
        type(widget, "dd");
        expectText("beta\ngamma\n");
    }

    /**
     * {@code g??} encodes the line with ROT13, typed at once (faster than Neovim reports its state):
     * neither {@code ?} may open the backward-search prompt, which the second one once did.
     */
    @Test
    void gQuestionQuestionEncodesTheLine() {
        type(widget, "ggg??");
        expectText("nycun\nbeta\ngamma\n");
        assertNull(textChildOf(widget), "a ? opened the search prompt");
    }

    @Test
    void xDeletesTheCharacterUnderTheCursor() {
        type(widget, "gg0x");
        expectText("lpha\nbeta\ngamma\n");
    }

    @Test
    void typedTextIsRepeatedWithDot() {
        type(widget, "gg");
        type(widget, 'A');
        // Wait until Eclipse may type (the buffer sync for entering insert mode has landed), so that
        // this test covers Eclipse's typing plus the replay into Neovim at Esc.
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, '!'); // typed by Eclipse, not Neovim
        assertFalse(session.isNeovimDrivenInsert());
        expectText("alpha!\nbeta\ngamma\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        type(widget, "j.");
        expectText("alpha!\nbeta!\ngamma\n");
    }

    /**
     * A key typed as soon as insert mode is reported, before the editor has received Neovim's
     * buffer sync for it. Eclipse used to type it, and the sync then overwrote it.
     */
    @Test
    void textTypedRightAfterEnteringInsertModeIsKept() {
        type(widget, "gg");
        type(widget, 'A');
        waitUntil("insert mode", TIMEOUT_MS, session::isTypingMode);
        type(widget, "!?"); // no pause: Neovim takes over this insert
        expectText("alpha!?\nbeta\ngamma\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        type(widget, "j.");
        expectText("alpha!?\nbeta!?\ngamma\n");
    }

    /** Keys typed before Neovim even reports insert mode reach Neovim in order, so they are inserted too. */
    @Test
    void textTypedTogetherWithTheInsertKeyIsKept() {
        type(widget, "ggA!");
        expectText("alpha!\nbeta\ngamma\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
    }

    /** {@code gt} is mapped in Neovim to an IDE action: the whole chain from key to tab switch. */
    @Test
    void gtSwitchesToTheNextEditorTab() throws Exception {
        ITextEditor otherEditor = openOtherFile();
        IWorkbenchPage page = editor.getSite().getPage();

        type(widgetOf(otherEditor), "gt");
        waitUntil("the other tab to be active", TIMEOUT_MS, () -> page.getActiveEditor() != otherEditor);
        page.closeEditor(otherEditor, false);
    }

    /**
     * In a multi-page editor (here {@link TestMultiPageEditor}, like the POM
     * editor) the editor tab is the multi-page editor, not the text page Neovim is attached to.
     * {@code gt} on that page still moves to the next editor tab.
     */
    @Test
    void gtInAMultiPageEditorSwitchesToTheNextEditorTab() throws Exception {
        IFile file = createFile("pom.mpage", "<project/>\n");
        IEditorPart multiPage = IDE.openEditor(page(), file, true);
        assertTrue(multiPage instanceof TestMultiPageEditor, "opened in " + multiPage);
        ITextEditor sourcePage = EditorAttachManager.textEditorOf(multiPage);
        waitForAttach(sourcePage, "the Source page");

        type(widgetOf(sourcePage), "gt");

        // A gt that fails shows its error in the message line and stays on this tab, so the wait fails.
        waitUntil("another tab to be active", TIMEOUT_MS, () -> page().getActiveEditor() != multiPage,
                () -> "; the message line shows " + messageText(sourcePage));
        page().closeEditor(multiPage, false);
    }

    /**
     * Neovim's own jump into another open file ({@code 'A} here; {@code Ctrl-O} and {@code :b} work
     * the same way) is followed: Eclipse activates that file's editor and attaches to it, with the
     * caret where Neovim's cursor is. Of two editors on the file, the first one opened is chosen
     * ({@code core/EditorBuffers}).
     */
    @Test
    void neovimsJumpToAnotherFileActivatesTheFirstEditorOnIt() throws Exception {
        // File mark A on line 2 of test.txt, then the caret away from it: activating an editor alone
        // would put Neovim's cursor where the editor's caret is (line 3), following keeps Neovim's.
        type(widget, "jmAj");
        settle();
        IWorkbenchPage page = editor.getSite().getPage();
        // A second editor on the same file (Window > Editor > Clone): it shares test.txt's buffer.
        IEditorPart second = page.openEditor(editor.getEditorInput(), "org.eclipse.ui.DefaultTextEditor", true,
                IWorkbenchPage.MATCH_NONE);
        waitForAttach((ITextEditor) second, "the second editor on test.txt");
        ITextEditor otherEditor = openOtherFile();

        type(widgetOf(otherEditor), "'A");

        waitForAttach(editor, "the first editor on test.txt");
        assertEquals(editor, page.getActiveEditor());
        waitUntil("the caret on the mark's line", TIMEOUT_MS,
                () -> widget.getLineAtOffset(widget.getCaretOffset()) == 1);
        page.closeEditor(second, false);
        page.closeEditor(otherEditor, false);
    }

    /**
     * {@code Ctrl-^} (Ctrl+Shift+6 on a US keyboard) toggles between two files' editors, as it does
     * between two buffers in one Vim window. other.txt's editor is opened from test.txt's, so its
     * window's alternate file is test.txt; {@code Ctrl-^} there edits test.txt in that window,
     * which travels to test.txt's editor with its alternate, other.txt (docs/design/WINDOWS.md, E2),
     * and the next {@code Ctrl-^} comes back.
     */
    @Test
    void ctrlCaretTogglesBetweenTwoFilesEditors() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor other = openOtherFile();

        ctrlShift6(widgetOf(other));
        waitForAttach(editor, "test.txt, after Ctrl-^");
        assertEquals(editor, page.getActiveEditor());

        ctrlShift6(widget);
        waitForAttach(other, "other.txt, after a second Ctrl-^");
        assertEquals(other, page.getActiveEditor());
        page.closeEditor(other, false);
    }

    /** Ctrl+Shift+6 as GTK reports it: the key 6 and the control code 0x1E that Ctrl-^ is in ASCII. */
    private static void ctrlShift6(StyledText target) {
        Event event = new Event();
        event.keyCode = '6';
        event.character = (char) 0x1E;
        event.stateMask = SWT.CTRL | SWT.SHIFT;
        event.doit = true; // as for a real key press; a new Event defaults to false (key ignored)
        target.notifyListeners(SWT.KeyDown, event);
    }

    /**
     * {@code :e} of a file that already has an editor is Neovim's own {@code :edit}: the window
     * travels into that editor, as for {@code :b}, so {@code :e #} toggles between the two editors
     * as in one Vim window, and {@code Ctrl-O} goes back through the same jump history.
     */
    @Test
    void eOfAnOpenFileTravelsSoEHashTogglesAndCtrlOGoesBack() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor other = openOtherFile();
        page.activate(editor);
        waitForAttach(editor, "test.txt");

        runInBox(widget, "e other.txt");
        waitForAttach(other, "other.txt, after :e");
        runInBox(widgetOf(other), "e #");
        waitForAttach(editor, "test.txt, after :e #");
        runInBox(widget, "e #");
        waitForAttach(other, "other.txt, after a second :e #");
        assertEquals(other, page.getActiveEditor());

        session.inputAsync("<C-o>");
        waitForAttach(editor, "test.txt, after Ctrl-O");
        assertEquals(editor, page.getActiveEditor());
        page.closeEditor(other, false);
    }

    /**
     * As reported: {@code Ctrl-O} held down after a few {@code :e #} between two files went back and
     * forth between them for ever. Each travel left a stray jump in the window left behind (WINDOWS
     * module, put_buf), so a window came back with its place reset to the end of its history, and
     * {@code Ctrl-O} always found one more jump. Checked as Vim has it: while {@code Ctrl-O} is held
     * down, no window's jump history grows and no window's place in it moves forward (and the other
     * way for {@code Ctrl-I}).
     *
     * <p>Not "it stops after N presses": at the end of the editors' own histories {@code Ctrl-O} is
     * Eclipse's Back, whose history is the whole test Eclipse's, with earlier tests' editors in it.
     * For the same reason the editors that Back reopens are closed at the end.
     */
    @Test
    void ctrlOAndCtrlIHeldDownAfterEHashOnlyGoOneWay() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor other = openOtherFile();
        try {
            page.activate(editor);
            waitForAttach(editor, "test.txt");
            runInBox(widget, "e other.txt");
            waitForAttach(other, "other.txt, after :e");
            runInBox(widgetOf(other), "e #");
            waitForAttach(editor, "test.txt, after :e #");
            runInBox(widget, "e #");
            waitForAttach(other, "other.txt, after a second :e #");

            assertOnlyOneWay("<C-o>", -1);
            assertOnlyOneWay("<C-i>", +1);
        } finally {
            closeAllBut(page, editor);
        }
    }

    /**
     * As reported: after a few {@code :e #} between two files, {@code Ctrl-O} went back about nine
     * times and {@code Ctrl-I} forward about three. Neovim keeps two jumps (it drops repeats of a
     * place); Eclipse's Back then walked through every editor switch the plug-in had made for the
     * {@code :e} commands, and {@code Ctrl-I}'s first jump into the other file deleted Eclipse's way
     * forward (NavigationHistorySteps). Now {@code Ctrl-O} is one Neovim jump, then Back past the
     * plug-in's switches (and past the tab click, where the jump already is) to where other.txt was
     * opened; {@code Ctrl-I} comes back through the same places.
     */
    @Test
    void ctrlIRetracesCtrlOAfterEHash() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor other = openOtherFile();
        INavigationLocation otherOpened = page.getNavigationHistory().getCurrentLocation();
        try {
            page.activate(editor);
            waitForAttach(editor, "test.txt");
            runInBox(widget, "e other.txt");
            waitForAttach(other, "other.txt, after :e");
            runInBox(widgetOf(other), "e #");
            waitForAttach(editor, "test.txt, after :e #");
            runInBox(widget, "e #");
            waitForAttach(other, "other.txt, after a second :e #");

            List<String> back = pressAndRecord("<C-o>", 2, this::activeEditor);
            assertEquals(List.of("other.txt", "test.txt", "other.txt"), back);
            assertSame(otherOpened, page.getNavigationHistory().getCurrentLocation(),
                    "Back went to where other.txt was opened, past the plug-in's switches");
            assertEquals(reversed(back), pressAndRecord("<C-i>", 2, this::activeEditor));
        } finally {
            closeAllBut(page, editor);
        }
    }

    /**
     * {@code Ctrl-I} comes back to where a Back started even when Eclipse's history lost that
     * place: Back lands in test.txt, whose window has older jumps into other.txt; {@code Ctrl-O}
     * goes on through them, and the plug-in's switch into other.txt deletes the entry of the tab
     * click into third.txt ahead of it. {@code Ctrl-I} shows third.txt again.
     */
    @Test
    void ctrlIComesBackToAPlaceEclipsesHistoryLost() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor other = openOtherFile();
        ITextEditor third = openFile("third.txt", "third\n");
        try {
            page.activate(editor);
            waitForAttach(editor, "test.txt");
            runInBox(widget, "e other.txt");
            waitForAttach(other, "other.txt, after :e");
            runInBox(widgetOf(other), "e #");
            waitForAttach(editor, "test.txt, after :e #");
            page.activate(third); // a tab click
            waitForAttach(third, "third.txt");

            List<String> back = pressAndRecord("<C-o>", 3, this::activeEditor);
            assertEquals(List.of("third.txt", "test.txt", "other.txt", "test.txt"), back,
                    "Back, a Neovim jump in test.txt's window, Back");
            assertEquals(reversed(back), pressAndRecord("<C-i>", 3, this::activeEditor));
        } finally {
            closeAllBut(page, editor);
        }
    }

    /**
     * The other side of {@link #ctrlOStepsOverAPlaceWhoseFileIsGone}: an editor still open on a new
     * file not written yet ({@code :e new.txt}, nothing on disk) is a place {@code Ctrl-O} goes back
     * to, and it stays open, as Vim keeps a {@code [New]} buffer.
     */
    @Test
    void ctrlOBackToANewFileNotWrittenYetKeepsItsEditor() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        try {
            runInBox(widget, "e new.txt");
            waitUntil("new.txt's editor", TIMEOUT_MS, () -> page.getActiveEditor() != null
                    && "new.txt".equals(page.getActiveEditor().getTitle()));
            ITextEditor created = (ITextEditor) page.getActiveEditor();
            waitForAttach(created, "new.txt");
            page.activate(editor); // a tab click: Eclipse's switch, no Neovim jump
            waitForAttach(editor, "test.txt");

            session.inputAsync("<C-o>");

            waitForAttach(created, "new.txt again, after Ctrl-O");
            assertEquals(created, page.findEditor(created.getEditorInput()), "still open");
        } finally {
            closeAllBut(page, editor);
        }
    }

    /**
     * {@code Ctrl-O} back to a file deleted since (its project deleted): Eclipse's history keeps the
     * place, and its Back opens an error page in a new tab for it ("Unable to create part").
     * {@code Ctrl-O} closes that page and goes on (where depends on the history older tests left; in
     * a fresh Eclipse it stays in test.txt).
     * Eclipse's own Back ({@code Alt+Left}) still shows the page: Eclipse's behavior. Found
     * 2026-10-31 by a macOS run, where a deleted test project left such a place for a later
     * {@code Ctrl-O}.
     */
    @Test
    void ctrlOStepsOverAPlaceWhoseFileIsGone() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        IProject gone = ResourcesPlugin.getWorkspace().getRoot().getProject("e2e-gone");
        List<String> logged = new CopyOnWriteArrayList<>();
        ILogListener log = (status, plugin) -> logged.add(status.getMessage()
                + (status.getException() == null ? "" : " " + status.getException().getMessage()));
        try {
            gone.create(null);
            gone.open(null);
            IFile file = gone.getFile("g.txt");
            file.create(new ByteArrayInputStream("gone\n".getBytes(StandardCharsets.UTF_8)), true, null);
            ITextEditor g = (ITextEditor) IDE.openEditor(page, file, TEXT_EDITOR, true);
            waitForAttach(g, "g.txt");
            page.activate(editor);
            waitForAttach(editor, "test.txt");
            gone.delete(true, true, null); // g.txt's editor closes with its file
            waitUntil("g.txt's editor closed", TIMEOUT_MS, () -> page.findEditor(g.getEditorInput()) == null);

            Platform.addLogListener(log);
            session.inputAsync("<C-o>");

            // Eclipse's Back went to the place: it logs that it could not open the file there...
            waitUntil("Back to g.txt's place", TIMEOUT_MS, () -> logged.stream().anyMatch(m -> m.contains("e2e-gone/g.txt")));
            settle();
            // ...and the error page it opened for it is closed again.
            assertEquals(List.of(), Arrays.stream(page.getEditorReferences()).map(IEditorReference::getTitle)
                    .filter("g.txt"::equals).toList(), "no tab for the deleted file");
        } finally {
            Platform.removeLogListener(log);
            closeAllBut(page, editor);
            if (gone.exists()) {
                gone.delete(true, true, null);
            }
        }
    }

    /**
     * The place a Back started from is gone for good when its editor was closed: {@code Ctrl-I}
     * stops there, and does not reopen it. As {@link #ctrlIComesBackToAPlaceEclipsesHistoryLost},
     * with third.txt closed before the {@code Ctrl-I}.
     */
    @Test
    void ctrlIDoesNotReopenTheEditorABackLeftWhenItWasClosed() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor other = openOtherFile();
        ITextEditor third = openFile("third.txt", "third\n");
        try {
            page.activate(editor);
            waitForAttach(editor, "test.txt");
            runInBox(widget, "e other.txt");
            waitForAttach(other, "other.txt, after :e");
            runInBox(widgetOf(other), "e #");
            waitForAttach(editor, "test.txt, after :e #");
            page.activate(third);
            waitForAttach(third, "third.txt");
            pressAndRecord("<C-o>", 3, this::activeEditor);
            page.closeEditor(third, false);
            settle();

            assertEquals(List.of("test.txt", "other.txt", "test.txt", "test.txt"),
                    pressAndRecord("<C-i>", 3, this::activeEditor), "the last Ctrl-I has nowhere to go");
            assertTrue(java.util.Arrays.stream(page.getEditorReferences())
                    .noneMatch(ref -> "third.txt".equals(ref.getName())), "third.txt stays closed");
        } finally {
            closeAllBut(page, editor);
        }
    }

    /**
     * {@code Ctrl-I} undoes the newest {@code Ctrl-O} step first, even where a window has an older
     * one waiting (the trail in LuaScripts.SETUP). Here other.txt's editor holds the window that
     * made the first step, a Neovim jump; a later Back comes to that editor again, and
     * {@code Ctrl-I} there must undo the Back after it, not redo the jump. The caret's line tells
     * the two visits of test.txt apart: line 2 after the tab clicks, line 1 where it was opened.
     */
    @Test
    void ctrlIUndoesTheNewestCtrlOStepFirst() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor other = openOtherFile();
        try {
            page.activate(editor);
            waitForAttach(editor, "test.txt");
            page.activate(other);
            waitForAttach(other, "other.txt, a tab click");
            page.activate(editor);
            waitForAttach(editor, "test.txt, a tab click");
            type(widget, "j");
            settle();
            runInBox(widget, "e other.txt");
            waitForAttach(other, "other.txt, after :e");
            runInBox(widgetOf(other), "e #");
            waitForAttach(editor, "test.txt, after :e #");

            List<String> back = pressAndRecord("<C-o>", 4, this::activeEditorAndLine);
            assertEquals(List.of("test.txt:2", "other.txt:1", "test.txt:2", "other.txt:1", "test.txt:1"), back,
                    "a Neovim jump, then Back to the tab clicks and to where test.txt was activated");
            assertEquals(reversed(back), pressAndRecord("<C-i>", 4, this::activeEditorAndLine));
        } finally {
            closeAllBut(page, editor);
        }
    }

    /** {@code state} before and after each of {@code times} presses of {@code key}. */
    private List<String> pressAndRecord(String key, int times, java.util.function.Supplier<String> state) {
        List<String> seen = new ArrayList<>();
        seen.add(state.get());
        for (int i = 0; i < times; i++) {
            session.inputAsync(key);
            settle();
            seen.add(state.get());
        }
        return seen;
    }

    /** The active editor's name: {@code "test.txt"}. */
    private String activeEditor() {
        return editor.getSite().getPage().getActiveEditor().getTitle();
    }

    /** The active editor's name and Neovim's cursor line: {@code "test.txt:2"}. */
    private String activeEditorAndLine() {
        return activeEditor() + ":" + cursorLine();
    }

    private static List<String> reversed(List<String> list) {
        List<String> copy = new ArrayList<>(list);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /** Closes every editor but {@code keep}: Back may have reopened earlier tests' editors. */
    private static void closeAllBut(IWorkbenchPage page, ITextEditor keep) {
        for (IEditorReference ref : page.getEditorReferences()) {
            if (ref.getEditor(false) != keep) {
                page.closeEditors(new IEditorReference[] { ref }, false);
            }
        }
    }

    /**
     * Presses {@code key} 8 times (enough for the old loop to come round four times) and checks
     * each window's jump history after each press: its length never grows, and its place moves only
     * in {@code direction} (-1 back, +1 forward) or stays. The windows' histories are read from
     * Neovim: {@code {winid, length, place}} for every editor window.
     */
    private void assertOnlyOneWay(String key, int direction) throws Exception {
        java.util.Map<Long, long[]> before = jumpHistories();
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            session.inputAsync(key);
            settle();
            java.util.Map<Long, long[]> now = jumpHistories();
            seen.add(String.valueOf(now.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()[0] + ":" + e.getValue()[1]).toList()));
            for (var e : now.entrySet()) {
                long[] was = before.get(e.getKey());
                if (was == null) {
                    continue; // a window opened meanwhile (Back reopened an older editor)
                }
                long[] is = e.getValue();
                assertTrue(is[0] <= was[0], key + ": window " + e.getKey() + "'s history grew: " + seen);
                assertTrue(Long.signum(is[1] - was[1]) * direction >= 0,
                        key + ": window " + e.getKey() + " moved the wrong way in its history: " + seen);
            }
            before = now;
        }
    }

    /** Each window's jump history: winid to {length, place}. */
    private java.util.Map<Long, long[]> jumpHistories() {
        Object answer = io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe.lua(session, """
                local out = {}
                for _, win in ipairs(vim.api.nvim_list_wins()) do
                  local list, place = unpack(vim.api.nvim_win_call(win, vim.fn.getjumplist))
                  out[#out + 1] = { win, #list, place }
                end
                return out""");
        java.util.Map<Long, long[]> histories = new java.util.HashMap<>();
        for (Object row : (List<?>) answer) {
            List<?> r = (List<?>) row;
            histories.put(((Number) r.get(0)).longValue(),
                    new long[] { ((Number) r.get(1)).longValue(), ((Number) r.get(2)).longValue() });
        }
        return histories;
    }

    /**
     * {@code Ctrl-O} across files goes back through one jump history, as in Vim with one window:
     * test.txt's Neovim window jumps into other.txt and travels to other.txt's editor (the two swap
     * windows, docs/design/WINDOWS.md, E2), so its history comes along. Here: {@code G} in test.txt
     * (a jump from line 1), {@code :b other.txt}, then {@code Ctrl-O} twice: back to test.txt line
     * 3, then to line 1, both in test.txt's editor.
     */
    @Test
    void ctrlOAcrossFilesGoesBackThroughTheWholeJumpHistory() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor other = openOtherFile();
        page.activate(editor);
        waitForAttach(editor, "test.txt");
        type(widget, "G");
        settle();
        runInBox(widget, "b other.txt");
        waitForAttach(other, "other.txt, after :b");

        session.inputAsync("<C-o>");
        waitForAttach(editor, "test.txt, after Ctrl-O");
        waitUntil("the caret on test.txt's last line", TIMEOUT_MS,
                () -> widget.getLineAtOffset(widget.getCaretOffset()) == 2);
        session.inputAsync("<C-o>");
        waitUntil("Neovim on test.txt's first line", TIMEOUT_MS, () -> cursorLine() == 1);
        assertEquals(editor, page.getActiveEditor(), "the second jump is inside test.txt");
        page.closeEditor(other, false);
    }

    /**
     * A buffer no editor shows (here a new empty one, made by Lua; also help or {@code :e} of a
     * file that is not open; {@code :enew} opens an untitled editor instead) is not followed: Neovim
     * is sent back to the attached document, so the next key still edits it. (The send-back is {@code core/DocumentBuffers.onEntered}; the
     * one in {@code EditorAttachManager} is for a document's buffer whose editor cannot show it,
     * such as a multi-page editor on a form page, which no test here sets up.)
     */
    @Test
    void neovimIsSentBackFromABufferNoEditorShows() throws Exception {
        type(widget, "G"); // line 3, "gamma"; in a new empty buffer the cursor would be on line 1
        settle();
        assertEquals(3, cursorLine());

        session.inputAsync(":lua vim.api.nvim_set_current_buf(vim.api.nvim_create_buf(true, false))<CR>");
        settle();
        waitUntil("Neovim back in test.txt", TIMEOUT_MS, () -> cursorLine() == 3);

        type(widget, "ggx");
        expectText("lpha\nbeta\ngamma\n");
    }

    /**
     * {@code :bufdo} (also {@code :argdo}, {@code :cdo}) enters every buffer in turn. Eclipse follows
     * only the last one, where Vim ends: following each would activate (and attach to) every
     * editor on the way.
     */
    @Test
    void aCommandThatWalksThroughBuffersIsFollowedOnlyToTheLastOne() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor second = openFile("second.txt", "second\n");
        ITextEditor third = openFile("third.txt", "third\n"); // opened last: the highest buffer number
        page.activate(editor);
        waitForAttach(editor, "test.txt");
        List<IWorkbenchPart> activated = new ArrayList<>();
        IPartListener2 listener = new IPartListener2() {
            @Override
            public void partActivated(IWorkbenchPartReference partRef) {
                activated.add(partRef.getPart(false));
            }
        };
        page.addPartListener(listener);
        try {
            session.inputAsync(":bufdo let b:visited = 1<CR>");
            // Hold the UI thread until every BufEnter of :bufdo has been read, so that Eclipse handles
            // them all at once: the case the check is for. The question is queued after the keys and
            // Neovim answers it only once they are done, with the BufEnter notifications before the
            // answer (as NeovimBarrier in tests/unit). This used to be a 500 ms sleep, which on a
            // loaded machine could end before the last BufEnter, and then tested an easier case.
            java.util.concurrent.CompletableFuture<String> answered = new java.util.concurrent.CompletableFuture<>();
            session.evalAsync("1", answered::complete);
            answered.get(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            waitForAttach(third, "third.txt, where :bufdo ends");
            assertEquals(List.of(third), activated);
        } finally {
            page.removePartListener(listener);
            page.closeEditor(second, false);
            page.closeEditor(third, false);
        }
    }

    /**
     * {@code :bufdo} changes files whose editors are in the background; each change reaches its
     * document (sync/UnattachedDocumentSync), which is then dirty, ready for Save All.
     */
    @Test
    void bufdoChangesEveryOpenFile() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor second = openFile("second.txt", "banana\n");
        ITextEditor third = openFile("third.txt", "papaya\n");
        page.activate(editor);
        waitForAttach(editor, "test.txt");
        try {
            // As the : box runs it (CommandRunner): typed, and waited for until Neovim is idle again.
            session.commandAsync("bufdo %s/a/A/ge");

            waitForAttach(third, "third.txt, where :bufdo ends");
            expectText("AlphA\nbetA\ngAmmA\n");
            waitUntil("second.txt changed", TIMEOUT_MS, () -> "bAnAnA\n".equals(documentOf(second).get()),
                    () -> "; it reads " + documentOf(second).get().replace("\n", "\\n"));
            assertEquals("pApAyA\n", documentOf(third).get());
            assertTrue(second.isDirty(), "second.txt is changed, not saved");
        } finally {
            page.closeEditor(second, false);
            page.closeEditor(third, false);
        }
    }

    /**
     * The other way: Eclipse changes a file whose editor is in the background (as a rename
     * refactoring does), and the change reaches its buffer. Otherwise the {@code :bufdo} below would
     * edit Neovim's stale copy, one line, and its change would replace the wrong line.
     */
    @Test
    void eclipsesChangeToAFileInTheBackgroundReachesItsBuffer() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor second = openFile("second.txt", "banana\n");
        page.activate(editor);
        waitForAttach(editor, "test.txt");
        try {
            documentOf(second).replace(0, 0, "added\n");
            settle();

            session.commandAsync("bufdo %s/^/>/");

            waitUntil("second.txt changed", TIMEOUT_MS, () -> ">added\n>banana\n".equals(documentOf(second).get()),
                    () -> "; it reads " + documentOf(second).get().replace("\n", "\\n"));
        } finally {
            page.closeEditor(second, false);
        }
    }

    /**
     * {@code :bufdo ... | update} saves every changed file through Eclipse: the document buffers are
     * {@code buftype=acwrite}, and their write hook asks Eclipse to save (core/LuaScripts.DOCUMENT_BUFFER).
     */
    @Test
    void bufdoUpdateSavesEveryChangedFileThroughEclipse() throws Exception {
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor second = openFile("second.txt", "banana\n");
        ITextEditor unchanged = openFile("third.txt", "kiwi\n");
        page.activate(editor);
        waitForAttach(editor, "test.txt");
        try {
            session.commandAsync("bufdo %s/a/A/ge | update");

            waitUntil("second.txt saved", TIMEOUT_MS, () -> "bAnAnA\n".equals(fileText("second.txt")),
                    () -> "; it reads " + fileText("second.txt").replace("\n", "\\n"));
            waitUntil("test.txt saved", TIMEOUT_MS, () -> "AlphA\nbetA\ngAmmA\n".equals(fileText("test.txt")));
            assertFalse(second.isDirty());
            assertFalse(editor.isDirty());
            assertFalse(unchanged.isDirty());
        } finally {
            page.closeEditor(second, false);
            page.closeEditor(unchanged, false);
        }
    }

    // The system clipboard (Eclipse is Neovim's clipboard provider; see core/ClipboardRegisters).
    // No g:clipboard of the developer's can interfere: the UI tests read no personal Neovim config.
    // Clipboard sharing with a virtual machine's host can (CONTRIBUTING.md, "Tests on a virtual machine").

    @Test
    void plusPPastesTextCopiedInAnotherProgram() {
        setSystemClipboard("copied ");
        type(widget, "\"+P");
        expectText("copied alpha\nbeta\ngamma\n");
    }

    @Test
    void plusYyCopiesTheLineToTheSystemClipboard() {
        setSystemClipboard("before");
        type(widget, "j\"+yy");
        settle();
        waitUntil("the copy", TIMEOUT_MS, () -> ("beta" + System.lineSeparator()).equals(systemClipboard()));
    }

    private static void setSystemClipboard(String text) {
        Clipboard clipboard = new Clipboard(Display.getCurrent());
        try {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    private static String systemClipboard() {
        Clipboard clipboard = new Clipboard(Display.getCurrent());
        try {
            return (String) clipboard.getContents(TextTransfer.getInstance());
        } finally {
            clipboard.dispose();
        }
    }

    /**
     * A change Eclipse makes by itself in normal mode (here: what a formatter or refactoring would
     * do) must reach Neovim. Otherwise {@code Gdd} would delete Neovim's stale last line, "gamma",
     * and its line event would remove "beta" from the document instead.
     */
    @Test
    void eclipsesOwnChangesInNormalModeReachNeovim() throws Exception {
        document.replace(0, 0, "zeta\n");
        type(widget, "G");
        type(widget, "dd");
        expectText("zeta\nalpha\nbeta\n");
    }

    /**
     * After Eclipse's undo brings back a deleted line, Neovim's cursor is where Eclipse's caret is,
     * on that line. Otherwise inserting the line above Neovim's cursor would push the cursor one
     * line down, and {@code j dd} would delete the wrong line.
     */
    @Test
    void afterEclipsesUndoNeovimsCursorIsOnTheRestoredLine() {
        type(widget, "ggdd");
        expectText("beta\ngamma\n");
        ITextOperationTarget target = editor.getAdapter(ITextOperationTarget.class);
        target.doOperation(ITextOperationTarget.UNDO);
        expectText("alpha\nbeta\ngamma\n");
        settle(); // let the push reach Neovim
        type(widget, "jdd");
        expectText("alpha\ngamma\n");
    }

    /**
     * Linked editing, as in a local rename (Alt+Shift+R): both "foo" are linked, so an edit of one is
     * copied to the other. Neovim's {@code ciw} must arrive as a change of the name only (a change of
     * the whole line would end linked mode), and the copies must reach Neovim.
     */
    @Test
    void ciwInALinkedRenameChangesEveryOccurrence() throws Exception {
        linkTwoFoos();
        type(widget, "gg0");
        type(widget, "ciw");
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, "bar");
        escape(widget);
        expectText("bar = bar;\n");
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        type(widget, "$x"); // Neovim's next edit: it must see the renamed line
        expectText("bar = bar\n");
    }

    /** Normal-mode edits in linked mode: {@code x} on one name is copied to the other, in Neovim too. */
    @Test
    void xInALinkedRenameChangesEveryOccurrence() throws Exception {
        linkTwoFoos();
        type(widget, "gg0x");
        expectText("oo = oo;\n");
        settle(); // let the copy reach Neovim
        type(widget, "$x");
        expectText("oo = oo\n");
    }

    /**
     * With Eclipse's linked-mode UI on (as a Java rename or a template starts it), Esc is taken by
     * Eclipse to leave linked mode before the plug-in's key listener sees it. One Esc must still end
     * Neovim's insert mode too ({@code KeyInterceptor.afterKeyDown}), not leave the user typing.
     */
    @Test
    void oneEscLeavesBothLinkedModeAndInsertMode() throws Exception {
        LinkedModeModel model = linkTwoFoos();
        ITextViewer viewer = (ITextViewer) editor.getAdapter(ITextOperationTarget.class);
        LinkedModeUI ui = new LinkedModeUI(model, viewer);
        ui.enter();
        type(widget, "gg0");
        type(widget, "ciw");
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, "bar");

        escape(widget);

        expectText("bar = bar;\n");
        waitUntil("normal mode after one Esc", TIMEOUT_MS, () -> "n".equals(session.getMode()),
                () -> "; mode " + session.getMode());
        assertFalse(LinkedModeModel.hasInstalledModel(document), "linked mode ended");
        type(widget, "$x"); // Neovim's next edit: it must see the renamed line
        expectText("bar = bar\n");
    }

    /** Makes the document "foo = foo;" with both names linked, the way a rename does. */
    private LinkedModeModel linkTwoFoos() throws Exception {
        document.set("foo = foo;\n");
        settle(); // Eclipse's own change is pushed to Neovim
        LinkedPositionGroup group = new LinkedPositionGroup();
        group.addPosition(new LinkedPosition(document, 0, 3));
        group.addPosition(new LinkedPosition(document, 6, 3));
        LinkedModeModel model = new LinkedModeModel();
        model.addGroup(group);
        model.forceInstall();
        return model;
    }

    /** {@code o} after a line ending in a brace starts one level further in, like the Eclipse formatter. */
    @Test
    void oIndentsTheFirstLineOfABlock() throws Exception {
        IFile file = project.getFile("Brace.java");
        file.create(new ByteArrayInputStream("class A {\n\tvoid f() {\n\t}\n}\n".getBytes(StandardCharsets.UTF_8)),
                true, null);
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor braceEditor = (ITextEditor) IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor", true);
        StyledText braceWidget = (StyledText) braceEditor.getAdapter(Control.class);
        IDocument braceDocument = braceEditor.getDocumentProvider().getDocument(braceEditor.getEditorInput());
        waitUntil("Neovim to attach to Brace.java", TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && c.getEditor() == braceEditor;
        });
        settle();

        type(braceWidget, "j");
        type(braceWidget, 'o');
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(braceWidget, "x();");
        escape(braceWidget);
        waitUntil("the indented line", TIMEOUT_MS,
                () -> "class A {\n\tvoid f() {\n\t\tx();\n\t}\n}\n".equals(braceDocument.get()),
                () -> "; it reads " + braceDocument.get().replace("\n", "\\n").replace("\t", "\\t"));
        page.closeEditor(braceEditor, false);
    }

    /**
     * {@code o} in visual mode moves the cursor to the other end of the selection. Drawing the
     * selection moves the Eclipse caret, which must not be sent back to Neovim: that would put the
     * cursor back where it was. Here {@code o} then {@code j} shrinks the selection to "beta".
     */
    @Test
    void oInVisualLineModeMovesTheCursorToTheOtherEnd() {
        typeSlowly("ggVjoj");
        type(widget, 'd');
        expectText("alpha\ngamma\n");
    }

    /** As above, characterwise: after {@code o} the cursor is on "a" of "alpha", {@code l} moves it on. */
    @Test
    void oInVisualModeMovesTheCursorToTheOtherEnd() {
        typeSlowly("ggvjol");
        type(widget, 'd');
        expectText("aeta\ngamma\n");
    }

    /**
     * The Vim key contexts switch on the letters chosen for the mode (KeyContextRule): in normal mode
     * the Normal choices (Ctrl+F on, Ctrl+W Eclipse's by default), in insert mode the Insert choices
     * (Ctrl+W on, Ctrl+F Eclipse's), whether Eclipse types or Neovim drives the insert (block-visual
     * {@code I}).
     */
    @Test
    void theKeyContextsSwitchOnTheLettersChosenForTheMode() {
        IContextService contexts = PlatformUI.getWorkbench().getService(IContextService.class);
        Predicate<Character> on = letter -> contexts.getActiveContextIds().contains(VimCtrlKeys.contextId(letter));
        waitUntil("normal mode's letters", TIMEOUT_MS, () -> on.test('f') && !on.test('w'));

        type(widget, "gg");
        type(widget, 'A');
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        waitUntil("insert mode's letters while Eclipse types", TIMEOUT_MS, () -> on.test('w') && !on.test('f'));
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        waitUntil("normal mode's letters again", TIMEOUT_MS, () -> on.test('f') && !on.test('w'));

        session.inputAsync("<C-v>");
        waitUntil("visual block mode", TIMEOUT_MS, () -> "\u0016".equals(session.getMode()));
        typeSlowly("j");
        type(widget, 'I');
        waitUntil("an insert Neovim drives", TIMEOUT_MS, session::isNeovimDrivenInsert);
        waitUntil("insert mode's letters while Neovim drives the insert", TIMEOUT_MS,
                () -> on.test('w') && on.test('o') && !on.test('f'));
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
    }

    /**
     * A sync point (docs/design/INSERT_CTRL_KEYS.md, section 2): Ctrl+W while Eclipse types hands the
     * typed text to Neovim, whose own Ctrl-W deletes the word; Eclipse then types on, and {@code .}
     * repeats the whole insert, Ctrl-W included, as in Vim.
     */
    @Test
    void ctrlWWhileEclipseTypesDeletesTheWordAndDotRepeatsTheWholeInsert() {
        type(widget, "gg");
        type(widget, 'A');
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, " foo bar");
        expectText("alpha foo bar\nbeta\ngamma\n");

        ctrlKey(widget, 'w');
        expectText("alpha foo \nbeta\ngamma\n");
        waitUntil("Eclipse types again", TIMEOUT_MS, session::isEclipseTypingReady);
        assertFalse(session.isNeovimDrivenInsert(), "Eclipse, not Neovim, types the rest");
        type(widget, "baz");
        expectText("alpha foo baz\nbeta\ngamma\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());

        typeSlowly("j.");
        expectText("alpha foo baz\nbeta foo baz\ngamma\n");
    }

    /** Ctrl-U knows where the insert began, although Eclipse typed the text: it deletes only that. */
    @Test
    void ctrlUWhileEclipseTypesDeletesBackToWhereTheInsertBegan() {
        type(widget, "gg");
        type(widget, 'A');
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, " foo");
        expectText("alpha foo\nbeta\ngamma\n");
        ctrlKey(widget, 'u');
        expectText("alpha\nbeta\ngamma\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
    }

    /**
     * Ctrl-T and Ctrl-D while Eclipse types: each is a sync point, and Neovim's own Ctrl-T indents the line by one {@code 'shiftwidth'}, Ctrl-D takes one
     * back. Each step is checked before the next key (one level, two, one again), so a lost key
     * fails the test; the caret stays after the text, where the insert goes on. The indent is the
     * one Neovim makes from the editor's settings (tabs or spaces, {@code core/LuaScripts}' indent
     * script), so the test reads it from Neovim rather than assuming one.
     */
    @Test
    void ctrlTAndCtrlDWhileEclipseTypesIndentByOneShiftwidthEach() {
        String oneLevel = indent(1);
        String twoLevels = indent(2);
        type(widget, "gg");
        type(widget, 'A');
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        ctrlKey(widget, 't');
        expectIndentedAlpha(oneLevel);
        ctrlKey(widget, 't');
        expectIndentedAlpha(twoLevels);
        ctrlKey(widget, 'd');
        expectIndentedAlpha(oneLevel);
        type(widget, '!');
        expectText(oneLevel + "alpha!\nbeta\ngamma\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
    }

    /**
     * {@code levels} indent levels as Neovim writes them in this buffer: spaces with
     * {@code 'expandtab'}, else as many tabs as fit and spaces for the rest ({@code shiftwidth=4},
     * {@code tabstop=8}: two levels are one tab).
     */
    private String indent(int levels) {
        return (String) io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe.lua(session, """
                local width = vim.fn.shiftwidth() * LEVELS
                return vim.bo.expandtab and string.rep(' ', width)
                  or string.rep('\\t', math.floor(width / vim.bo.tabstop)) .. string.rep(' ', width % vim.bo.tabstop)"""
                .replace("LEVELS", Integer.toString(levels)));
    }

    /** Waits for the first line to be {@code indent} plus alpha, the caret after it, and Eclipse to type again. */
    private void expectIndentedAlpha(String indent) {
        expectText(indent + "alpha\nbeta\ngamma\n");
        int end = indent.length() + "alpha".length();
        waitUntil("the caret after alpha", TIMEOUT_MS, () -> widget.getCaretOffset() == end,
                () -> "; it is at " + widget.getCaretOffset());
        waitUntil("Eclipse types again", TIMEOUT_MS, session::isEclipseTypingReady);
    }

    /**
     * Ctrl-O runs one normal-mode command ({@code 0}: to the line's start) and the insert goes on
     * there; once Neovim is back in insert mode, Eclipse types again.
     */
    @Test
    void ctrlOWhileEclipseTypesRunsOneNormalCommandThenEclipseTypesOn() {
        type(widget, "gg");
        type(widget, 'A');
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, " foo");
        ctrlKey(widget, 'o');
        waitUntil("one normal-mode command", TIMEOUT_MS, () -> "niI".equals(session.getMode()));
        type(widget, '0');
        waitUntil("Eclipse types again", TIMEOUT_MS, session::isEclipseTypingReady);
        assertFalse(session.isNeovimDrivenInsert(), "Eclipse, not Neovim, types the rest");
        type(widget, "X");
        expectText("Xalpha foo\nbeta\ngamma\n");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
    }

    /**
     * Ctrl-N runs Eclipse's Word Completion, which offers the nearest matching word before the caret
     * first, and the next one when pressed again (Neovim's popup is not drawn). Ticked for the test:
     * on Windows and Linux Ctrl+N is Eclipse's in insert mode by default.
     */
    @Test
    void ctrlNWhileEclipseTypesRunsWordCompletion() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setValue(PreferenceConstants.ctrlKeyInInsert('n'), true);
        try {
            document.set("apple\napricot\n");
            settle();
            typeSlowly("Go");
            waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
            type(widget, "ap");
            ctrlKey(widget, 'n');
            expectText("apple\napricot\napricot\n");
            ctrlKey(widget, 'n');
            expectText("apple\napricot\napple\n");
            escape(widget);
            waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
            settle();
            expectText("apple\napricot\napple\n");
        } finally {
            store.setToDefault(PreferenceConstants.ctrlKeyInInsert('n'));
        }
    }

    /**
     * A normal-mode binding that had not been switched off yet when a key came in insert mode (the
     * key contexts follow the mode a moment late): the letter is Eclipse's in insert mode, so
     * Eclipse's command runs. Ctrl+D, given to Eclipse for insert mode, is Delete Line, not Vim's
     * unindent. The binding's delivery is simulated with the command's own entry point.
     */
    @Test
    void aNormalModeBindingStillOnInInsertModeRunsEclipsesCommand() {
        assumeFalse(PlatformUtil.isMac(), "Delete Line is Cmd+D on macOS: Ctrl+D runs nothing there");
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setValue(PreferenceConstants.ctrlKeyInInsert('d'), false);
        try {
            type(widget, "gg");
            type(widget, 'A');
            waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
            EditorAttachManager.getInstance().getActiveController().sendKey("<C-d>");
            expectText("beta\ngamma\n");
            assertTrue(session.isTypingMode(), "still in the insert");
        } finally {
            store.setToDefault(PreferenceConstants.ctrlKeyInInsert('d'));
        }
    }

    /**
     * Ctrl-X then Ctrl-O asks for Eclipse's Content Assist (Vim's omni completion). The text editor
     * has no proposals to show, so the test checks that the command is run, through the workbench's
     * command service. Ticked for the test: Ctrl+X is Eclipse's in insert mode by default.
     */
    @Test
    void ctrlXCtrlOWhileEclipseTypesRunsContentAssist() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setValue(PreferenceConstants.ctrlKeyInInsert('x'), true);
        ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
        List<String> run = new CopyOnWriteArrayList<>();
        IExecutionListener listener = new IExecutionListener() {
            @Override
            public void preExecute(String commandId, ExecutionEvent event) {
                run.add(commandId);
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception) {
                run.add(commandId); // asked for, though no handler took it
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception) {
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue) {
            }
        };
        commands.addExecutionListener(listener);
        try {
            type(widget, 'A');
            waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
            ctrlKey(widget, 'x');
            ctrlKey(widget, 'o');
            waitUntil("Content Assist", TIMEOUT_MS,
                    () -> run.contains(ITextEditorActionDefinitionIds.CONTENT_ASSIST_PROPOSALS), run::toString);
            assertTrue(session.isTypingMode(), "not Ctrl-O's one normal-mode command");
            escape(widget);
            waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        } finally {
            commands.removeExecutionListener(listener);
            store.setToDefault(PreferenceConstants.ctrlKeyInInsert('x'));
        }
    }

    /** Ctrl-C, when ticked for insert mode, ends the insert as Esc does: {@code .} repeats the typed text. */
    @Test
    void ctrlCEndsAnInsertAsEscDoes() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setValue(PreferenceConstants.ctrlKeyInInsert('c'), true);
        try {
            type(widget, "gg");
            type(widget, 'A');
            waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
            type(widget, " foo");
            ctrlKey(widget, 'c');
            waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
            typeSlowly("j.");
            expectText("alpha foo\nbeta foo\ngamma\n");
        } finally {
            store.setToDefault(PreferenceConstants.ctrlKeyInInsert('c'));
        }
    }

    /**
     * Ctrl-R waits for a register name: Neovim drives the insert until it has one, so the {@code "}
     * that follows reaches it, and the unnamed register's text is inserted. Then Eclipse types
     * again, and {@code .} repeats the whole insert, the register's text included.
     */
    @Test
    void ctrlRWhileEclipseTypesInsertsARegisterThenEclipseTypesAgain() {
        typeSlowly("ggyiw"); // "alpha" in the unnamed register
        type(widget, 'A');
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, " ");
        ctrlKey(widget, 'r');
        type(widget, '"');
        expectText("alpha alpha\nbeta\ngamma\n");
        waitUntil("Eclipse typing again", TIMEOUT_MS,
                () -> session.isEclipseTypingReady() && !session.isNeovimDrivenInsert());
        type(widget, "!");
        assertEquals("alpha alpha!\nbeta\ngamma\n", documentOf(editor).get(), "Eclipse typed it at once");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        typeSlowly("j.");
        expectText("alpha alpha!\nbeta alpha!\ngamma\n");
    }

    /**
     * Blockwise: {@code o} goes to the opposite corner, {@code O} to the other corner on the same
     * line. Ctrl-V is sent to Neovim directly (synthetic Ctrl keys miss the workbench bindings).
     */
    @Test
    void oAndCapitalOInVisualBlockModeMoveTheCursorToAnotherCorner() {
        type(widget, "gg");
        session.inputAsync("<C-v>");
        waitUntil("visual block mode", TIMEOUT_MS, () -> "\u0016".equals(session.getMode()));
        typeSlowly("jlo"); // cursor on "a" of "alpha", anchor on "e" of "beta"
        typeSlowly("O"); // cursor on "l", anchor on "b"
        typeSlowly("j"); // block: column 0-1 of "beta" only
        type(widget, 'd');
        expectText("alpha\nta\ngamma\n");
    }

    /**
     * {@code ZQ} is mapped in Neovim to Eclipse's close, which arrives after the key event is over.
     * Eclipse's own key listeners run after the plug-in's and use the editor; closed during the
     * event, the Java editor fails with a NullPointerException ("getViewer() is null").
     */
    @Test
    void zqClosesTheEditorAfterTheKeyEvent() {
        type(widget, "ZQ");
        assertFalse(widget.isDisposed(), "the editor must still exist while its key event runs");
        IWorkbenchPage page = editor.getSite().getPage();
        waitUntil("the editor to close", TIMEOUT_MS, () -> page.findEditor(editor.getEditorInput()) == null);
        editor = null; // closed already: nothing for closeEditor() to do
    }

    /** In a text file, which has no Open Declaration, {@code gd} is Neovim's own: to the word's first use. */
    @Test
    void gdInATextFileJumpsToTheFirstUseOfTheWord() {
        document.set("int x = 1;\ny = x;\n");
        settle(); // Eclipse's own change is pushed to Neovim
        type(widget, "G$h"); // on the x of "y = x;"
        type(widget, "gd");
        waitUntil("the caret on the first x", TIMEOUT_MS, () -> widget.getCaretOffset() == 4,
                () -> "; caret " + widget.getCaretOffset());
        type(widget, 'x');
        expectText("int  = 1;\ny = x;\n");
    }

    /**
     * Another plug-in may bind F3 for every text editor to a command the Text Editor has no handler
     * for (the test context in {@code fragment.xml} recreates that). {@code gd} is then Neovim's own,
     * not the error "gd: not available in this editor".
     */
    @Test
    void gdIsNeovimsOwnWhenTheF3CommandCannotRunHere() throws Exception {
        IContextService contexts = editor.getSite().getService(IContextService.class);
        IContextActivation activation = contexts.activateContext(
                "io.github.jm6cnddxbwftaks.neovim4eclipse.tests.context.unhandledF3");
        try {
            assertEquals("io.github.jm6cnddxbwftaks.neovim4eclipse.tests.commands.unhandledF3",
                    KeyBindings.commandIdFor("F3"), "F3 is bound, with no handler");
            document.set("int x = 1;\ny = x;\n");
            settle();
            type(widget, "G$h"); // on the x of "y = x;"
            type(widget, "gd");
            waitUntil("the caret on the first x", TIMEOUT_MS, () -> widget.getCaretOffset() == 4,
                    () -> "; caret " + widget.getCaretOffset());
        } finally {
            contexts.deactivateContext(activation);
        }
    }

    /**
     * The Generic Editor binds F3 to Open Hyperlink, how Language Server files go to a definition.
     * {@code gd} runs it only where a link is under the caret (here {@link TestHyperlinkDetector}'s
     * LINKME); on any other word it is Neovim's own, as in the plain Text Editor.
     */
    @Test
    void gdInTheGenericEditorOpensALinkOnlyWhereThereIsOne() throws Exception {
        IFile file = project.getFile("generic.txt");
        // Offsets: "word" at 2 and 14, LINKME at 7. None is 0, where the caret starts, so each wait
        // below can only be met by the step before it.
        file.create(new ByteArrayInputStream("a word LINKME\nword\n".getBytes(StandardCharsets.UTF_8)), true, null);
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor generic = (ITextEditor) IDE.openEditor(page, file, "org.eclipse.ui.genericeditor.GenericEditor", true);
        try {
            waitForAttach(generic, "generic.txt in the Generic Editor");
            assertEquals("org.eclipse.ui.edit.text.open.hyperlink", KeyBindings.commandIdFor("F3"));
            StyledText genericWidget = widgetOf(generic);
            int openedBefore = TestHyperlinkDetector.OPENED.get();
            // Open Hyperlink where there is none shows a modal dialog ("Select a hyperlink target"),
            // which would block the test; close any such dialog, and fail below.
            List<String> dialogs = closeModalDialogsWhile(genericWidget);

            type(genericWidget, "G"); // on the second "word": no link there
            waitUntil("the caret on the second word", TIMEOUT_MS, () -> genericWidget.getCaretOffset() == 14,
                    () -> "; caret " + genericWidget.getCaretOffset());
            type(genericWidget, "gd");
            waitUntil("Neovim's gd: the caret on the first word", TIMEOUT_MS, () -> genericWidget.getCaretOffset() == 2,
                    () -> "; caret " + genericWidget.getCaretOffset() + ", dialogs " + dialogs);
            assertEquals(openedBefore, TestHyperlinkDetector.OPENED.get(), "no link was opened");
            assertEquals(List.of(), dialogs, "no dialog");

            type(genericWidget, "w"); // on LINKME; Neovim's cursor followed its own gd
            waitUntil("the caret on LINKME", TIMEOUT_MS, () -> genericWidget.getCaretOffset() == 7,
                    () -> "; caret " + genericWidget.getCaretOffset());
            type(genericWidget, "gd");
            waitUntil("the LINKME link opened", TIMEOUT_MS, () -> TestHyperlinkDetector.OPENED.get() == openedBefore + 1);
            assertEquals(List.of(), dialogs, "no dialog");
        } finally {
            page.closeEditor(generic, false);
        }
    }

    @Test
    void undoRevertsNeovimsChange() {
        type(widget, "ggdd");
        expectText("beta\ngamma\n");
        type(widget, 'u');
        expectText("alpha\nbeta\ngamma\n");
        assertEquals("n", session.getMode());
    }

    // ---- Eclipse's own edits reach Neovim (the parts without Java tooling) ----

    /** File › Revert (Eclipse's command, not {@code :e!}): Neovim gets the saved text back. */
    @Test
    void fileRevertReachesNeovim() throws Exception {
        type(widget, "ggdd");
        expectText("beta\ngamma\n");

        runCommand("org.eclipse.ui.file.revert");
        expectText("alpha\nbeta\ngamma\n");
        assertFalse(editor.isDirty());
        settle(); // let the push reach Neovim

        type(widget, "Gdd");
        expectText("alpha\nbeta\n");
    }

    /**
     * Neovim mode off (the Neovim toolbar toggle), an edit in plain Eclipse, Neovim mode on again:
     * the edit reaches Neovim when it is switched back on ({@code DocumentPush}), so {@code G dd}
     * deletes the real last line and {@code u} brings it back.
     */
    @Test
    void anEditWhileNeovimModeIsOffReachesNeovimWhenItIsBackOn() throws Exception {
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        runCommand("io.github.jm6cnddxbwftaks.neovim4eclipse.commands.toggleMode");
        assertFalse(controller.isNeovimModeActive());
        document.replace(0, 0, "zeta\n");

        runCommand("io.github.jm6cnddxbwftaks.neovim4eclipse.commands.toggleMode");
        assertTrue(controller.isNeovimModeActive());
        settle();

        type(widget, "Gdd");
        expectText("zeta\nalpha\nbeta\n");
        type(widget, 'u');
        expectText("zeta\nalpha\nbeta\ngamma\n");
    }

    // ---- Keys ----

    /**
     * {@code Z} as the argument of {@code f} is Neovim's to read, even typed fast: {@code fZ} finds
     * the {@code Z} (Java no longer holds a {@code Z} back as the start of {@code ZZ}), and
     * {@code x} deletes it.
     */
    @Test
    void zAfterFIsItsArgumentNotAFileCommand() throws Exception {
        document.set("aZb\n");
        settle();
        type(widget, "0fZx");
        expectText("ab\n");
    }

    /**
     * A macro recorded at full speed records its insert: the {@code i} starts insert mode before
     * Eclipse could take over the typing, so Neovim types {@code hello} and records it (KeyRouter).
     */
    @Test
    void aFastRecordingRecordsTheInsert() {
        type(widget, "ggqaihello");
        escape(widget);
        type(widget, "qj0@a");
        expectText("helloalpha\nhellobeta\ngamma\n");
    }

    /**
     * A macro recorded at a person's speed records its insert too: the session sees that Neovim is
     * recording (reg_recording() in its state) before the {@code i}, and makes the insert
     * Neovim-driven, so Neovim types {@code hello} and records it.
     */
    @Test
    void aSlowRecordingRecordsTheInsert() {
        typeSlowly("ggqaihello");
        escape(widget);
        settle();
        typeSlowly("qj0@a");
        expectText("helloalpha\nhellobeta\ngamma\n");
    }

    /**
     * {@code o} on the last line of a file without a final newline shows one new line, not two, and
     * the file then ends with exactly one line break (see {@code LineTextUtil}).
     */
    @Test
    void oAtTheEndOfAFileWithoutAFinalNewlineAddsOneLine() {
        document.set("alpha\nbeta");
        settle();
        typeSlowly("Go");
        expectText("alpha\nbeta\n");
        escape(widget);
        settle();
        expectText("alpha\nbeta\n");
    }

    /**
     * The empty last line that {@code o} made in a file without a final newline stays a line after
     * Esc: {@code Go} goes to it and adds a second one, as in Vim, and Neovim has both.
     */
    @Test
    void anEmptyLastLineWithoutAFinalNewlineStaysALine() throws Exception {
        document.set("alpha\nbeta");
        settle();
        typeSlowly("Go");
        escape(widget);
        settle();
        typeSlowly("Go");
        escape(widget);
        settle();
        expectText("alpha\nbeta\n\n");
        assertEquals(List.of("alpha", "beta", "", ""), session.getBufferLines());
    }

    /**
     * Neovim's {@code 'modified'} follows the editor's dirty flag: a change in Eclipse sets it, a
     * save in Eclipse clears it, so {@code :update} writes what Eclipse would save.
     */
    @Test
    void neovimsModifiedFollowsTheEditorsDirtyFlag() throws Exception {
        document.replace(0, 0, "1");
        settle();
        waitUntil("'modified' set", TIMEOUT_MS,
                () -> Boolean.TRUE.equals(io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe.lua(session, "return vim.bo.modified")));
        editor.doSave(null);
        waitUntil("'modified' cleared", TIMEOUT_MS,
                () -> Boolean.FALSE.equals(io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe.lua(session, "return vim.bo.modified")));
    }

    /**
     * Eclipse's Undo key, so that Eclipse's key binding runs Undo: Ctrl+Z, or Cmd+Z on macOS. Undo is
     * bound to M1+Z, and M1 is Command on a Mac (there, Ctrl+Z runs nothing in Eclipse).
     *
     * <p>{@code UiTestSupport.m1Key} sends it to the widget, not as real key events through the
     * operating system ({@code Display.post}): on macOS those need the Accessibility permission, and
     * without it they are dropped.
     */
    private void postCtrlZ() {
        UiTestSupport.m1Key(widget, 'z');
        UiTestSupport.spin(500);
    }

    /**
     * Ctrl+Z after a Neovim edit makes the editor clean again every time, not only the first time:
     * each Neovim change starts its own step in Eclipse's undo history ({@code sync/UndoSteps}).
     * Real key events, so that Ctrl+Z goes through Eclipse's key bindings as when typed.
     */
    @Test
    void ctrlZBackToTheSavedTextMakesTheEditorCleanEveryTime() throws Exception {
        for (int round = 0; round < 3; round++) {
            editor.doSave(null);
            settle();
            typeSlowly("2Gyyp");
            settle();
            assertTrue(editor.isDirty(), "round " + round + ": yyp changed the file");
            postCtrlZ();
            settle();
            expectText("alpha\nbeta\ngamma\n");
            int r = round;
            waitUntil("round " + r + ": clean after Ctrl+Z", TIMEOUT_MS, () -> !editor.isDirty());
        }
    }

    /** {@code J} reaches Eclipse as two changes; one Ctrl+Z undoes both, back to the saved, clean text. */
    @Test
    void oneCtrlZUndoesAWholeJ() throws Exception {
        editor.doSave(null);
        settle();
        typeSlowly("ggJ");
        expectText("alpha beta\ngamma\n");
        postCtrlZ();
        settle();
        expectText("alpha\nbeta\ngamma\n");
        waitUntil("clean after one Ctrl+Z", TIMEOUT_MS, () -> !editor.isDirty());
    }

    /**
     * Neovim works in the editor's project ({@code :lcd}) and leaves it before Eclipse closes (or
     * deletes) the project: Windows refuses to delete a folder Neovim's process works in
     * ({@code ProjectFolderExit}). The teardown's delete checks the delete on Windows.
     */
    @Test
    void neovimLeavesTheProjectFolderBeforeEclipseClosesTheProject() throws Exception {
        java.nio.file.Path folder = project.getLocation().toPath().toRealPath();
        waitUntil("Neovim works in the project", TIMEOUT_MS, () -> folder.toString().equals(workingFolder()));
        project.close(null);
        assertEquals(folder.getParent().toString(), workingFolder());
    }

    /** Neovim's process folder, real path (macOS: /var is /private/var). */
    private String workingFolder() {
        try {
            return java.nio.file.Path.of(String.valueOf(
                    io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe.lua(session, "return vim.uv.cwd()")))
                    .toRealPath().toString();
        } catch (java.io.IOException e) {
            return e.toString();
        }
    }

    /** As {@link #fRAndTTakeColonAndSlashAsTheirArgument}, typed at full speed: Neovim reads every key. */
    @Test
    void fRAndTTypedFastTakeColonAndSlashAsTheirArgument() throws Exception {
        document.set("key: a/b/c\n");
        settle();
        type(widget, "0f:r/t/");
        assertNull(textChildOf(widget), "no : box");
        type(widget, 'x');
        expectText("key/ /b/c\n");
    }


    /** {@code f:} finds the colon (it does not open the : box), {@code r/} replaces it, {@code t/} stops before the next slash. */
    @Test
    void fRAndTTakeColonAndSlashAsTheirArgument() throws Exception {
        document.set("key: a/b/c\n");
        settle();
        typeSlowly("0f:r/t/");
        assertNull(textChildOf(widget), "no : box");
        type(widget, 'x');
        expectText("key/ /b/c\n");
    }

    /**
     * The {@code q} that stops a recording takes no register, so the {@code :} right after it opens
     * the box, and the recorded macro then plays back.
     *
     * <p>The keys are typed faster than Neovim reports its state: {@code jq} may arrive while the
     * session still says "not recording". That no longer matters: every key outside insert mode goes
     * to Neovim, which knows the {@code q} stops the recording and that the {@code :} after it opens
     * a command line (the box opens on {@code cmdline_show}). Before 0.1.7 {@code KeyRouter} guessed
     * both from the keys it routed, and this test caught the guess going wrong.
     */
    @Test
    void afterRecordingAMacroColonOpensTheBoxAndTheMacroPlaysBack() {
        type(widget, "ggqaA;");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        type(widget, "jq");
        settle();
        waitUntil("the recording to stop", TIMEOUT_MS, () -> !session.isRecording());

        type(widget, ':');
        waitUntil("the : box to open", TIMEOUT_MS, () -> textChildOf(widget) != null,
                () -> "; Neovim is in mode " + session.getMode() + " (c: the : went to Neovim)");
        Text box = textChildOf(widget);
        Event escape = new Event();
        escape.keyCode = SWT.ESC;
        escape.character = SWT.ESC;
        escape.doit = true;
        box.notifyListeners(SWT.KeyDown, escape);
        waitUntil("the : box to close", TIMEOUT_MS, () -> textChildOf(widget) == null);

        type(widget, "@a");
        expectText("alpha;\nbeta;\ngamma\n");
    }

    /** A click puts Neovim's cursor where the caret lands: {@code x} deletes the clicked character. */
    @Test
    void xDeletesTheCharacterThatWasClicked() {
        click(8); // the t of "beta"
        settle();
        type(widget, 'x');
        expectText("alpha\nbea\ngamma\n");
    }

    /**
     * Dragging is Neovim's own mouse ({@link NeovimMouse}): it selects in visual mode, from the
     * character pressed to the character released, and {@code x} deletes the selection.
     */
    @Test
    void xAfterDraggingOverWordsDeletesTheSelection() throws Exception {
        document.set("one two three\n");
        settle();
        drag(4, 8); // from the t of "two" to the t of "three": "two t"
        waitUntil("Neovim's visual selection", TIMEOUT_MS, () -> new Point(4, 9).equals(widget.getSelection()),
                () -> "; selection " + widget.getSelection() + ", mode " + session.getMode());
        type(widget, 'x');
        expectText("one hree\n");
    }

    /** A left click on the character at {@code offset}, as the mouse sends it. */
    private void click(int offset) {
        Point at = pointOf(offset);
        widget.notifyListeners(SWT.MouseDown, mouse(at, 0));
        widget.notifyListeners(SWT.MouseUp, mouse(at, SWT.BUTTON1));
    }

    /**
     * Presses the left button on {@code from}, moves to {@code to} in two steps, releases it. Two,
     * as a real drag sends many: on each move StyledText moves the caret (the plug-in sends it to
     * Neovim then) before it extends the selection, so only from the second move on does the caret
     * event see a selection, and send its start.
     */
    private void drag(int from, int to) {
        Point start = pointOf(from);
        Point end = pointOf(to);
        widget.notifyListeners(SWT.MouseDown, mouse(start, 0));
        widget.notifyListeners(SWT.MouseMove, mouse(pointOf(to - 1), SWT.BUTTON1));
        widget.notifyListeners(SWT.MouseMove, mouse(end, SWT.BUTTON1));
        widget.notifyListeners(SWT.MouseUp, mouse(end, SWT.BUTTON1));
    }

    /** Inside the left half of the character at {@code offset}, so the caret goes before it. */
    private Point pointOf(int offset) {
        Point location = widget.getLocationAtOffset(offset);
        return new Point(location.x + 1, location.y + widget.getLineHeight(offset) / 2);
    }

    private static Event mouse(Point at, int stateMask) {
        Event event = new Event();
        event.button = 1;
        event.count = 1;
        event.x = at.x;
        event.y = at.y;
        event.stateMask = stateMask;
        return event;
    }
}
