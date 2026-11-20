// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
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
     * The status row under the editor's text ({@link StatusLineRow}): the visible canvas child of the
     * widget that lies on the client area's last line and shows window {@code window}'s grid;
     * {@code null} when none is shown. The window tells it from ui2's floats, which can lie on the
     * same line (with {@code 'laststatus'} 0 the ruler moves to ui2's command-line window).
     */
    private String statusRowText(int window) {
        Rectangle area = widget.getClientArea();
        for (Control child : widget.getChildren()) {
            if (child instanceof GridCanvas canvas && canvas.isVisible() && canvas.grid() != null && canvas.grid().window() == window
                    && canvas.grid().height() == 1 && canvas.getBounds().y + canvas.getBounds().height == area.height) {
                return canvas.grid().row(0);
            }
        }
        return null;
    }

    /**
     * Neovim's status line is drawn under the editor's text, evaluated by Neovim for this editor's
     * window (its default format names the file), and {@code :set laststatus=0} takes the row away,
     * the text widget's margin with it. The cells are {@code status_line_spec.lua}, the row's
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
        // The row's margin is one line high; the editor's own margin is what is left without it.
        int margin = widget.getBottomMargin() - widget.getLineHeight();
        assertTrue(margin >= 0, "the row takes a line of margin under the text: " + widget.getBottomMargin());
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
