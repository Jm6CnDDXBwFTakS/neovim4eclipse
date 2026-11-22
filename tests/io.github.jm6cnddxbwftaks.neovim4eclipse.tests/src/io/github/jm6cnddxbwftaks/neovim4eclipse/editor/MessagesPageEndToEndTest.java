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
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.EditorWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageKind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ScreenInput;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimModes;

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

    /** Runs {@code line} on Neovim's command line from the code editor, as typed. */
    private void colon(String line) {
        colonIn(widget, line);
    }

    /** Runs {@code line} on Neovim's command line from the code editor showing in {@code text}. */
    private void colonIn(StyledText text, String line) {
        openCommandLine(text, line);
        pressEnter(text);
    }

    /**
     * Types {@code :} and {@code line} in the page, then Enter: every key goes to Neovim, which edits
     * the line itself; ui2 draws it over the page as over code.
     */
    private void colonInPage(NeovimPageEditor page, String line) {
        UiTestSupport.requireKeyboardFocus(page.canvasForTests(), TIMEOUT_MS);
        key(page.canvasForTests(), ':');
        expectCommandLine(":");
        for (char c : line.toCharArray()) {
            key(page.canvasForTests(), c);
        }
        expectCommandLine(":" + line);
        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        enter.doit = true;
        page.canvasForTests().notifyListeners(SWT.KeyDown, enter);
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

    /** {@code /} typed in the page goes to Neovim's search line, and the keys stay the page's. */
    @Test
    void searchInThePageGoesToNeovimsLine() {
        NeovimPageEditor page = messagesShowing("search here");
        UiTestSupport.requireKeyboardFocus(page.canvasForTests(), TIMEOUT_MS);
        key(page.canvasForTests(), '/');
        key(page.canvasForTests(), 'x');
        expectCommandLine("/x");
        assertTrue(page.canvasForTests().isFocusControl(), "the keys stay the page's");
        Event escape = new Event();
        escape.keyCode = SWT.ESC;
        escape.character = SWT.ESC;
        escape.doit = true;
        page.canvasForTests().notifyListeners(SWT.KeyDown, escape);
        expectCommandLine("");
    }

    /** Ctrl-W on the page's command line is Neovim's: it deletes a word, and the editor stays open. */
    @Test
    void ctrlWOnThePagesCommandLineDeletesAWord() {
        NeovimPageEditor page = messagesShowing("ctrl w here");
        UiTestSupport.requireKeyboardFocus(page.canvasForTests(), TIMEOUT_MS);
        for (char c : ":ab cd".toCharArray()) {
            key(page.canvasForTests(), c);
        }
        expectCommandLine(":ab cd");
        page.sendKey("<C-w>"); // what the plug-in's Ctrl+W binding does in a page editor
        expectCommandLine(":ab "); // the word deleted by Neovim, the blank before it kept, as in Vim
        assertTrue(page().isPartVisible(page), "still open: Ctrl+W was not Eclipse's Close");
        session.inputAsync("<Esc>");
    }

    /** The page draws its window's status line below its text, as Neovim does. */
    @Test
    void thePageDrawsItsStatusLine() {
        NeovimPageEditor page = messagesShowing("status below");
        waitUntil("the status line row drawn", TIMEOUT_MS, () -> {
            settle();
            GridSnapshot row = page.windowPane().statusRowForTests().grid();
            return row != null && row.row(0).contains("neovim4eclipse://messages");
        });
    }

    /**
     * The user's {@code 'cmdheight'} 0 takes the command line's row away under the page's status
     * line too, as under a code editor's: the status line lies on the page's bottom edge; 1 brings
     * the row back.
     */
    @Test
    void withCmdheight0ThePagesStatusLineLiesOnItsBottomEdge() {
        NeovimPageEditor page = messagesShowing("cmdheight 0");
        GridCanvas row = page.windowPane().statusRowForTests();
        waitUntil("the status row shown", TIMEOUT_MS, () -> {
            settle();
            return row.isVisible() && row.getSize().y > 0;
        });
        int line = row.getSize().y;
        try {
            SessionProbe.lua(session, "vim.o.cmdheight = 0 return 0");
            waitUntil("the status line on the bottom edge", TIMEOUT_MS, () -> {
                settle();
                return row.getBounds().y + row.getBounds().height == row.getParent().getClientArea().height;
            }, () -> "; row " + row.getBounds() + ", area " + row.getParent().getClientArea());
        } finally {
            SessionProbe.lua(session, "vim.o.cmdheight = 1 return 0");
        }
        waitUntil("the command line's row back under it", TIMEOUT_MS, () -> {
            settle();
            return row.getBounds().y + row.getBounds().height == row.getParent().getClientArea().height - line;
        }, () -> "; row " + row.getBounds() + ", area " + row.getParent().getClientArea());
    }

    /** One Neovim item in Eclipse's status bar for the messages editors, kept while any is open. */
    @Test
    void oneStatusBarItemServesBothHalvesOfASplit() {
        NeovimPageEditor first = messagesShowing("status bar item");
        IStatusLineManager bar = first.getEditorSite().getActionBars().getStatusLineManager();
        waitUntil("the pages' item in the status bar", TIMEOUT_MS, () -> bar.find(ModeStatusContribution.PAGE_ID) != null);
        colonInPage(first, "split");
        waitUntil("two messages editors", TIMEOUT_MS, () -> {
            settle();
            return messagesPages().size() == 2;
        });
        page().closeEditor(first, false);
        settle();
        assertTrue(bar.find(ModeStatusContribution.PAGE_ID) != null, "the other half keeps the item");
        for (NeovimPageEditor page : messagesPages()) {
            page().closeEditor(page, false);
        }
        settle();
        assertNull(bar.find(ModeStatusContribution.PAGE_ID), "the last one removed it");
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

    /**
     * A prompt with the cursor in the code editor hides its caret; the page takes the focus, which
     * closes the prompt (the code's {@code LineOnFocusLoss}), and back in the code the caret is
     * there again and the prompt is not drawn ({@code NeovimEditorController.takeFocusBack} draws
     * the floats as they are now, also when their last list went to the page).
     */
    @Test
    void theCodesCaretComesBackWhenAPromptClosedWhileThePageHadTheFocus() {
        NeovimPageEditor page = messagesShowing("prompt away");
        page().activate(editor);
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        try {
            SessionProbe.lua(session, FloatingWindowsEndToEndTest.OPEN_PROMPT);
            waitUntil("the code's caret hidden for the prompt", TIMEOUT_MS, () -> {
                settle();
                return widget.getCaret() == null;
            });
            page().activate(page);
            waitUntil("the prompt closed as the code was left", TIMEOUT_MS, () -> {
                settle();
                return Long.valueOf(-1).equals(SessionProbe.lua(session, "return vim.fn.bufwinid(vim.g.n4e_prompt)"))
                        && !"i".equals(session.getMode());
            }, () -> "; mode " + session.getMode() + " (the prompt's insert must end, not go on in the page)");
            page().activate(editor);
            waitUntil("the code's caret back, the closed prompt not drawn", TIMEOUT_MS, () -> {
                settle();
                return widget.getCaret() != null && !pluginFloatDrawn();
            });
        } finally {
            SessionProbe.lua(session, "vim.cmd.stopinsert()"
                    + " pcall(vim.api.nvim_win_close, vim.g.n4e_float or -1, true) vim.g.n4e_float = nil");
        }
    }

    /** Whether a plug-in's float (not one of ui2's windows) is drawn over the code's text. */
    private boolean pluginFloatDrawn() {
        for (Control child : widget.getChildren()) {
            if (child instanceof GridCanvas canvas && !canvas.isDisposed() && !StatusLineRow.isRow(canvas)
                    && !isNeovimsOwnFloat(canvas)) {
                return true;
            }
        }
        return false;
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
        expectShown("still the code");
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
            return !"".equals(SessionProbe.lua(session, "return vim.fn.getcmdwintype()"))
                    && gridCanvasChildOf(page.canvasForTests()) != null;
        });
        session.closeCommandWindowAsync();
        settle();
    }

    /**
     * The command-line window open over the page, then a click into the code: the window closes, as
     * over code, and the code has the focus and takes keys again.
     */
    @Test
    void theCommandLineWindowOverThePageClosesWhenTheCodeIsClicked() {
        NeovimPageEditor page = messagesShowing("q colon then code");
        UiTestSupport.requireKeyboardFocus(page.canvasForTests(), TIMEOUT_MS);
        waitUntil("Neovim in the page", TIMEOUT_MS, () -> currentWin() == page.windowPane().window());
        SessionProbe.lua(session, "vim.api.nvim_feedkeys('q:', 'n', false) return 0");
        waitUntil("the command-line window open", TIMEOUT_MS, () -> {
            settle();
            return !"".equals(SessionProbe.lua(session, "return vim.fn.getcmdwintype()"));
        });
        page().activate(editor);
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        waitUntil("the command-line window closed and the code with the focus", TIMEOUT_MS, () -> {
            settle();
            return "".equals(SessionProbe.lua(session, "return vim.fn.getcmdwintype()"))
                    && EditorAttachManager.getInstance().getActiveController().hasFocus();
        });
        type(widget, "iafter q colon");
        type(widget, "\u001b");
        waitUntil("the text typed in the code", TIMEOUT_MS, () -> document.get().contains("after q colon"));
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

    /** Whether the page's Neovim window is as many cells as its grid canvas: the screen follows the page. */
    private boolean pageWindowFitsItsCanvas(WindowPane pane) {
        GridCanvas canvas = pane.canvas();
        Point size = canvas.getSize();
        List<?> window = (List<?>) SessionProbe.lua(session, "local w = " + pane.window()
                + " return { vim.api.nvim_win_get_width(w), vim.api.nvim_win_get_height(w) }");
        // The canvas's rows less those drawn under the window: its status line, the command line's.
        int below = (pane.statusRowForTests().isVisible() ? 1 : 0) + StatusLineRow.COMMAND_LINE_ROWS;
        return ((Number) window.get(0)).intValue() == ScreenInput.cells(size.x, canvas.cellWidth())
                && ((Number) window.get(1)).intValue() == ScreenInput.cells(size.y, canvas.cellHeight()) - below;
    }

    private String pageSizes(WindowPane pane) {
        GridCanvas canvas = pane.canvas();
        return "; canvas " + ScreenInput.cells(canvas.getSize().x, canvas.cellWidth()) + "x"
                + ScreenInput.cells(canvas.getSize().y, canvas.cellHeight()) + ", window "
                + SessionProbe.lua(session, "local w = " + pane.window()
                        + " return vim.api.nvim_win_get_width(w) .. 'x' .. vim.api.nvim_win_get_height(w)");
    }

    /**
     * The status row coming and going changes the grid canvas's height, and Neovim's window follows:
     * its rows are always the canvas's rows ({@code :set laststatus=0}, then back).
     */
    @Test
    void thePagesWindowKeepsItsCanvasRowsAsTheStatusRowComesAndGoes() {
        NeovimPageEditor page = messagesShowing("rows follow");
        WindowPane pane = page.windowPane();
        UiTestSupport.requireKeyboardFocus(page.canvasForTests(), TIMEOUT_MS);
        waitUntil("the status row shown", TIMEOUT_MS, () -> {
            settle();
            return pane.statusRowForTests().isVisible() && pane.statusRowForTests().getSize().y > 0;
        });
        waitUntil("the window the canvas's size", TIMEOUT_MS, () -> {
            settle();
            return pageWindowFitsItsCanvas(pane);
        }, () -> pageSizes(pane));
        try {
            SessionProbe.lua(session, "vim.o.laststatus = 0 return 0");
            waitUntil("the status row gone", TIMEOUT_MS, () -> {
                settle();
                return !pane.statusRowForTests().isVisible(); // hidden: the canvas keeps the command line's row
            });
            waitUntil("the window the taller canvas's size", TIMEOUT_MS, () -> {
                settle();
                return pageWindowFitsItsCanvas(pane);
            }, () -> pageSizes(pane));
        } finally {
            SessionProbe.lua(session, "vim.o.laststatus = 2 return 0");
        }
        waitUntil("the status row back", TIMEOUT_MS, () -> {
            settle();
            return pane.statusRowForTests().getSize().y > 0 && pageWindowFitsItsCanvas(pane);
        }, () -> pageSizes(pane));
    }

    /**
     * While the page has the focus, Neovim's screen is the page's, however the code editor resizes;
     * with no part holding the focus (the page closed while a view was active), the code's resize
     * goes through again.
     */
    @Test
    void theCodesResizeIsHeldOnlyWhileThePageHasTheFocus() throws Exception {
        NeovimPageEditor page = messagesShowing("hold the size");
        WindowPane pane = page.windowPane();
        UiTestSupport.requireKeyboardFocus(page.canvasForTests(), TIMEOUT_MS);
        Shell shell = widget.getShell();
        Point shellSize = shell.getSize();
        try {
            shell.setSize(shellSize.x - 120, shellSize.y - 150); // the code editor resizes too
            shell.layout(true, true);
            waitUntil("the page's window the smaller page's size, not the code's", TIMEOUT_MS, () -> {
                settle();
                return pageWindowFitsItsCanvas(pane);
            }, () -> pageSizes(pane));
            page().showView(IPageLayout.ID_TASK_LIST); // the page loses the focus: no part holds it
            settle();
            page().closeEditor(page, false);
            settle();
            int lines = ((Number) SessionProbe.lua(session, "return vim.o.lines")).intValue();
            shell.setSize(shellSize);
            shell.layout(true, true);
            waitUntil("the code's resize sent with no part holding the focus", TIMEOUT_MS, () -> {
                settle();
                return ((Number) SessionProbe.lua(session, "return vim.o.lines")).intValue() > lines;
            });
        } finally {
            shell.setSize(shellSize);
        }
    }

    /**
     * {@code :e} and Tab in the page: Neovim's candidates in its popup menu ({@link #popupMenu},
     * the float of window {@code -1}), drawn over the page as over code, gone with the line.
     */
    @Test
    void tabOnThePagesCommandLineListsTheCandidates() throws Exception {
        createFile("tabone.txt", "1\n");
        createFile("tabtwo.txt", "2\n");
        NeovimPageEditor page = messagesShowing("tab here");
        UiTestSupport.requireKeyboardFocus(page.canvasForTests(), TIMEOUT_MS);
        for (char c : ":e tab".toCharArray()) {
            key(page.canvasForTests(), c);
        }
        expectCommandLine(":e tab");
        page.sendKey("<Tab>");
        waitUntil("the candidates listed", TIMEOUT_MS, () -> {
            settle();
            String menu = popupMenuText();
            return menu.contains("tabone.txt") && menu.contains("tabtwo.txt");
        }, () -> "; the menu reads \"" + popupMenuText() + "\"");
        session.inputAsync("<Esc>");
        waitUntil("the candidates gone with the line", TIMEOUT_MS, () -> {
            settle();
            return popupMenu() == null;
        });
    }

    /** Ex mode ({@code gQ}) in the page: a one-line {@code :echo} stays shown when the next line opens. */
    @Test
    void exModeKeepsAOneLineEchoShown() {
        NeovimPageEditor page = messagesShowing("ex mode here");
        UiTestSupport.requireKeyboardFocus(page.canvasForTests(), TIMEOUT_MS);
        waitUntil("Neovim in the page", TIMEOUT_MS, () -> currentWin() == page.windowPane().window());
        SessionProbe.lua(session, "vim.api.nvim_feedkeys('gQ', 'n', false) return 0");
        waitUntil("Ex mode", TIMEOUT_MS, () -> VimModes.isExMode(session.getMode()));
        try {
            for (char c : "echo 'ex one'".toCharArray()) {
                key(page.canvasForTests(), c);
            }
            Event enter = new Event();
            enter.keyCode = SWT.CR;
            enter.character = SWT.CR;
            enter.doit = true;
            page.canvasForTests().notifyListeners(SWT.KeyDown, enter);
            waitUntil("the echo shown, with the next Ex line open", TIMEOUT_MS, () -> {
                settle();
                return shownText().contains("ex one") && commandLine().startsWith(":");
            }, () -> "; Neovim shows '" + shownText() + "', its line '" + commandLine() + "'");
        } finally {
            session.inputAsync("vi<CR>"); // leaves Ex mode
            waitUntil("Ex mode left", TIMEOUT_MS, () -> !VimModes.isExMode(session.getMode()));
        }
    }

    /**
     * A second workbench window (Window › New Window) with a text editor and a messages editor in
     * it: both take keys, and closing the window leaves nothing broken: no error in the log while
     * the window is used, no warning either while it closes, and the first window's editor and
     * status bar item still work. Each workbench window has status bar items of its own
     * ({@code PageStatus} per status line manager).
     *
     * <p>Warnings count only from the close on, and never {@code KeyInput}'s "A key took 315 ms"
     * diagnostic: a slow machine (a run pinned to one core) may log it for a key typed before the
     * close, when that key ends after it, and it says nothing about the window's close.
     */
    @Test
    void aSecondWorkbenchWindowWithAPageClosesCleanly() throws Exception {
        List<String> logged = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
        ILogListener listener = (status, plugin) -> {
            boolean slowKey = String.valueOf(status.getMessage()).startsWith("A key took "); // KeyInput's speed diagnostic
            if (status.getSeverity() >= IStatus.ERROR
                    || closing.get() && status.getSeverity() >= IStatus.WARNING && !slowKey) {
                logged.add(status.getPlugin() + ": " + status.getMessage()
                        + (status.getException() != null ? " (" + status.getException() + ")" : ""));
            }
        };
        IFile file = createFile("second.txt", "second\n");
        Platform.addLogListener(listener);
        IWorkbenchWindow second = PlatformUI.getWorkbench().openWorkbenchWindow(null);
        try {
            ITextEditor there = (ITextEditor) IDE.openEditor(second.getActivePage(), file, TEXT_EDITOR, true);
            waitForAttach(there, "second.txt");
            StyledText thereText = (StyledText) there.getAdapter(Control.class);
            SessionProbe.lua(session, "vim.cmd.echomsg(\"'second window'\") return 0");
            colonIn(thereText, "messages");
            NeovimPageEditor[] found = new NeovimPageEditor[1];
            waitUntil("a messages editor in the second window", TIMEOUT_MS, () -> {
                settle();
                for (IEditorReference reference : second.getActivePage().getEditorReferences()) {
                    if (reference.getEditor(false) instanceof NeovimPageEditor p && p.kind() == PageKind.MESSAGES
                            && rowsOf(p).contains("second window")) {
                        found[0] = p;
                        return true;
                    }
                }
                return false;
            });
            NeovimPageEditor page = found[0];
            UiTestSupport.requireKeyboardFocus(page.canvasForTests(), TIMEOUT_MS);
            waitUntil("Neovim in the second window's page", TIMEOUT_MS, () -> currentWin() == page.windowPane().window());
            key(page.canvasForTests(), 'k'); // a key in the page: Neovim's
            IStatusLineManager bar = page.getEditorSite().getActionBars().getStatusLineManager();
            waitUntil("the pages' item in the second window's status bar", TIMEOUT_MS,
                    () -> bar.find(ModeStatusContribution.PAGE_ID) != null);
            second.getActivePage().activate(there);
            UiTestSupport.requireKeyboardFocus(thereText, TIMEOUT_MS);
            type(thereText, "iSECOND ");
            type(thereText, "\u001b");
            IDocument thereDocument = there.getDocumentProvider().getDocument(there.getEditorInput());
            waitUntil("typed in the second window's editor", TIMEOUT_MS, () -> thereDocument.get().contains("SECOND"));
            settle(); // every key typed handled, the Esc too, before the close
            there.doRevertToSaved();
        } finally {
            closing.set(true);
            second.close();
            settle();
            Platform.removeLogListener(listener);
        }
        assertEquals(List.of(), logged, "no error while the second window was used, nothing logged while it closed");
        page().activate(editor);
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
        waitForAttach(editor, "the first window's editor again");
        type(widget, "ifirst still ");
        type(widget, "\u001b");
        waitUntil("the first window's editor takes keys", TIMEOUT_MS, () -> document.get().contains("first still"));
        IStatusLineManager firstBar = editor.getEditorSite().getActionBars().getStatusLineManager();
        assertTrue(firstBar.find(ModeStatusContribution.ID) != null, "the first window's Neovim item is there");
        assertNull(firstBar.find(ModeStatusContribution.PAGE_ID), "and no pages' item left from the second");
    }

    private int currentWin() {
        return ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_win()")).intValue();
    }

    /**
     * The first {@link GridCanvas} below {@code parent}: the command-line window's panel over a
     * page. ui2's windows and Neovim's popup menu, drawn over the page too, are skipped
     * ({@link #isNeovimsOwnFloat}).
     */
    private GridCanvas gridCanvasChildOf(Composite parent) {
        if (parent.isDisposed()) {
            return null;
        }
        for (Control child : parent.getChildren()) {
            if (child instanceof GridCanvas grid && !grid.isDisposed() && grid.isVisible() && !StatusLineRow.isRow(grid) && !isNeovimsOwnFloat(grid)) {
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
