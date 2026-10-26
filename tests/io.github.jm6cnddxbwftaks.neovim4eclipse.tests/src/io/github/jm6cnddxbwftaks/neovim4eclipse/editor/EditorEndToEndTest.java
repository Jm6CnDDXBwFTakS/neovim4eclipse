// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
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
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Test;

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
        // A second editor on the same file (Window > New Editor): it shares test.txt's buffer.
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
     * A buffer no editor shows (here a new empty one, {@code :enew}; also help or {@code :e} of a
     * file that is not open) is not followed: Neovim is sent back to the attached document, so
     * the next key still edits it. (The send-back is {@code core/DocumentBuffers.onEntered}; the
     * one in {@code EditorAttachManager} is for a document's buffer whose editor cannot show it,
     * such as a multi-page editor on a form page, which no test here sets up.)
     */
    @Test
    void neovimIsSentBackFromABufferNoEditorShows() throws Exception {
        type(widget, "G"); // line 3, "gamma"; in :enew's empty buffer the cursor would be on line 1
        settle();
        assertEquals(3, cursorLine());

        session.inputAsync(":enew<CR>");
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
            // Hold the UI thread meanwhile, so that every BufEnter has been read before Eclipse handles
            // the first: the case the check is for. (Without the wait it is also the usual case.)
            Thread.sleep(500);
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
    // These assume the init.lua on the test machine does not set g:clipboard (its own provider wins).

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
