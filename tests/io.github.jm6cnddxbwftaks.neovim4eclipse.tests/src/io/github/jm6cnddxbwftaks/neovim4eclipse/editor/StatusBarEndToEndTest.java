// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;

/**
 * The status bar in a real window (docs/design/STATUS_BAR.md): the mode's name and
 * {@code ECLIPSE} when keys go to Eclipse; and Neovim's status line, which is no longer in the bar
 * but in a row under each editor's text. The texts are {@code ModeLabelsTest}; the label's width
 * {@code StatusBarLabelsTest}.
 */
class StatusBarEndToEndTest extends EndToEndTestBase {

    private static IPreferenceStore store() {
        return Activator.getDefault().getPreferenceStore();
    }

    @AfterEach
    void restorePreferences() {
        store().setToDefault(PreferenceConstants.STATUS_MODE);
        // No controller when the test failed with the editor gone: the preferences are restored
        // anyway, and the test's own failure is the one reported.
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        if (controller != null) {
            controller.setNeovimModeActive(true);
            settle();
        }
    }

    private void waitForLabel(String text) {
        waitUntil("the status bar showing " + text, TIMEOUT_MS, () -> statusLineTexts(editor).contains(text),
                () -> "; it shows " + statusLineTexts(editor));
    }

    /**
     * The mode's names ({@code NORMAL}, {@code INSERT}, {@code V-LINE}), and a recording after them
     * in lower case ({@code NORMAL rec @a}); the tooltip keeps Vim's wording.
     */
    @Test
    void theModeAndARecording() {
        waitForLabel("NORMAL");
        typeSlowly("i");
        waitForLabel("INSERT");
        UiTestSupport.escape(widget);
        waitForLabel("NORMAL");
        typeSlowly("V");
        waitForLabel("V-LINE");
        UiTestSupport.escape(widget);
        typeSlowly("qa");
        try {
            waitForLabel("NORMAL rec @a");
        } finally {
            typeSlowly("q");
        }
        waitForLabel("NORMAL");
    }

    /**
     * The status row under the editor's text ({@link StatusLineRow}): the visible row canvas child of
     * the widget that lies just above the command line's row and shows window {@code window}'s grid;
     * {@code null} when none is shown.
     */
    private String statusRowText(int window) {
        return statusRowText(widget, window);
    }

    /** {@link #statusRowText(int)} in editor widget {@code text}, which may be another editor's. */
    private static String statusRowText(StyledText text, int window) {
        Rectangle area = text.getClientArea();
        int commandLine = StatusLineRow.COMMAND_LINE_ROWS * text.getLineHeight();
        for (Control child : text.getChildren()) {
            if (child instanceof GridCanvas canvas && StatusLineRow.isRow(canvas) && canvas.isVisible() && canvas.grid() != null
                    && canvas.grid().window() == window && canvas.grid().height() == 1
                    && canvas.getBounds().y + canvas.getBounds().height == area.height - commandLine) {
                return canvas.grid().row(0);
            }
        }
        return null;
    }

    /**
     * Neovim's status line is drawn under the editor's text, evaluated by Neovim for this editor's
     * window (its default format names the file), and {@code :set laststatus=0} takes the row away,
     * and its line of margin with it; the command line's row stays. The cells are {@code status_line_spec.lua}, the row's
     * placement {@code StatusLineRowTest}; here the session, the notifications and the row together.
     */
    @Test
    void theEditorShowsItsWindowsStatusLineUnderItsText() throws Exception {
        SessionProbe.lua(session, "vim.cmd('set laststatus=2') return 0");
        typeSlowly("l");
        int window = ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_win()")).intValue();
        waitUntil("a status row naming test.txt", TIMEOUT_MS, () -> {
            settle();
            String row = statusRowText(window);
            return row != null && row.contains("test.txt");
        }, () -> "; the row reads " + statusRowText(window));
        // The status line's row and the command line's take that many lines of margin under the
        // text; without a status line, the command line's stay.
        int line = widget.getLineHeight();
        int margin = widget.getBottomMargin() - line;
        assertTrue(margin >= StatusLineRow.COMMAND_LINE_ROWS * line, "the rows take their margin under the text: " + widget.getBottomMargin());
        try {
            SessionProbe.lua(session, "vim.cmd('set laststatus=0') return 0");
            waitUntil("the status row gone", TIMEOUT_MS, () -> {
                settle();
                return statusRowText(window) == null && widget.getBottomMargin() == margin;
            }, () -> "; the row reads " + statusRowText(window) + ", margin " + widget.getBottomMargin());
        } finally {
            SessionProbe.lua(session, "vim.cmd('set laststatus=2') return 0");
        }
    }

