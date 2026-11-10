// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.e4.ui.model.application.ui.advanced.MArea;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;

/**
 * Vim's splits as Eclipse editor stacks (docs/design/SPLITS.md): {@code :split} and {@code :vsplit}
 * put an editor into a new stack beside this one, {@code Ctrl-W} moves between them and sizes them,
 * {@code :only} gathers them into one stack again, and Eclipse's Toggle Split Editor makes the same
 * split, or Eclipse's two halves with the hidden preference. Which words Neovim sends for each
 * command is windows_spec.lua's; this is what Eclipse makes of them.
 */
class SplitsEndToEndTest extends EndToEndTestBase {

    @AfterEach
    void closeTheSplits() {
        Activator.getDefault().getPreferenceStore().setToDefault(PreferenceConstants.ECLIPSE_SPLITS);
        // Every editor but test.txt's own: their stacks go with them, as an emptied stack does.
        List<IEditorReference> others = new ArrayList<>();
        for (IEditorReference reference : page().getEditorReferences()) {
            if (reference.getEditor(false) != editor) {
                others.add(reference);
            }
        }
        page().closeEditors(others.toArray(IEditorReference[]::new), false);
        if (session != null) {
            ex("set nosplitbelow nosplitright"); // one Neovim serves every test
        }
    }

    /** Runs {@code :line} in Neovim, through its command line, as typed, and waits for the result. */
    private void ex(String line) {
        session.commandAsync(line);
        settle();
    }

    /** Sends keys to Neovim, in its notation ({@code "<C-w>l"}), as if typed in the attached editor. */
    private void keys(String keys) {
        session.inputAsync(keys);
        settle();
    }

    /** The editor stacks of the editor area that show something. */
    private static List<MPartStack> stacks(ITextEditor in) {
        MPart part = in.getSite().getService(MPart.class);
        MArea area = EditorSplits.areaOf(part);
        EModelService models = in.getSite().getService(EModelService.class);
        return models.findElements(area, null, MPartStack.class, null).stream()
                .filter(s -> s.isToBeRendered() && s.isVisible() && !s.getChildren().isEmpty()).toList();
    }

    /** Waits until Neovim is attached to a new text editor other than {@code before}, and returns it. */
    private ITextEditor waitForNewEditor(IEditorPart before, String what) {
        waitUntil(what, TIMEOUT_MS, () -> page().getActiveEditor() != before
                && page().getActiveEditor() instanceof ITextEditor);
        ITextEditor opened = (ITextEditor) page().getActiveEditor();
        waitForAttach(opened, what);
        settle();
        return opened;
    }

    private void waitForActive(ITextEditor wanted, String what) {
        waitUntil(what, TIMEOUT_MS, () -> page().getActiveEditor() == wanted);
        waitForAttach(wanted, what);
        settle();
    }

    /** The editor's text widget on screen, in display pixels. */
    private static Rectangle onScreen(ITextEditor target) {
        var text = widgetOf(target);
        Point at = text.toDisplay(0, 0);
        Rectangle size = text.getBounds();
        return new Rectangle(at.x, at.y, size.width, size.height);
    }

    /** Runs Eclipse's Toggle Split Editor, as Ctrl+_ ({@code oneAboveTheOther}) or Ctrl+{ does. */
    private static void toggleSplitEditor(boolean oneAboveTheOther) throws Exception {
        Command command = PlatformUI.getWorkbench().getService(ICommandService.class)
                .getCommand("org.eclipse.ui.window.splitEditor");
        ParameterizedCommand withSide = ParameterizedCommand.generateCommand(command,
                Map.of(SplitEditorHandler.HORIZONTAL_PARAMETER, String.valueOf(oneAboveTheOther)));
        page().getActivePart().getSite().getService(IHandlerService.class).executeCommand(withSide, null);
    }

