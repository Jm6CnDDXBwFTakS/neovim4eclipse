// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.EditorWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageKind;

/**
 * The messages page as a window of the editing Neovim ({@link WindowPane}): what one Neovim
 * behind both the code and the page gives, and the commands typed in the page
 * ({@link PageCommandsRun}). The page editor itself (its placement, {@code q}, the mouse, the
 * yank into the code's registers) is {@code NeovimPagesEndToEndTest}.
 */
class MessagesPageEndToEndTest extends EndToEndTestBase {

    @AfterEach
    void closeTheMessagesPages() {
        for (NeovimPageEditor page : messagesPages()) {
            page().closeEditor(page, false);
        }
        settle();
    }

    /** Every open messages editor (a split has two). */
    private static List<NeovimPageEditor> messagesPages() {
        List<NeovimPageEditor> found = new ArrayList<>();
        for (IEditorReference reference : page().getEditorReferences()) {
            if (reference.getEditor(false) instanceof NeovimPageEditor page && page.kind() == PageKind.MESSAGES) {
                found.add(page);
            }
        }
        return found;
    }

    /** Runs {@code line} from the code editor's {@code :} box, as typed. */
    private void colon(String line) {
        colonIn(widget, line);
    }

    /** Runs {@code line} from the {@code :} box of the code editor showing in {@code text}. */
    private static void colonIn(StyledText text, String line) {
        type(text, ":");
        enterInBox(text, line);
    }

    /** Types {@code :} in the page, then {@code line} in the {@code :} box that opens over it, then Enter. */
    private static void colonInPage(NeovimPageEditor page, String line) {
        UiTestSupport.requireKeyboardFocus(page.canvasForTests(), TIMEOUT_MS);
        key(page.canvasForTests(), ':');
        enterInBox(page.canvasForTests(), line);
    }

    private static void enterInBox(Composite host, String line) {
        waitUntil("the : box", TIMEOUT_MS, () -> textChildOf(host) != null);
        Text box = textChildOf(host);
        UiTestSupport.requireKeyboardFocus(box, TIMEOUT_MS);
        box.insert(line);
        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        enter.doit = true;
        box.notifyListeners(SWT.KeyDown, enter);
    }

    private static void key(Control control, char character) {
        Event event = new Event();
        event.keyCode = character;
        event.character = character;
        event.doit = true;
        control.notifyListeners(SWT.KeyDown, event);
    }

    /** Shows {@code marker} as the newest message, runs {@code :messages}, and waits for its page. */
    private NeovimPageEditor messagesShowing(String marker) {
        return messagesShowing(marker, widget);
    }