    /**
     * Two editors side by side ({@code :vsplit}, a second editor on test.txt in a stack of its own):
     * each shows its own window's status line, the one in the background too. Neovim draws only
     * the focused editor's screen, so the background editor's row lives on the lines Neovim sends
     * for every window ({@code eclipse_status_lines}).
     */
    @Test
    void anEditorBesideTheFocusedOneShowsItsStatusLineToo() throws Exception {
        SessionProbe.lua(session, "vim.cmd('set laststatus=2') return 0");
        int first = ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_win()")).intValue();
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        session.commandAsync("vsplit");
        settle();
        try {
            waitUntil("the clone of :vsplit", TIMEOUT_MS, () -> page.getActiveEditor() != editor
                    && page.getActiveEditor() instanceof ITextEditor);
            ITextEditor clone = (ITextEditor) page.getActiveEditor();
            waitForAttach(clone, "the clone of :vsplit");
            settle();
            int second = ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_win()")).intValue();
            StyledText cloneText = widgetOf(clone);
            waitUntil("both editors' status rows naming test.txt", TIMEOUT_MS, () -> {
                settle();
                String focused = statusRowText(cloneText, second);
                String background = statusRowText(widget, first);
                return focused != null && focused.contains("test.txt") && background != null
                        && background.contains("test.txt");
            }, () -> "; the clone's row (window " + second + ") reads " + statusRowText(cloneText, second)
                    + ", the background editor's (window " + first + ") " + statusRowText(widget, first));
        } finally {
            for (IEditorReference reference : page.getEditorReferences()) {
                if (reference.getEditor(false) != editor) {
                    page.closeEditors(new IEditorReference[] { reference }, false);
                }
            }
            settle();
        }
    }

    /**
     * With the user's {@code 'cmdheight'} 0, output of several lines rises over the status line and
     * stays until a key; that key takes it all away, and the status line shows again, not a line
     * of the output left on its row.
     */
    @Test
    void withCmdheight0OneKeyTakesTheOutputAwayAndTheStatusLineIsBack() throws Exception {
        SessionProbe.lua(session, "vim.cmd('set laststatus=2') return 0");
        int window = ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_win()")).intValue();
        typeSlowly(":set ch=0\r");
        try {
            waitUntil("the status row on the editor's bottom edge", TIMEOUT_MS, () -> {
                settle();
                return statusRowBounds(window) != null && session.commandLineRows() == 0;
            });
            typeSlowly(":echo \"one\\ntwo\\nthree\"\r");
            waitUntil("the output drawn over the status line", TIMEOUT_MS, () -> {
                settle();
                return !floatsOver(statusRowBounds(window)).isEmpty();
            }, () -> "; floats " + floatsOver(statusRowBounds(window)));
            typeSlowly("j");
            waitUntil("nothing left over the status line after one key", TIMEOUT_MS, () -> {
                settle();
                return floatsOver(statusRowBounds(window)).isEmpty();
            }, () -> "; over it: " + floatsOver(statusRowBounds(window)));
        } finally {
            backToCmdheight1();
        }
    }

    /**
     * A message ({@code :echo "hello"}) shows on the command line's row, under the status line, and
     * stays there; the status line is not covered.
     */
    @Test
    void aMessageShowsOnTheRowUnderTheStatusLineAndStays() {
        int window = currentWindow();
        typeSlowly(":echo \"hello\"\r");
        waitUntil("hello under the status line", TIMEOUT_MS, () -> {
            settle();
            return helloUnderTheRow(window);
        }, () -> "; floats " + visibleFloats() + ", row " + statusRowBounds(window));
        settle();
        settle();
        assertTrue(helloUnderTheRow(window), "still there: " + visibleFloats());
    }

