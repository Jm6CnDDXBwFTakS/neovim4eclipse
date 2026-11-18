// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.ParseException;
import org.eclipse.jface.resource.FontRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.keys.IBindingService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageKind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ScreenInput;

/**
 * The page editors in a real workbench, opened by the commands typed in an editor:
 * {@code :messages}, {@code :checkhealth} and {@code :help} each open an editor of their own below
 * the code (the next ones as tabs beside the first), titled with what it holds and showing it in a
 * second Neovim; {@code Ctrl-W p} gives the keyboard back to the editor; {@code q} closes it. The
 * editor itself, opened directly, is {@link NeovimPageEditorEndToEndTest}'s.
 */
class NeovimPagesEndToEndTest extends EndToEndTestBase {

    private static final String CTRL_W = "io.github.jm6cnddxbwftaks.neovim4eclipse.commands.ctrl.w";

    @AfterEach
    void closeThePages() {
        // A failed test leaves no page editor (and no second Neovim) for the next one.
        for (PageKind kind : PageKind.values()) {
            IEditorPart page = page().findEditor(new NeovimPageInput(kind));
            if (page != null) {
                page().closeEditor(page, false);
            }
        }
        settle();
    }

    /** The open page editors, of any kind. */
    private List<NeovimPageEditor> pages() {
        List<NeovimPageEditor> pages = new ArrayList<>();
        for (PageKind kind : PageKind.values()) {
            if (page().findEditor(new NeovimPageInput(kind)) instanceof NeovimPageEditor page) {
                pages.add(page);
            }
        }
        return pages;
    }

    /** Runs {@code line} from the editor's {@code :} box, as typed. */
    private void colon(String line) {
        type(widget, ":");
        waitUntil("the : box", TIMEOUT_MS, () -> textChildOf(widget) != null);
        Text box = textChildOf(widget);
        UiTestSupport.requireKeyboardFocus(box, TIMEOUT_MS);
        box.insert(line);
        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        enter.doit = true;
        box.notifyListeners(SWT.KeyDown, enter);
    }

    /** Waits for a page editor to show a row holding {@code text}, and returns it. */
    private NeovimPageEditor pageShowing(String text) {
        NeovimPageEditor[] found = new NeovimPageEditor[1];
        waitUntil("a page editor showing " + text, TIMEOUT_MS, () -> {
            settle();
            for (NeovimPageEditor page : pages()) {
                if (rowsOf(page).contains(text)) {
                    found[0] = page;
                    return true;
                }
            }
            return false;
        }, () -> "; open: " + pages().stream().map(p -> p.getPartName() + " showing " + rowsOf(p)).toList());
        return found[0];
    }

    private static String rowsOf(NeovimPageEditor view) {
        GridSnapshot grid = view.canvasForTests().grid();
        if (grid == null) {
            return "";
        }
        StringBuilder rows = new StringBuilder();
        for (int r = 0; r < grid.height(); r++) {
            rows.append(grid.row(r).stripTrailing()).append('\n');
        }
        return rows.toString();
    }

    private static void key(Control control, int keyCode, char character, int stateMask) {
        Event event = new Event();
        event.keyCode = keyCode;
        event.character = character;
        event.stateMask = stateMask;
        event.doit = true;
        control.notifyListeners(SWT.KeyDown, event);
    }

