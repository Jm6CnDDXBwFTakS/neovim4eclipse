// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

/**
 * Eclipse's content assist as the pictures use it ({@code DemoRecordingTest},
 * {@code ScreenshotCaptureTest}): Ctrl+Space pressed in insert mode, and the list of proposals it
 * opens. Example: after {@code names.si}, {@link #open} lists {@code size()}, and
 * {@link #proposals} finds that list's table once it shows.
 */
final class ContentAssist {

    private ContentAssist() {
    }

    /**
     * Presses Ctrl+Space in {@code text}, as a user does in insert mode. The key event runs the
     * workbench's key bindings, whose content assist command opens the list (see
     * {@link UiTestSupport#m1Key}); the plug-in leaves the key to Eclipse while Eclipse types.
     */
    static void open(StyledText text) {
        Event space = new Event();
        space.keyCode = ' ';
        space.character = ' ';
        space.stateMask = SWT.CTRL;
        space.doit = true;
        text.notifyListeners(SWT.KeyDown, space);
    }

    /**
     * The table of the open proposal list, or {@code null} while none shows: content assist opens
     * its list in a shell of its own, beside {@code window}.
     */
    static Table proposals(Shell window) {
        for (Shell shell : Display.getCurrent().getShells()) {
            if (shell != window && shell.isVisible()) {
                Table table = tableIn(shell);
                if (table != null && table.getItemCount() > 0) {
                    return table;
                }
            }
        }
        return null;
    }

    private static Table tableIn(Composite parent) {
        for (Control child : parent.getChildren()) {
            if (child instanceof Table table) {
                return table;
            }
            if (child instanceof Composite composite) {
                Table found = tableIn(composite);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