    private boolean helloUnderTheRow(int window) {
        Rectangle row = statusRowBounds(window);
        return row != null && floatsOver(row).isEmpty() && visibleFloatCanvases().stream()
                .anyMatch(c -> text(c).contains("hello") && c.getBounds().y >= row.y + row.height);
    }

    /**
     * {@code :set ch=0} takes the row under the status line away, so the editor shows one more text
     * row and its status line lies on its bottom edge; {@code :set ch=1} brings the row back.
     */
    @Test
    void withCmdheight0TheRowUnderTheStatusLineGoesAndComesBack() {
        int window = currentWindow();
        int line = widget.getLineHeight();
        waitUntil("the status row", TIMEOUT_MS, () -> {
            settle();
            return statusRowBounds(window) != null;
        });
        int margin = widget.getBottomMargin();
        typeSlowly(":set ch=0\r");
        try {
            waitUntil("one row less, the status line on the bottom edge", TIMEOUT_MS, () -> {
                settle();
                Rectangle row = statusRowBounds(window);
                return widget.getBottomMargin() == margin - line && row != null
                        && row.y + row.height == widget.getClientArea().height;
            }, () -> "; margin " + widget.getBottomMargin() + " (was " + margin + "), row " + statusRowBounds(window)
                    + ", client " + widget.getClientArea());
        } finally {
            backToCmdheight1();
        }
        waitUntil("the row back", TIMEOUT_MS, () -> {
            settle();
            Rectangle row = statusRowBounds(window);
            return widget.getBottomMargin() == margin && row != null
                    && row.y + row.height == widget.getClientArea().height - line;
        }, () -> "; margin " + widget.getBottomMargin() + ", row " + statusRowBounds(window));
    }

    /**
     * With {@code 'cmdheight'} 0 the {@code :} line being typed lies over the status line, and its
     * Tab list right above it, covering neither; the text does not move. At Enter the line goes,
     * and a message shows on the row above the status line, for a while.
     */
    @Test
    void withCmdheight0TheTypedLineLiesOverTheStatusLine() {
        int window = currentWindow();
        typeSlowly(":set ch=0\r");
        try {
            waitUntil("the status row on the bottom edge", TIMEOUT_MS, () -> {
                settle();
                return statusRowBounds(window) != null && session.commandLineRows() == 0;
            });
            int top = widget.getTopIndex();
            int margin = widget.getBottomMargin();
            typeSlowly(":set ");
            UiTestSupport.type(widget, '\t');
            waitUntil("the line over the status line, its list right above it", TIMEOUT_MS, () -> {
                settle();
                Rectangle row = statusRowBounds(window);
                if (row == null) {
                    return false;
                }
                boolean lineOnTheRow = visibleFloatCanvases().stream()
                        .anyMatch(c -> text(c).startsWith(":set") && c.getBounds().y == row.y);
                boolean listAbove = visibleFloatCanvases().stream().anyMatch(c -> c.grid().window() == -1 // the popup menu
                        && c.getBounds().y + c.getBounds().height == row.y);
                return lineOnTheRow && listAbove;
            }, () -> "; floats " + visibleFloats() + ", row " + statusRowBounds(window));
            UiTestSupport.escape(widget);
            settle();
            typeSlowly(":echo \"hi\"\r");
            waitUntil("hi on the row above the status line, nothing over it", TIMEOUT_MS, () -> {
                settle();
                Rectangle row = statusRowBounds(window);
                return row != null && floatsOver(row).isEmpty() && visibleFloatCanvases().stream()
                        .anyMatch(c -> text(c).contains("hi") && c.getBounds().y + c.getBounds().height == row.y);
            }, () -> "; floats " + visibleFloats() + ", row " + statusRowBounds(window));
            assertTrue(widget.getTopIndex() == top && widget.getBottomMargin() == margin,
                    "the text did not move: top " + widget.getTopIndex() + " (was " + top + "), margin "
                            + widget.getBottomMargin() + " (was " + margin + ")");
            waitUntil("the message gone after a while", TIMEOUT_MS, () -> {
                settle();
                return visibleFloatCanvases().stream().noneMatch(c -> text(c).contains("hi"));
            }, () -> "; floats " + visibleFloats());
        } finally {
            backToCmdheight1();
        }
    }