    /**
     * {@code :vsplit}: a second editor on test.txt, in a new stack to the left (Vim's default side),
     * with the caret where it was; Neovim drives it, and an edit shows in both. {@code :q} in it
     * closes that editor, and its stack goes with it.
     */
    @Test
    void vsplitPutsACloneInANewStackToTheLeft() throws Exception {
        type(widget, "j");
        settle();
        ex("vsplit");
        ITextEditor clone = waitForNewEditor(editor, "the clone of :vsplit");
        assertNotSame(editor, clone);
        assertEquals(editor.getEditorInput(), clone.getEditorInput(), "the same file");
        assertEquals(2, stacks(editor).size(), "a stack of its own");
        assertTrue(onScreen(clone).x < onScreen(editor).x, "to the left: " + onScreen(clone) + " " + onScreen(editor));
        assertEquals(onScreen(clone).y, onScreen(editor).y, "side by side");
        waitUntil("the caret on line 2 in the clone too", TIMEOUT_MS, () -> cursorLine() == 2,
                () -> "; Neovim is on line " + cursorLine());

        type(widgetOf(clone), "x");
        expectText("alpha\neta\ngamma\n");

        ex("q");
        waitForActive(editor, "test.txt again");
        assertEquals(1, stacks(editor).size(), "the emptied stack went away");
    }

    /**
     * {@code :split other.txt} with {@code 'splitbelow'}: other.txt below; {@code Ctrl-W k} and
     * {@code Ctrl-W j} go between the two, and {@code Ctrl-W p} back to the one before.
     */
    @Test
    void ctrlWMovesBetweenTheSplitsByWhereTheyAre() throws Exception {
        createFile("other.txt", "other\n");
        ex("set splitbelow");
        ex("split other.txt");
        ITextEditor other = waitForNewEditor(editor, "other.txt below");
        assertEquals("other.txt", other.getEditorInput().getName());
        assertTrue(onScreen(other).y > onScreen(editor).y, "below: " + onScreen(other) + " " + onScreen(editor));

        keys("<C-w>k");
        waitForActive(editor, "Ctrl-W k: test.txt above");
        keys("<C-w>j");
        waitForActive(other, "Ctrl-W j: other.txt below");
        keys("<C-w>p");
        waitForActive(editor, "Ctrl-W p: the one before");
        keys("<C-w>w");
        waitForActive(other, "Ctrl-W w: the next one");
    }

    /** {@code :only} keeps every editor: the other stack's become tabs of this one. */
    @Test
    void onlyGathersTheEditorsIntoOneStackAndClosesNone() throws Exception {
        createFile("other.txt", "other\n");
        ex("vsplit other.txt");
        ITextEditor other = waitForNewEditor(editor, "other.txt to the left");
        assertEquals(2, stacks(editor).size());

        ex("only");
        waitUntil("one stack", TIMEOUT_MS, () -> stacks(editor).size() == 1);
        assertEquals(2, page().getEditorReferences().length, "both editors still open");
        assertSame(other, page().getActiveEditor(), "the editor :only was typed in stays in front");
        assertSame(stacks(editor).get(0), EditorSplits.stackOf(other.getSite().getService(MPart.class)));
    }

    /** {@code 10 Ctrl-W >}: ten characters wider, the other editor narrower; {@code Ctrl-W =} evens them. */
    @Test
    void ctrlWSizesTheSplits() throws Exception {
        ex("vsplit");
        ITextEditor clone = waitForNewEditor(editor, "the clone");
        int before = onScreen(clone).width;
        int otherBefore = onScreen(editor).width;
        keys("10<C-w>>");
        waitUntil("the clone to grow", TIMEOUT_MS, () -> onScreen(clone).width > before + 20,
                () -> "; it is " + onScreen(clone).width + " wide, was " + before);
        assertTrue(onScreen(editor).width < otherBefore, "the other gave the space");

        keys("<C-w>=");
        waitUntil("the two to be as wide", TIMEOUT_MS,
                () -> Math.abs(onScreen(clone).width - onScreen(editor).width) <= 2,
                () -> "; they are " + onScreen(clone).width + " and " + onScreen(editor).width);
    }

    /**
     * {@code Ctrl-W x}: the two editors change places, and the cursor stays where it was on screen,
     * so the editor that came there is the active one, as in Vim.
     */
    @Test
    void ctrlWXSwapsTheEditorsOfTwoStacks() throws Exception {
        createFile("other.txt", "other\n");
        ex("vsplit other.txt");
        ITextEditor other = waitForNewEditor(editor, "other.txt to the left");
        int left = onScreen(other).x;

        keys("<C-w>x");
        waitForActive(editor, "test.txt, now on the left");
        assertEquals(left, onScreen(editor).x, "test.txt where other.txt was");
        assertTrue(onScreen(other).x > left, "other.txt on the right");
    }

