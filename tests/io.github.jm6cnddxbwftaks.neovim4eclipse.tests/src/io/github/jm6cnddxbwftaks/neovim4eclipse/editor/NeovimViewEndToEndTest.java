// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IViewPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;

/**
 * The Neovim view in a real workbench: {@code :messages} typed in an editor opens it, titled with
 * what it holds and showing the history in a second Neovim; {@code Ctrl-W p} gives the keyboard
 * back to the editor; {@code q} in the view closes it.
 */
class NeovimViewEndToEndTest extends EndToEndTestBase {

    @AfterEach
    void closeTheView() {
        IViewPart view = page().findView(NeovimView.ID);
        if (view != null) {
            page().hideView(view); // a failed test leaves no view (and no second Neovim) for the next one
        }
        settle();
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

    /** Waits for the Neovim view to show a row holding {@code text}, and returns it. */
    private NeovimView viewShowing(String text) {
        waitUntil("the Neovim view showing " + text, TIMEOUT_MS, () -> {
            settle();
            return page().findView(NeovimView.ID) instanceof NeovimView view && rowsOf(view).contains(text);
        }, () -> page().findView(NeovimView.ID) instanceof NeovimView view ? "; it shows " + rowsOf(view) : "; no view");
        return (NeovimView) page().findView(NeovimView.ID);
    }

    private static String rowsOf(NeovimView view) {
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
    void messagesOpenTheViewAndCtrlWPGoesBackToTheEditor() {
        SessionProbe.lua(session, "vim.cmd.echomsg(\"'from the end-to-end test'\") return 0");
        colon("messages");
        NeovimView view = viewShowing("from the end-to-end test");
        assertEquals("Neovim: messages", view.getPartName());
        UiTestSupport.requireKeyboardFocus(view.canvasForTests(), TIMEOUT_MS);

        key(view.canvasForTests(), 'w', (char) 0x17, SWT.CTRL); // Ctrl-W
        key(view.canvasForTests(), 'p', 'p', 0);
        waitUntil("the editor has the keyboard back", TIMEOUT_MS, widget::isFocusControl);
    }

    @Test
    void qInTheViewClosesIt() {
        SessionProbe.lua(session, "vim.cmd.echomsg(\"'before q'\") return 0");
        colon("messages");
        NeovimView view = viewShowing("before q");
        UiTestSupport.requireKeyboardFocus(view.canvasForTests(), TIMEOUT_MS);
        key(view.canvasForTests(), 'q', 'q', 0);
        waitUntil("the view closed", TIMEOUT_MS, () -> {
            settle();
            return page().findView(NeovimView.ID) == null;
        });
    }
}