    @Test
    void messagesOpenAnEditorAndCtrlWPGoesBackToTheEditor() {
        SessionProbe.lua(session, "vim.cmd.echomsg(\"'from the end-to-end test'\") return 0");
        colon("messages");
        NeovimPageEditor view = pageShowing("from the end-to-end test");
        assertEquals("Neovim: messages", view.getPartName());
        UiTestSupport.requireKeyboardFocus(view.canvasForTests(), TIMEOUT_MS);

        // Ctrl+W as Eclipse's key dispatcher sees it: in the page's key context it is the plug-in's
        // Ctrl-W command, not Close (which would close the page editor), and that
        // command sends <C-w> to the page's Neovim. (macOS: Close is Cmd+W, Ctrl+W free anyway.)
        if (!PlatformUtil.isMac()) {
            IBindingService bindings = PlatformUI.getWorkbench().getService(IBindingService.class);
            Binding binding;
            try {
                binding = bindings.getPerfectMatch(KeySequence.getInstance("M1+W"));
            } catch (ParseException e) {
                throw new AssertionError(e);
            }
            assertEquals(CTRL_W, binding == null ? null : binding.getParameterizedCommand().getId());
        }
        runCtrlW(view);
        key(view.canvasForTests(), 'p', 'p', 0);
        waitUntil("the editor has the keyboard back", TIMEOUT_MS, widget::isFocusControl);
        assertEquals(true, editor.getSite().getPage().isPartVisible(editor), "the editor is still open");
    }

    @Test
    void qClosesIt() {
        SessionProbe.lua(session, "vim.cmd.echomsg(\"'before q'\") return 0");
        colon("messages");
        NeovimPageEditor view = pageShowing("before q");
        UiTestSupport.requireKeyboardFocus(view.canvasForTests(), TIMEOUT_MS);
        key(view.canvasForTests(), 'q', 'q', 0);
        waitUntil("the editor closed", TIMEOUT_MS, () -> {
            settle();
            return pages().isEmpty();
        });
    }

    /** The row of {@code grid} holding {@code text}, or -1. */
    private static int rowWith(GridSnapshot grid, String text) {
        for (int r = 0; r < grid.height(); r++) {
            if (grid.row(r).contains(text)) {
                return r;
            }
        }
        return -1;
    }

    /**
     * Where it opens and how it looks: in a stack of its own below the code, as Vim's
     * {@code :help} splits the window, with the keyboard, an error in Neovim's error color, the
     * cursor on the newest message, and the editor's background under the text.
     */
    @Test
    void itOpensBelowTheCodeAndShowsAnErrorInItsColor() {
        SessionProbe.lua(session, "vim.cmd.echomsg(\"'a plain message'\")"
                + " vim.api.nvim_echo({ { 'E486: Pattern not found: nothing-like-this' } }, true, { err = true }) return 0");
        colon("messages");
        NeovimPageEditor view = pageShowing("E486: Pattern not found");
        UiTestSupport.requireKeyboardFocus(view.canvasForTests(), TIMEOUT_MS);

        MPart part = view.getSite().getService(MPart.class);
        MPart code = editor.getSite().getService(MPart.class);
        assertNotEquals(EditorSplits.stackOf(code), EditorSplits.stackOf(part), "a stack of its own");
        assertTrue(EditorSplits.onScreen(part).y() > EditorSplits.onScreen(code).y(),
                "below the code: " + EditorSplits.onScreen(part) + " under " + EditorSplits.onScreen(code));

        GridSnapshot grid = view.canvasForTests().grid();
        int plain = rowWith(grid, "a plain message");
        int error = rowWith(grid, "E486");
        assertTrue(plain >= 0 && error > plain, rowsOf(view));
        assertNotEquals(grid.attr(grid.hlAt(plain, 0)).foreground(), grid.attr(grid.hlAt(error, 0)).foreground(),
                "the error in Neovim's ErrorMsg color, the plain message not");
        assertEquals(error, grid.cursorRow(), "the cursor on the newest message");

        GridCanvas canvas = view.canvasForTests();
        Point size = canvas.getSize();
        Image image = new Image(canvas.getDisplay(), size.x, size.y);
        GC gc = new GC(image);
        try {
            canvas.paint(gc);
            ImageData data = image.getImageData();
            int x = (grid.width() - 1) * canvas.cellWidth() + canvas.cellWidth() / 2; // a blank cell at the row's end
            int y = plain * canvas.cellHeight() + canvas.cellHeight() / 2;
            RGB pixel = data.palette.getRGB(data.getPixel(x, y));
            assertEquals(OverlayFrame.intendedBackground(widget).getRGB(), pixel, "the editor's background under the text");
        } finally {
            gc.dispose();
            image.dispose();
        }
    }

