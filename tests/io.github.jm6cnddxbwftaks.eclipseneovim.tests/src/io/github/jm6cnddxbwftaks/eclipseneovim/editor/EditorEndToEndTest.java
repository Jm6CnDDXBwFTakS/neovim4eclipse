// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.link.LinkedModeModel;
import org.eclipse.jface.text.link.LinkedPosition;
import org.eclipse.jface.text.link.LinkedPositionGroup;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.eclipseneovim.Activator;
import io.github.jm6cnddxbwftaks.eclipseneovim.core.NeovimSession;
import io.github.jm6cnddxbwftaks.eclipseneovim.prefs.PreferenceConstants;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimExecutable;

/**
 * Real editor, real Neovim: opens a file in Eclipse's text editor, waits for the plug-in to attach,
 * types keys into the editor widget and checks the document. Skipped when no {@code nvim} can be
 * found ({@code -Dnvim.path=...} selects one).
 *
 * <p>Keys are delivered with {@code notifyListeners(SWT.KeyDown)}, which runs the editor's own key
 * handling, the plug-in's {@code KeyInterceptor} included, but not the workbench's key-binding
 * dispatcher; Ctrl keys are therefore not covered here.
 */
class EditorEndToEndTest {

    private static final long TIMEOUT_MS = 15_000;

    private IProject project;
    private ITextEditor editor;
    private StyledText widget;
    private IDocument document;
    private NeovimSession session;

    @BeforeEach
    void openEditor() throws Exception {
        String nvimPath = System.getProperty("nvim.path", "");
        if (!nvimPath.isBlank()) {
            Activator.getDefault().getPreferenceStore().setValue(PreferenceConstants.NVIM_EXECUTABLE, nvimPath);
        }
        assumeTrue(NvimExecutable.looksAvailable(Activator.getDefault().getNvimExecutable()),
                "nvim not found; set -Dnvim.path");

        project = ResourcesPlugin.getWorkspace().getRoot().getProject("e2e");
        if (!project.exists()) {
            project.create(null);
        }
        project.open(null);
        IFile file = project.getFile("test.txt");
        byte[] content = "alpha\nbeta\ngamma\n".getBytes(StandardCharsets.UTF_8);
        if (file.exists()) {
            file.setContents(new ByteArrayInputStream(content), true, false, null);
        } else {
            file.create(new ByteArrayInputStream(content), true, null);
        }

        EditorAttachManager.getInstance().installWorkbenchListeners();
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        editor = (ITextEditor) IDE.openEditor(page, file, "org.eclipse.ui.DefaultTextEditor", true);
        document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        widget = (StyledText) editor.getAdapter(Control.class);

        waitUntil("Neovim to attach", TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && c.getEditor() == editor && !c.isClosed();
        });
        session = Activator.getDefault().getOrCreateSession();
        // One Neovim serves the whole IDE, so a previous test may have left it in another mode.
        session.inputAsync("<Esc>");
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        UiTestSupport.spin(300); // let the initial document push and cursor placement finish
    }

    @AfterEach
    void closeEditor() throws Exception {
        if (editor != null) {
            editor.getSite().getPage().closeEditor(editor, false);
        }
        if (project != null && project.exists()) {
            project.delete(true, true, null);
        }
    }

    private void expectText(String expected) {
        waitUntil("document to read " + expected.replace("\n", "\\n"), TIMEOUT_MS,
                () -> expected.equals(document.get()),
                () -> "; it reads " + document.get().replace("\n", "\\n") + " (mode " + session.getMode()
                        + ", caret " + widget.getCaretOffset() + ")");
    }

    @Test
    void ddDeletesTheFirstLine() {
        type(widget, "gg");
        type(widget, "dd");
        expectText("beta\ngamma\n");
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
        IFile other = project.getFile("other.txt");
        other.create(new ByteArrayInputStream("other\n".getBytes(StandardCharsets.UTF_8)), true, null);
        IWorkbenchPage page = editor.getSite().getPage();
        ITextEditor otherEditor = (ITextEditor) IDE.openEditor(page, other, "org.eclipse.ui.DefaultTextEditor", true);
        StyledText otherWidget = (StyledText) otherEditor.getAdapter(Control.class);
        waitUntil("Neovim to attach to other.txt", TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && c.getEditor() == otherEditor;
        });
        UiTestSupport.spin(300);

        type(otherWidget, "gt");
        waitUntil("the other tab to be active", TIMEOUT_MS, () -> page.getActiveEditor() != otherEditor);
        page.closeEditor(otherEditor, false);
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
        UiTestSupport.spin(300); // let the push reach Neovim
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
        UiTestSupport.spin(300); // let the copy reach Neovim
        type(widget, "$x");
        expectText("oo = oo\n");
    }

    /** Makes the document "foo = foo;" with both names linked, the way a rename does. */
    private void linkTwoFoos() throws Exception {
        document.set("foo = foo;\n");
        UiTestSupport.spin(300); // Eclipse's own change is pushed to Neovim
        LinkedPositionGroup group = new LinkedPositionGroup();
        group.addPosition(new LinkedPosition(document, 0, 3));
        group.addPosition(new LinkedPosition(document, 6, 3));
        LinkedModeModel model = new LinkedModeModel();
        model.addGroup(group);
        model.forceInstall();
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
        UiTestSupport.spin(300);

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

    /** Types one key at a time, letting each selection update reach the widget before the next key. */
    private void typeSlowly(String keys) {
        for (char key : keys.toCharArray()) {
            type(widget, key);
            UiTestSupport.spin(200);
        }
    }

    /**
     * {@code ZQ} closes the editor only after the key event is over. Eclipse's own key listeners
     * run after the plug-in's and use the editor; closed during the event, the Java editor fails
     * with a NullPointerException ("getViewer() is null").
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
        UiTestSupport.spin(300); // Eclipse's own change is pushed to Neovim
        type(widget, "G$h"); // on the x of "y = x;"
        type(widget, "gd");
        waitUntil("the caret on the first x", TIMEOUT_MS, () -> widget.getCaretOffset() == 4,
                () -> "; caret " + widget.getCaretOffset());
        type(widget, 'x');
        expectText("int  = 1;\ny = x;\n");
    }

    /** {@code gd} opens a link only where there is one; elsewhere it must not show Eclipse's dialog. */
    @Test
    void hyperlinksAreFoundOnlyWhereThereIsOne() {
        document.set("see https://example.com now\n");
        ITextViewer viewer = (ITextViewer) editor.getAdapter(ITextOperationTarget.class);
        assertTrue(HyperlinkProbe.isAt(editor, viewer, 8), "the URL is a link");
        assertFalse(HyperlinkProbe.isAt(editor, viewer, 1), "\"see\" is not");
    }

    @Test
    void undoRevertsNeovimsChange() {
        type(widget, "ggdd");
        expectText("beta\ngamma\n");
        type(widget, 'u');
        expectText("alpha\nbeta\ngamma\n");
        assertEquals("n", session.getMode());
    }
}