    /**
     * Eclipse's Toggle Split Editor in an editor Neovim drives: a Vim split (a new stack, not two
     * halves), one above the other for Ctrl+_; pressed again, {@code :only}.
     */
    @Test
    void toggleSplitEditorMakesAVimSplitAndThenOnly() throws Exception {
        toggleSplitEditor(true);
        ITextEditor clone = waitForNewEditor(editor, "the Vim split");
        assertEquals(2, stacks(editor).size(), "a new stack");
        assertEquals(onScreen(clone).x, onScreen(editor).x, "one above the other");
        assertTrue(onScreen(clone).y < onScreen(editor).y, "above, as :split has it by default");

        toggleSplitEditor(true);
        waitUntil("one stack again", TIMEOUT_MS, () -> stacks(editor).size() == 1);
        assertEquals(2, page().getEditorReferences().length, "the clone is a tab now; nothing closed");
    }

    /**
     * The hidden preference gives Toggle Split Editor back to Eclipse: two halves in one stack, one
     * above the other for Ctrl+_. {@code Ctrl-W j} and {@code k} still go between them; pressed
     * again, the command joins them.
     */
    @Test
    void withTheHiddenPreferenceToggleSplitEditorMakesEclipsesHalves() throws Exception {
        Activator.getDefault().getPreferenceStore().setValue(PreferenceConstants.ECLIPSE_SPLITS, true);
        toggleSplitEditor(true);
        waitUntil("Eclipse's second half", TIMEOUT_MS, () -> page().getEditorReferences().length == 2);
        assertEquals(1, stacks(editor).size(), "halves of one editor, in one stack");
        ITextEditor half = null;
        for (IEditorReference reference : page().getEditorReferences()) {
            if (reference.getEditor(false) != editor) {
                half = (ITextEditor) reference.getEditor(false);
            }
        }
        ITextEditor other = half;
        waitUntil("the halves to be drawn", TIMEOUT_MS, () -> onScreen(other).height > 0);
        assertEquals(onScreen(other).x, onScreen(editor).x, "one above the other");
        ITextEditor upper = onScreen(other).y < onScreen(editor).y ? other : editor;
        ITextEditor lower = upper == other ? editor : other;

        page().activate(upper);
        waitForActive(upper, "the upper half");
        keys("<C-w>j");
        waitForActive(lower, "Ctrl-W j: the lower half");
        keys("<C-w>k");
        waitForActive(upper, "Ctrl-W k: the upper half");

        page().activate(editor);
        waitForActive(editor, "test.txt's first editor");
        toggleSplitEditor(true);
        waitUntil("the halves joined", TIMEOUT_MS, () -> page().getEditorReferences().length == 1);
    }

    /**
     * {@code :q} in one of Eclipse's halves closes that half; the focus goes back to the half that
     * stays, so the next key reaches Neovim there.
     */
    @Test
    void quitInOneOfEclipsesHalvesLeavesTheOtherWithTheFocus() throws Exception {
        Activator.getDefault().getPreferenceStore().setValue(PreferenceConstants.ECLIPSE_SPLITS, true);
        toggleSplitEditor(false);
        waitUntil("Eclipse's second half", TIMEOUT_MS, () -> page().getEditorReferences().length == 2);
        ITextEditor half = null;
        for (IEditorReference reference : page().getEditorReferences()) {
            if (reference.getEditor(false) != editor) {
                half = (ITextEditor) reference.getEditor(false);
            }
        }
        ITextEditor other = half;
        page().activate(other);
        waitForActive(other, "the second half");

        ex("q");
        waitUntil("one editor again", TIMEOUT_MS, () -> page().getEditorReferences().length == 1);
        ITextEditor left = (ITextEditor) page().getEditorReferences()[0].getEditor(false);
        waitForActive(left, "the half that stays");
        waitUntil("the focus in it", TIMEOUT_MS, () -> widgetOf(left).isFocusControl(),
                () -> "; the focus is on " + UiTestSupport.focusReport(widgetOf(left)));
        type(widgetOf(left), "x");
        expectText(left, "lpha\nbeta\ngamma\n");
    }
}