    /** {@code /} searches in the page editor, {@code yy} yanks, and {@code p} in the editor pastes the line. */
    @Test
    void aLineYankedInTheViewPastesInTheEditor() {
        SessionProbe.lua(session, "vim.cmd.echomsg(\"'paste me here'\") vim.cmd.echomsg(\"'a later message'\") return 0");
        colon("messages");
        NeovimPageEditor view = pageShowing("paste me here");
        GridCanvas canvas = view.canvasForTests();
        UiTestSupport.requireKeyboardFocus(canvas, TIMEOUT_MS);
        for (char c : "gg/paste".toCharArray()) {
            key(canvas, c, c, 0);
        }
        key(canvas, SWT.CR, SWT.CR, 0);
        waitUntil("the search found the line", TIMEOUT_MS, () -> {
            settle();
            GridSnapshot grid = canvas.grid();
            return grid != null && grid.cursorRow() == rowWith(grid, "paste me here");
        });
        key(canvas, 'y', 'y', 0);
        key(canvas, 'y', 'y', 0);
        waitUntil("the yank in the editing Neovim's register", TIMEOUT_MS,
                () -> "paste me here\n".equals(SessionProbe.lua(session, "return vim.fn.getreg('\"')")));
        runCtrlW(view);
        key(canvas, 'p', 'p', 0);
        waitUntil("the editor has the keyboard back", TIMEOUT_MS, widget::isFocusControl);
        type(widget, "p");
        waitUntil("the line pasted below the cursor", TIMEOUT_MS, () -> document.get().contains("\npaste me here\n"),
                () -> "; the document is " + document.get());
        type(widget, "u");
        waitUntil("the paste undone", TIMEOUT_MS, () -> !document.get().contains("paste me here"));
    }

    /**
     * A change of Eclipse's text font (Colors and Fonts) gives Neovim's screen the number of cells
     * that fits the editor in the new font, without the editor being resized.
     */
    @Test
    void aTextFontChangeResizesTheScreen() {
        SessionProbe.lua(session, "vim.cmd.echomsg(\"'before the font change'\") return 0");
        colon("messages");
        NeovimPageEditor view = pageShowing("before the font change");
        GridCanvas canvas = view.canvasForTests();
        int before = canvas.grid().width();
        FontRegistry fonts = JFaceResources.getFontRegistry();
        FontData[] original = fonts.getFontData(JFaceResources.TEXT_FONT);
        FontData[] twice = new FontData[original.length];
        for (int i = 0; i < original.length; i++) {
            twice[i] = new FontData(original[i].getName(), original[i].getHeight() * 2, original[i].getStyle());
        }
        try {
            fonts.put(JFaceResources.TEXT_FONT, twice);
            waitUntil("the screen in the new font's cells", TIMEOUT_MS, () -> {
                settle();
                return canvas.grid().width() == ScreenInput.cells(canvas.getSize().x, canvas.cellWidth())
                        && canvas.grid().width() < before;
            }, () -> "; " + canvas.grid().width() + " columns, " + before + " before");
        } finally {
            fonts.put(JFaceResources.TEXT_FONT, original);
            settle();
        }
    }

