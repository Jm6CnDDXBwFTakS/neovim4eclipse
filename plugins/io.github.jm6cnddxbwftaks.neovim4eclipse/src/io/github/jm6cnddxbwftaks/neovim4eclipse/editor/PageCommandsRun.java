// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeAction;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageCommands;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TabNavigation;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WindowCommand;

/**
 * Carries out, for a page editor shown from a window of the editing Neovim (the messages page),
 * what {@link PageCommands} decided about a command typed in it: the table is the pure decision,
 * this its Eclipse side. UI thread.
 *
 * <ul>
 *   <li>{@code :q}: the page editor closes (its window with it).</li>
 *   <li>{@code :split}, {@code :vsplit}, {@code :tab split}: a second page editor, its own Neovim
 *       window on the same buffer, in a new tab group beside the page or as a tab of its group.</li>
 *   <li>{@code Ctrl-W} moves, {@code :only}, sizes, {@code :new}, {@code :vsplit b.txt},
 *       {@code gt}: as from a code editor, with the page as the current editor
 *       ({@link EditorSplits.Origin}).</li>
 *   <li>{@code :e b.txt}, {@code :wa}, {@code :qa}, {@code :terminal}: as typed in the code editor
 *       the page came from (the attached one): a file opens in the code's tab group.</li>
 *   <li>{@code :wq}, {@code :e}: refused, with Vim's error over the page.</li>
 * </ul>
 */
final class PageCommandsRun {

    private static final Logger LOG = Logger.getLogger(PageCommandsRun.class.getName());

    private PageCommandsRun() {
    }

    static void run(NeovimPageEditor page, HostCommand command) {
        PageCommands.Decision decision = PageCommands.decide(command);
        LOG.fine(() -> "Page command " + command + ": " + decision);
        switch (decision.action()) {
            case CLOSE_PAGE -> page.getSite().getPage().closeEditor(page, false);
            case SECOND_PAGE -> second(page, command.window());
            case FROM_PAGE -> fromPage(page, command);
            case IN_CODE_GROUP, AS_FROM_CODE -> {
                ITextEditor code = code();
                if (code == null) {
                    error(page, "No code editor to run this from");
                } else {
                    HostCommands.run(command, code);
                }
            }
            case REFUSE -> error(page, decision.error());
        }
    }

    /** A key the plug-in maps for the IDE, typed in the page: only the editor tabs mean anything here. */
    static void action(NeovimPageEditor page, IdeAction action) {
        switch (action.name()) {
            case IdeAction.NEXT_TAB -> EditorTabs.go(page, message -> error(page, message), TabNavigation.Action.NEXT, action.count());
            case IdeAction.PREVIOUS_TAB -> EditorTabs.go(page, message -> error(page, message), TabNavigation.Action.PREVIOUS,
                    action.count());
            default -> LOG.fine(() -> "IDE action " + action + " ignored in a page");
        }
    }

    /** A second page editor of the page's kind, placed as the window command says, on the page's buffer. */
    private static void second(NeovimPageEditor page, WindowCommand window) {
        IWorkbenchPage workbench = page.getSite().getPage();
        try {
            IEditorPart opened = workbench.openEditor(NeovimPageInput.another(page.kind()), NeovimPageEditor.ID, false);
            if (window != null && window.action() == WindowCommand.Action.SPLIT && window.where() != null) {
                EditorSplits.intoNewStack(page, opened, EditorSplits.side(window.where()));
            } else {
                EditorSplits.intoStackOf(page, opened); // :tab split: a tab of the page's group
            }
            workbench.activate(opened);
            opened.setFocus();
            WindowPane pane = page.windowPane();
            int[] colors = pane == null ? new int[] { 0x000000, 0xFFFFFF } : pane.colors();
            if (opened instanceof NeovimPageEditor half) {
                half.showSameAs(page, colors[0], colors[1]);
            }
        } catch (PartInitException e) {
            LOG.log(Level.WARNING, "Could not open a second " + page.getPartName() + " editor", e);
            error(page, "Cannot split the page: " + e.getMessage());
        }
    }

    private static void fromPage(NeovimPageEditor page, HostCommand command) {
        switch (command.kind()) {
            case WINDOW -> EditorSplits.run(origin(page), command.window());
            case TAB_NEXT -> EditorTabs.go(page, m -> error(page, m), TabNavigation.Action.NEXT, command.count());
            case TAB_PREVIOUS -> EditorTabs.go(page, m -> error(page, m), TabNavigation.Action.PREVIOUS, command.count());
            case TAB_FIRST -> EditorTabs.go(page, m -> error(page, m), TabNavigation.Action.FIRST, 0);
            case TAB_LAST -> EditorTabs.go(page, m -> error(page, m), TabNavigation.Action.LAST, 0);
            default -> LOG.fine(() -> "Not a command done from the page: " + command);
        }
    }

    /** The page as the editor a window command was typed in; the code it came from for a project. */
    private static EditorSplits.Origin origin(NeovimPageEditor page) {
        WindowPane pane = page.windowPane();
        return new EditorSplits.Origin() {
            @Override public IEditorPart part() { return page; }
            @Override public void error(String message) { PageCommandsRun.error(page, message); }
            @Override public Point cursor() { return pane == null ? new Point(0, 0) : pane.cursorOnScreen(); }
            @Override public Canvas canvas() { return pane == null ? null : pane.canvas(); }

            @Override
            public void newUntitled() {
                ITextEditor code = code();
                if (code == null) {
                    PageCommandsRun.error(page, "No code editor to take the project from");
                } else {
                    EclipseFileCommands.newUntitled(code);
                }
            }

            @Override
            public Path folder() {
                ITextEditor code = code();
                return code == null ? Path.of(System.getProperty("user.home")) : EclipseFileCommands.windowFolder(code);
            }

            @Override public void closeSelf(boolean force) { page.getSite().getPage().closeEditor(page, false); }
            @Override public String toString() { return page.getPartName(); }
        };
    }

    /** The code editor the page came from: the attached one, or {@code null}. */
    private static ITextEditor code() {
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        return controller == null || controller.isClosed() ? null : controller.getEditor();
    }

    private static void error(NeovimPageEditor page, String message) {
        WindowPane pane = page.windowPane();
        if (pane != null) {
            pane.showMessage(message, true);
        }
    }
}