    /** {@link #messagesShowing(String)}, {@code :messages} typed in the code editor showing in {@code from}. */
    private NeovimPageEditor messagesShowing(String marker, StyledText from) {
        SessionProbe.lua(session, "vim.cmd.echomsg(\"'" + marker + "'\") return 0");
        colonIn(from, "messages");
        NeovimPageEditor[] found = new NeovimPageEditor[1];
        waitUntil("the messages page showing " + marker, TIMEOUT_MS, () -> {
            settle();
            for (NeovimPageEditor page : messagesPages()) {
                if (rowsOf(page).contains(marker)) {
                    found[0] = page;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    private static String rowsOf(NeovimPageEditor page) {
        GridSnapshot grid = page.canvasForTests().grid();
        if (grid == null) {
            return "";
        }
        StringBuilder rows = new StringBuilder();
        for (int r = 0; r < grid.height(); r++) {
            rows.append(grid.row(r).stripTrailing()).append('\n');
        }
        return rows.toString();
    }

    /** The messages page is a window of the editing Neovim: the code's session, no second Neovim. */
    @Test
    void theMessagesPageIsAWindowOfTheCodesNeovim() {
        NeovimPageEditor page = messagesShowing("one neovim");
        WindowPane pane = page.windowPane();
        assertTrue(pane != null, "a window of the editing Neovim, not a second Neovim's screen");
        assertNotEquals(EditorWindow.UNKNOWN, pane.window());
        waitUntil("Neovim in the page's window", TIMEOUT_MS,
                () -> ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_win()")).intValue() == pane.window());
        assertEquals("neovim4eclipse://messages",
                SessionProbe.lua(session, "return vim.api.nvim_buf_get_name(vim.api.nvim_get_current_buf())"));
    }

    /** {@code /} typed in the page opens the {@code :} box over the page, not over the code. */
    @Test
    void searchInThePageOpensTheBoxOverThePage() {
        NeovimPageEditor page = messagesShowing("search here");
        UiTestSupport.requireKeyboardFocus(page.canvasForTests(), TIMEOUT_MS);
        key(page.canvasForTests(), '/');
        waitUntil("the / box over the page", TIMEOUT_MS, () -> textChildOf(page.canvasForTests()) != null);
        assertNull(textChildOf(widget), "not over the code");
        Text box = textChildOf(page.canvasForTests());
        Event escape = new Event();
        escape.keyCode = SWT.ESC;
        escape.character = SWT.ESC;
        escape.doit = true;
        box.notifyListeners(SWT.KeyDown, escape);
    }

    /** {@code :split} in the page: a second messages editor, its own window on the same buffer. */
    @Test
    void splitInThePageMakesASecondViewOfTheSameText() {
        NeovimPageEditor first = messagesShowing("split me");
        colonInPage(first, "split");
        waitUntil("two messages editors, both showing the text", TIMEOUT_MS, () -> {
            settle();
            List<NeovimPageEditor> pages = messagesPages();
            return pages.size() == 2 && pages.stream().allMatch(p -> rowsOf(p).contains("split me"));
        });
        List<NeovimPageEditor> pages = messagesPages();
        assertNotEquals(pages.get(0).windowPane().window(), pages.get(1).windowPane().window(), "a window each");
        assertNotEquals(EditorSplits.stackOf(pages.get(0).getSite().getService(MPart.class)),
                EditorSplits.stackOf(pages.get(1).getSite().getService(MPart.class)),
                "in a tab group of its own");
    }

    /** {@code :q} in the page closes that page editor, and the code is still attached. */
    @Test
    void quitInThePageClosesItAndTheCodeStays() {
        NeovimPageEditor page = messagesShowing("quit me");
        colonInPage(page, "q");
        waitUntil("the page closed", TIMEOUT_MS, () -> {
            settle();
            return messagesPages().isEmpty();
        });
        assertTrue(editor.getSite().getPage().getEditorReferences().length > 0);
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        assertTrue(controller != null && controller.getEditor() == editor, "the code is still attached");
    }

    /** {@code :e other.txt} in the page opens the file in an editor; the page keeps its text. */
    @Test
    void editInThePageOpensTheFileInAnEditor() throws Exception {
        createFile("other.txt", "other\n");
        NeovimPageEditor page = messagesShowing("edit from here");
        colonInPage(page, "e " + project.getFile("other.txt").getLocation().toOSString());
        waitUntil("other.txt open in an editor", TIMEOUT_MS, () -> {
            settle();
            IEditorPart active = page().getActiveEditor();
            return active instanceof ITextEditor text && "other.txt".equals(text.getTitle());
        });
        assertTrue(rowsOf(page).contains("edit from here"), "the page keeps its text");
    }

    /** After the page, typing in the code works as before: the code took the focus back. */
    @Test
    void typingInTheCodeAfterThePageWorks() {
        messagesShowing("then the code");
        page().activate(editor);
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        waitUntil("Neovim back in the code's window", TIMEOUT_MS,
                () -> session.focusedPage() == EditorWindow.UNKNOWN);
        type(widget, "ityped after the page");
        type(widget, "\u001b");
        waitUntil("the text typed in the code", TIMEOUT_MS, () -> document.get().contains("typed after the page"));
    }

    /** The page closed while a view had the keyboard: back in the code, the code has the focus again. */
    @Test
    void thePageClosedFromAViewThenTheCodeHasTheFocus() throws Exception {
        NeovimPageEditor page = messagesShowing("then a view");
        page().showView(IPageLayout.ID_TASK_LIST);
        settle();
        page().closeEditor(page, false);
        settle();
        page().activate(editor);
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        waitUntil("the code with the focus again", TIMEOUT_MS,
                () -> EditorAttachManager.getInstance().getActiveController().hasFocus());
        colon("echo 'still the code'");
        waitUntil("the echo in the code's message line", TIMEOUT_MS, () -> {
            settle();
            return session.currentStatus().message().contains("still the code");
        });
    }

    /** The code editor the page came from closed while the page has the keyboard: the page keeps it, and q still closes it. */
    @Test
    void theCodeClosedUnderThePageLeavesThePageWithTheFocus() throws Exception {
        ITextEditor other = openOtherFile();
        NeovimPageEditor page = messagesShowing("code closes under me",
                (StyledText) other.getAdapter(Control.class));
        WindowPane pane = page.windowPane();
        waitUntil("Neovim in the page", TIMEOUT_MS, () -> currentWin() == pane.window());
        page().closeEditor(other, false);
        settle();
        waitUntil("Neovim back in the page after the close", TIMEOUT_MS, () -> {
            settle();
            return currentWin() == pane.window();
        });
        colonInPage(page, "q");
        waitUntil("the page closed by its q", TIMEOUT_MS, () -> {
            settle();
            return messagesPages().isEmpty();
        });
    }

    /** Neovim closing the page's window by itself (its buffer wiped) closes the page editor. */
    @Test
    void thePagesWindowClosedByNeovimClosesTheEditor() {
        messagesShowing("wipe me");
        SessionProbe.lua(session, "for _, b in ipairs(vim.api.nvim_list_bufs()) do"
                + " if vim.b[b].neovim4eclipse_page == 'messages' then vim.cmd('bwipeout! ' .. b) end end return 0");
        waitUntil("the messages editor closed", TIMEOUT_MS, () -> {
            settle();
            return messagesPages().isEmpty();
        });
    }

    /** Restart Neovim's step for pages: every messages editor closes, its window being the old Neovim's. */
    @Test
    void theEditingPagesCloseForARestart() {
        messagesShowing("restart closes me");
        NeovimPageEditor.closeEditingPages(org.eclipse.ui.PlatformUI.getWorkbench());
        settle();
        assertTrue(messagesPages().isEmpty());
    }

    /**
     * Neovim's command-line window opened from the page is drawn over the page. Opened without
     * mappings: {@code q} in the page closes it, as in Neovim's own message pager, so a typed
     * {@code q:} never gets there; {@code Ctrl-F} on the page's {@code :} line does.
     */
    @Test
    void theCommandLineWindowOpensOverThePage() {
        NeovimPageEditor page = messagesShowing("q colon here");
        UiTestSupport.requireKeyboardFocus(page.canvasForTests(), TIMEOUT_MS);
        waitUntil("Neovim in the page", TIMEOUT_MS, () -> currentWin() == page.windowPane().window());
        SessionProbe.lua(session, "vim.api.nvim_feedkeys('q:', 'n', false) return 0");
        waitUntil("the command-line window over the page", TIMEOUT_MS, () -> {
            settle();
            return gridCanvasChildOf(page.canvasForTests()) != null;
        });
        session.closeCommandWindowAsync();
        settle();
    }

    /** {@code :b} of the code's own buffer in the page: back to the code editor. */
    @Test
    void theCodesOwnBufferEnteredInThePageGoesBackToTheCode() {
        NeovimPageEditor page = messagesShowing("back to my code");
        colonInPage(page, "b " + session.getAttachedBufnr());
        waitUntil("the code editor active again", TIMEOUT_MS, () -> {
            settle();
            return page().getActiveEditor() == editor;
        });
    }

    private int currentWin() {
        return ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_win()")).intValue();
    }

    /** The first {@link GridCanvas} below {@code parent}: the command-line window's panel over a page. */
    private static GridCanvas gridCanvasChildOf(Composite parent) {
        if (parent.isDisposed()) {
            return null;
        }
        for (Control child : parent.getChildren()) {
            if (child instanceof GridCanvas grid && !grid.isDisposed() && grid.isVisible()) {
                return grid;
            }
            if (child instanceof Composite composite) {
                GridCanvas found = gridCanvasChildOf(composite);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