    /** The wheel scrolls the page's text, and a click puts its cursor on the clicked line. */
    @Test
    void theWheelScrollsAndAClickMovesTheCursor() {
        SessionProbe.lua(session, "for i = 1, 120 do vim.cmd.echomsg(string.format(\"'message %03d'\", i)) end return 0");
        colon("messages");
        NeovimPageEditor view = pageShowing("message 120");
        GridCanvas canvas = view.canvasForTests();
        String top = canvas.grid().row(0);
        Event wheel = new Event();
        wheel.count = 3; // one notch away from the user: earlier lines
        wheel.x = 5;
        wheel.y = 5;
        canvas.notifyListeners(SWT.MouseVerticalWheel, wheel);
        waitUntil("the text scrolled", TIMEOUT_MS, () -> {
            settle();
            return !canvas.grid().row(0).equals(top);
        }, () -> "; the top row is " + canvas.grid().row(0));
        Event press = new Event();
        press.button = 1;
        press.x = canvas.cellWidth() + 1;
        press.y = 2 * canvas.cellHeight() + 1;
        canvas.notifyListeners(SWT.MouseDown, press);
        canvas.notifyListeners(SWT.MouseUp, press);
        waitUntil("the cursor on the clicked row", TIMEOUT_MS, () -> {
            settle();
            return canvas.grid().cursorRow() == 2;
        }, () -> "; the cursor is on row " + canvas.grid().cursorRow());
    }

    /**
     * {@code :help} opens the page in the help editor, as Neovim's own help (the editing Neovim
     * keeps no help window), and {@code :messages} afterwards opens the messages editor, a tab in
     * the help editor's stack, with a second Neovim of its own; the help editor stays.
     */
    @Test
    void helpAndMessagesGetEditorsOfTheirOwnInOneStack() {
        colon("help help-context");
        NeovimPageEditor view = pageShowing("help-context");
        assertEquals("Neovim: help", view.getPartName());
        assertTrue(rowsOf(view).contains("[Help]"), rowsOf(view));
        assertEquals(1L, ((Number) SessionProbe.lua(session, "return #vim.api.nvim_tabpage_list_wins(0)")).longValue(),
                "no help window left in the editing Neovim");

        SessionProbe.lua(session, "vim.cmd.echomsg(\"'after help'\") return 0");
        page().activate(editor);
        waitUntil("the editor has the keyboard", TIMEOUT_MS, widget::isFocusControl);
        colon("messages");
        view = pageShowing("after help");
        assertEquals("Neovim: messages", view.getPartName());
        assertTrue(!rowsOf(view).contains("[Help]"), rowsOf(view));
        IEditorPart help = page().findEditor(new NeovimPageInput(PageKind.HELP));
        assertTrue(help != null, "the help editor stays open beside it");
        assertEquals(EditorSplits.stackOf(help.getSite().getService(MPart.class)),
                EditorSplits.stackOf(view.getSite().getService(MPart.class)), "a tab in the help editor's stack");
    }

    /**
     * {@code Ctrl-W} goes both ways by where the editors are: {@code Ctrl-W k} from the help editor
     * below the code reaches the code's editor (the help Neovim has no window above its own), and
     * {@code Ctrl-W j} from the code reaches the help editor again.
     */
    @Test
    void ctrlWGoesBetweenTheCodeAndThePageByWhereTheyAre() {
        colon("help help-context");
        NeovimPageEditor help = pageShowing("help-context");
        UiTestSupport.requireKeyboardFocus(help.canvasForTests(), TIMEOUT_MS);
        runCtrlW(help);
        key(help.canvasForTests(), 'k', 'k', 0);
        waitUntil("Ctrl-W k: the code above", TIMEOUT_MS, () -> {
            settle();
            return page().getActiveEditor() == editor && widget.isFocusControl();
        }, () -> "; active: " + page().getActiveEditor().getTitle());
        session.inputAsync("<C-w>j");
        waitUntil("Ctrl-W j: the help editor below", TIMEOUT_MS, () -> {
            settle();
            return page().getActiveEditor() == help;
        }, () -> "; active: " + page().getActiveEditor().getTitle());
    }

