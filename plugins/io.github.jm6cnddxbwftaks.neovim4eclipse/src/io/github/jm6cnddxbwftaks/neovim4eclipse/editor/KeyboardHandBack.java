// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Gives the keyboard back to the part Eclipse brought to the front after a page editor closed
 * ({@link NeovimPageEditor}), whenever it ends up with no control at all, until the user's next
 * mouse click in Eclipse.
 *
 * <p>Why: the keyboard was in the page editor's screen, which the close disposes. Eclipse brings
 * the part the user came from to the front, but does not always leave the keyboard there, and a
 * later step of the close can take it away again: the help editor opens in a stack of its own
 * below the code, and once it is gone Eclipse's {@code CleanupAddon} removes the empty stack, in
 * an {@code asyncExec} of its own, moving the code's stack to the parent that is left
 * ({@code Control.setParent}). On GTK that move takes the keyboard from the text it holds, with a
 * FocusOut and nothing after it. Example: {@code :help} typed in test.txt, then {@code q} in the
 * help editor; test.txt is the active editor again, but the next key goes nowhere until a click.
 *
 * <p>So it does not act once at a guessed moment: it checks right after the close, and again after
 * each FocusOut, from the state then. It acts only when no control has the keyboard while this
 * workbench window is the active one, and then gives it to the active part, the one Eclipse chose;
 * it never picks or opens an editor, and never takes the keyboard from a part, a view or a dialog
 * that has it, nor from another program.
 *
 * <p>How long: until the next mouse button pressed in Eclipse (from then on, where the keyboard
 * goes is the user's choice), or until the window closes. A press is seen by a display filter,
 * with one exception: a left click that Neovim handles in the code's text, which
 * {@link NeovimMouse}'s own filter, registered before this one, swallows (no later filter sees it);
 * its owner, {@code NeovimEditorController}, reports that press here ({@link #userClicked}). Not until the next key: keys typed fast
 * after {@code :q}, before Eclipse's cleanup has run, reach the code already, and the move that
 * follows would still leave the keyboard nowhere. So for a user who only types, the watch lasts
 * long after the close, and a later key that leaves the keyboard nowhere (a command that closes
 * or moves the part that had it, such as {@code :q} in one half of a split) gets the same hand-back,
 * to the active part. One watch per workbench window: a second close (Restart Neovim closes every
 * page editor) keeps the one already running.
 *
 * <p>UI thread.
 */
final class KeyboardHandBack {

    /** The running watch of each workbench window, by its shell. UI thread only. */
    private static final Map<Shell, KeyboardHandBack> WATCHES = new HashMap<>();

    private final IWorkbenchWindow window;
    private final Shell shell;
    private final Display display;
    /** After each FocusOut, the check, once that event and what caused it are done. */
    private final Listener onFocusOut;
    /** The user's next mouse button ends the watch. */
    private final Listener onClick;
    private final DisposeListener onWindowClosed;
    private boolean stopped;

    private KeyboardHandBack(IWorkbenchWindow window, Shell shell) {
        this.window = window;
        this.shell = shell;
        this.display = shell.getDisplay();
        this.onFocusOut = event -> display.asyncExec(this::check);
        this.onClick = event -> stop();
        this.onWindowClosed = event -> stop();
    }

    /**
     * Watches {@code page}'s window, for a page editor of it that is closing: checks once the close
     * is done, and starts a watch unless the window has one; nothing if the window is already gone.
     */
    static void afterClose(IWorkbenchPage page) {
        IWorkbenchWindow window = page.getWorkbenchWindow();
        Shell shell = window.getShell();
        if (shell == null || shell.isDisposed() || PlatformUI.getWorkbench().isClosing()) {
            return;
        }
        KeyboardHandBack watch = WATCHES.get(shell);
        if (watch == null) {
            watch = new KeyboardHandBack(window, shell);
            watch.start();
        }
        // Once the close itself is done: the keyboard may already be nowhere, with no FocusOut to come.
        shell.getDisplay().asyncExec(watch::check);
    }

    private void start() {
        WATCHES.put(shell, this);
        display.addFilter(SWT.FocusOut, onFocusOut);
        display.addFilter(SWT.MouseDown, onClick);
        shell.addDisposeListener(onWindowClosed);
    }

    /** Gives the keyboard to the active part if no control has it while this window is active. */
    private void check() {
        if (stopped) {
            return;
        }
        if (shell.isDisposed() || PlatformUI.getWorkbench().isClosing()) {
            stop();
            return;
        }
        if (display.getFocusControl() != null || display.getActiveShell() != shell) {
            return; // a control has it, or another window or program does: not ours to move
        }
        IWorkbenchPage page = window.getActivePage(); // read now, not kept from the close
        IWorkbenchPart front = page == null ? null : page.getActivePart();
        if (front != null) {
            front.setFocus();
        }
    }

    /**
     * The user pressed a mouse button in a way no filter of this class sees (a click
     * {@link NeovimMouse} swallowed): ends every watch, as the filter would have.
     */
    static void userClicked() {
        for (KeyboardHandBack watch : List.copyOf(WATCHES.values())) {
            watch.stop();
        }
    }

    /** Whether {@code shell}'s window has a watch running. For the UI tests. */
    static boolean watchingForTests(Shell shell) {
        return WATCHES.containsKey(shell);
    }

    /**
     * Ends every watch. For the UI tests: a watch lasts until the next click, which a test may never
     * make, and one left running from an earlier test would hide a later test's lost keyboard.
     */
    static void stopAllForTests() {
        userClicked();
    }

    private void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        WATCHES.remove(shell, this);
        display.removeFilter(SWT.FocusOut, onFocusOut);
        display.removeFilter(SWT.MouseDown, onClick);
        if (!shell.isDisposed()) {
            shell.removeDisposeListener(onWindowClosed);
        }
    }
}
