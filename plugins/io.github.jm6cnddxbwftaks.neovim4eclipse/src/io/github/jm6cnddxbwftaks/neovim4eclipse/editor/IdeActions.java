// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeAction;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TabNavigation;

/**
 * Carries out the keys Neovim hands back to the IDE ({@link IdeAction}): {@code gd}, {@code K},
 * {@code gt}, {@code gT}, {@code ZZ}, {@code ZQ}, and {@code Ctrl-O} / {@code Ctrl-I} past Neovim's jumps.
 *
 * <p><b>Commands, not code.</b> "Open Declaration" is different in every kind of editor (Java, C,
 * an LSP editor), so the plug-in does not implement it: it runs the Eclipse <em>command</em> the
 * editor binds to the usual key. Eclipse resolves key bindings by the active contexts (the Java
 * editor activates "Editing Java Source", which binds F3 to Java's Open Declaration), so asking
 * the binding service "what does F3 run here?" finds the right command for any editor.
 * <ul>
 *   <li>{@code gd}: goes to a definition when Eclipse knows one, else it is Neovim's own {@code gd}
 *       (jump to the word's first use, its "local declaration"). That is: the command bound to F3
 *       (Open Declaration in the Java editor); Neovim's {@code gd} if there is none or it cannot run
 *       in this editor (the plain Text Editor). The Generic Editor binds F3 to Open Hyperlink, which
 *       Language Server files use for go to definition; there {@code gd} runs it only when a link is
 *       under the caret ({@link HyperlinkProbe}), so a plain {@code .txt} file behaves the same in the
 *       Generic Editor as in the Text Editor.</li>
 *   <li>{@code K}: Show Tooltip Description (F2), the hover information of the text editor.</li>
 *   <li>{@code Ctrl-O} / {@code Ctrl-I} past the end of Neovim's jump history: Eclipse's Backward
 *       History and Forward History (Alt+Left, Alt+Right), stepping over the editor switches the
 *       plug-in made itself, so that {@code Ctrl-I} retraces {@code Ctrl-O}
 *       ({@link NavigationHistorySteps}).</li>
 * </ul>
 *
 * <p>UI thread only.
 */
final class IdeActions {

    private static final Logger LOG = Logger.getLogger(IdeActions.class.getName());

    private IdeActions() {
    }

    /**
     * Runs {@code action} in {@code editor}; an unknown action name is logged and ignored.
     *
     * @param viewer   the editor's text viewer, or {@code null}; for the hyperlink check of {@code gd}
     * @param caret    the caret as a document offset, for the same check
     * @param neovimGd runs Neovim's own {@code gd}, bypassing the plug-in's mapping
     * @param trail    Neovim's trail of {@code Ctrl-O} steps, told of Back steps that went nowhere
     */
    static void run(IdeAction action, ITextEditor editor, ITextViewer viewer, int caret, Runnable neovimGd,
            NavigationHistorySteps.Trail trail) {
        switch (action.name()) {
            case IdeAction.DEFINITION -> {
                String bound = commandBoundTo("F3");
                boolean noLinkHere = ITextEditorActionDefinitionIds.OPEN_HYPERLINK.equals(bound)
                        && (viewer == null || !HyperlinkProbe.isAt(editor, viewer, caret));
                // A command bound to F3 may still not run in this editor: another plug-in can bind F3
                // for every text editor while only its own editors handle it. gd is then Neovim's own.
                if (bound == null || noLinkHere || !execute(editor, bound, "gd")) {
                    neovimGd.run();
                }
            }
            case IdeAction.HOVER -> {
                if (!execute(editor, ITextEditorActionDefinitionIds.SHOW_INFORMATION, "K")) {
                    HostCommands.showError(editor, "K: not available in this editor");
                }
            }
            case IdeAction.NEXT_TAB -> EditorTabs.go(editor, TabNavigation.Action.NEXT, action.count());
            case IdeAction.PREVIOUS_TAB -> EditorTabs.go(editor, TabNavigation.Action.PREVIOUS, action.count());
            case IdeAction.HISTORY_BACK -> NavigationHistorySteps.back(editor.getSite().getPage(), action.count(),
                    action.step(), trail);
            case IdeAction.HISTORY_FORWARD -> NavigationHistorySteps.forward(editor.getSite().getPage(), action.count(),
                    action.step());
            case IdeAction.SAVE_AND_CLOSE -> EclipseFileCommands.zz(editor);
            case IdeAction.CLOSE -> EclipseFileCommands.zq(editor);
            default -> LOG.fine(() -> "Unknown IDE action ignored: " + action);
        }
    }

    /** The id of the command the workbench would run for {@code key} right now, or {@code null}. */
    private static String commandBoundTo(String key) {
        try {
            return KeyBindings.commandIdFor(key);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Key binding lookup for " + key + " failed", e);
            return null;
        }
    }

    /**
     * Executes a command through the editor's handler service, so that the editor's own handler
     * (for example Java's Open Declaration) is the one that runs.
     *
     * @return {@code false} if the command has no enabled handler in this editor (e.g. Open
     *         Declaration in a plain text file); {@code true} if it ran, or failed while running
     *         (logged)
     */
    private static boolean execute(ITextEditor editor, String commandId, String vimKey) {
        try {
            IHandlerService handlers = editor.getSite().getService(IHandlerService.class);
            handlers.executeCommand(commandId, null);
            return true;
        } catch (NotHandledException | NotEnabledException e) {
            LOG.fine(() -> vimKey + ": " + commandId + " is not available here: " + e.getMessage());
            return false;
        } catch (Exception e) {
            LOG.log(Level.WARNING, vimKey + ": running " + commandId + " failed", e);
            return true;
        }
    }
}