    /**
     * {@code Ctrl-O} from the code goes back past the help editor, as Vim's jump history never
     * jumps into a help window: other.txt, then {@code :help} (the help editor's entry in Eclipse's
     * history), then a click back into test.txt; {@code Ctrl-O} reaches other.txt and never the help
     * editor.
     */
    @Test
    void ctrlOFromTheCodeNeverStopsAtAPageEditor() throws Exception {
        ITextEditor other = openOtherFile();
        try {
            waitForAttach(other, "other.txt");
            session.inputAsync(":help help-context<CR>");
            pageShowing("help-context");
            page().activate(editor);
            waitForAttach(editor, "test.txt");
            settle();
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < 3 && !"other.txt".equals(page().getActiveEditor().getTitle()); i++) {
                session.inputAsync("<C-o>");
                settle();
                seen.add(page().getActiveEditor().getTitle());
            }
            assertTrue(seen.contains("other.txt"), "Ctrl-O reached other.txt: " + seen);
            assertTrue(!seen.contains("Neovim: help"), "never the help editor: " + seen);
        } finally {
            page().closeEditor(other, false);
        }
    }

    /** {@code :checkhealth neovim4eclipse} opens its editor titled for the report, with the plug-in's lines. */
    @Test
    void thePluginsHealthCheckInItsEditor() {
        colon("checkhealth neovim4eclipse");
        NeovimPageEditor view = pageShowing("Neovim for Eclipse ");
        assertEquals("Neovim: checkhealth", view.getPartName());
        String rows = rowsOf(view);
        assertTrue(rows.contains("OK Neovim "), rows); // the first section; the rest lies below the editor's last row
    }

    /**
     * Ctrl+W pressed for real, through the operating system and Eclipse's key dispatcher, then
     * {@code p}: the editor has the keyboard back and is still open (Eclipse's Close did not take
     * the key). Not on macOS: there Close is Cmd+W, and posting keys needs the Accessibility
     * permission (docs/MANUAL_CHECKS.md, card nv-view, does it by hand).
     */
    @Test
    @DisabledOnOs(OS.MAC)
    void aRealCtrlWPGoesBackToTheEditor() {
        SessionProbe.lua(session, "vim.cmd.echomsg(\"'before a real Ctrl-W'\") return 0");
        colon("messages");
        NeovimPageEditor view = pageShowing("before a real Ctrl-W");
        UiTestSupport.requireKeyboardFocus(view.canvasForTests(), TIMEOUT_MS);
        Display display = view.canvasForTests().getDisplay();
        post(display, SWT.KeyDown, SWT.CTRL);
        postLetter(display, 'w');
        post(display, SWT.KeyUp, SWT.CTRL);
        postLetter(display, 'p');
        waitUntil("the editor has the keyboard back", TIMEOUT_MS, () -> {
            settle();
            return widget.isFocusControl();
        });
        assertTrue(page().isPartVisible(editor), "the editor is still open: Ctrl+W was not Eclipse's Close");
    }

    /** Runs the plug-in's Ctrl-W command as Eclipse's key binding would, with the page editor active. */
    private static void runCtrlW(NeovimPageEditor view) {
        IHandlerService handlers = view.getSite().getService(IHandlerService.class);
        try {
            handlers.executeCommand(CTRL_W, null);
        } catch (Exception e) {
            throw new AssertionError("the Ctrl-W command did not run", e);
        }
    }

    /** One key event through the operating system; {@code keyCode} an SWT key code such as {@code SWT.CTRL}. */
    private static void post(Display display, int type, int keyCode) {
        Event event = new Event();
        event.type = type;
        event.keyCode = keyCode;
        assertTrue(display.post(event), "the system took the key event");
    }

    /** A letter key pressed and let go through the operating system (a letter is posted by its character). */
    private static void postLetter(Display display, char letter) {
        for (int type : new int[] { SWT.KeyDown, SWT.KeyUp }) {
            Event event = new Event();
            event.type = type;
            event.character = letter;
            assertTrue(display.post(event), "the system took the key event");
        }
    }
}