    /** Whatever a failed step left open is closed, and 'cmdheight' is 1 again: one Neovim serves every test. */
    private void backToCmdheight1() {
        session.inputAsync("<Esc><Esc>");
        settle();
        typeSlowly(":set ch=1\r");
        waitUntil("'cmdheight' 1 again", TIMEOUT_MS, () -> {
            settle();
            return session.commandLineRows() == 1;
        });
    }

    /** The current window, with 'laststatus' 2 (Neovim's default, so no test needs it put back). */
    private int currentWindow() {
        try {
            SessionProbe.lua(session, "vim.cmd('set laststatus=2') return 0");
            return ((Number) SessionProbe.lua(session, "return vim.api.nvim_get_current_win()")).intValue();
        } catch (Exception e) {
            throw new AssertionError("Neovim did not answer", e);
        }
    }

    /** The floats drawn in the editor (Neovim's windows and ui2's), not the status row. */
    private List<GridCanvas> visibleFloatCanvases() {
        List<GridCanvas> floats = new java.util.ArrayList<>();
        for (Control child : widget.getChildren()) {
            if (child instanceof GridCanvas canvas && !StatusLineRow.isRow(canvas) && canvas.isVisible()
                    && canvas.grid() != null) {
                floats.add(canvas);
            }
        }
        return floats;
    }

    private List<String> visibleFloats() {
        return visibleFloatCanvases().stream().map(c -> c.getBounds() + " \"" + text(c) + "\"").toList();
    }

    /** A canvas's text, its rows joined by {@code |}, trailing blanks dropped. */
    private static String text(GridCanvas canvas) {
        List<String> rows = new java.util.ArrayList<>();
        for (int r = 0; r < canvas.grid().height(); r++) {
            rows.add(canvas.grid().row(r).strip());
        }
        return String.join("|", rows);
    }

    /** The visible status row's bounds for {@code window}, or {@code null}. */
    private Rectangle statusRowBounds(int window) {
        for (Control child : widget.getChildren()) {
            if (child instanceof GridCanvas canvas && StatusLineRow.isRow(canvas) && canvas.isVisible()
                    && canvas.grid() != null && canvas.grid().window() == window) {
                return canvas.getBounds();
            }
        }
        return null;
    }

    /** The visible floats (not the row) that cover {@code row}, with what they show. */
    private List<String> floatsOver(Rectangle row) {
        List<String> over = new java.util.ArrayList<>();
        for (Control child : widget.getChildren()) {
            if (row != null && child instanceof GridCanvas canvas && !StatusLineRow.isRow(canvas) && canvas.isVisible()
                    && canvas.getBounds().intersects(row)) {
                over.add(canvas.getBounds() + " " + (canvas.grid() == null ? null
                        : canvas.grid().height() + " rows, last " + canvas.grid().row(canvas.grid().height() - 1).strip()));
            }
        }
        return over;
    }

    /**
     * The indicator says where keys go, so it shows even with the mode part turned off. Which parts
     * show is {@code StatusItemTest} (tests/unit); here the preference, the switch to Eclipse and
     * the bar's rebuild together.
     */
    @Test
    void eclipseShowsEvenWithTheModeOff() {
        store().setValue(PreferenceConstants.STATUS_MODE, false);
        waitUntil("no mode label", TIMEOUT_MS, () -> !statusLineTexts(editor).contains("NORMAL"),
                () -> "; it shows " + statusLineTexts(editor));
        EditorAttachManager.getInstance().getActiveController().setNeovimModeActive(false);
        waitForLabel("ECLIPSE");
        EditorAttachManager.getInstance().getActiveController().setNeovimModeActive(true);
        waitUntil("the indicator gone again", TIMEOUT_MS, () -> !statusLineTexts(editor).contains("ECLIPSE"),
                () -> "; it shows " + statusLineTexts(editor));
    }
}
