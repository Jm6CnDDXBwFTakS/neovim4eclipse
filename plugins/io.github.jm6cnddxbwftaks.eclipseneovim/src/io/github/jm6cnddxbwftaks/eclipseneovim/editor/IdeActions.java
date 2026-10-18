// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.IdeAction;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.TabNavigation;

/**
 * Carries out the keys Neovim hands back to the IDE ({@link IdeAction}): {@code gd}, {@code K},
 * {@code gt}, {@code gT}.
 *
 * <p><b>Commands, not code.</b> "Open Declaration" is different in every kind of editor (Java, C,
 * an LSP editor), so the plug-in does not implement it: it runs the Eclipse <em>command</em> the
 * editor binds to the usual key. Eclipse resolves key bindings by the active contexts (the Java
 * editor activates "Editing Java Source", which binds F3 to Java's Open Declaration), so asking
 * the binding service "what does F3 run here?" finds the right command for any editor.
 * <ul>
 *   <li>{@code gd}: the command bound to F3; if none (the plain Text Editor), Neovim's own
 *       {@code gd} (jump to the word's first use, its "local declaration"). The Generic Editor binds
 *       F3 to Open Hyperlink, which Language Server files use for go to definition, so {@code gd}
 *       runs it there too, the same as F3.</li>
 *   <li>{@code K}: Show Tooltip Description (F2), the hover information of the text editor.</li>
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
     * @param neovimGd runs Neovim's own {@code gd}, bypassing the plug-in's mapping
     */
    static void run(IdeAction action, ITextEditor editor, Runnable neovimGd) {
        switch (action.name()) {
            case IdeAction.DEFINITION -> {
                String bound = commandBoundTo("F3");
                if (bound != null) {
                    execute(editor, bound, "gd");
                } else {
                    neovimGd.run();
                }
            }
            case IdeAction.HOVER -> execute(editor, ITextEditorActionDefinitionIds.SHOW_INFORMATION, "K");
            case IdeAction.NEXT_TAB -> EditorTabs.go(editor, TabNavigation.Action.NEXT, action.count());
            case IdeAction.PREVIOUS_TAB -> EditorTabs.go(editor, TabNavigation.Action.PREVIOUS, action.count());
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
     */
    private static void execute(ITextEditor editor, String commandId, String vimKey) {
        try {
            IHandlerService handlers = editor.getSite().getService(IHandlerService.class);
            handlers.executeCommand(commandId, null);
        } catch (NotHandledException | NotEnabledException e) {
            // No handler in this kind of editor (e.g. Open Declaration in a plain text file).
            HostCommands.showError(editor, vimKey + ": not available in this editor");
            LOG.fine(() -> vimKey + ": " + commandId + " is not available here: " + e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, vimKey + ": running " + commandId + " failed", e);
        }
    }
}
