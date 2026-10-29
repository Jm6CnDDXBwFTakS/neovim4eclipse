// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeAction;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeKeys;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TabNavigation;

/**
 * Carries out the keys Neovim hands back to the IDE ({@link IdeAction}): {@code gd}, {@code K},
 * {@code gt}, {@code gT}, {@code ZZ}, {@code ZQ}, and {@code Ctrl-O} / {@code Ctrl-I} past Neovim's jumps.
 *
 * <p><b>Commands, not code.</b> "Open Declaration" is different in every kind of editor
 * (Java, C, an LSP editor), so the plug-in does not implement it: it runs the editor's own Eclipse
 * <em>command</em>. Which one, and how it is found, is the one table {@link IdeKeys#COMMANDS}, which
 * this class only interprets ({@link #runCommand}), the same way for every key:
 * <ul>
 *   <li>{@code gd}: the command bound to F3 (Open Declaration in the Java editor); Neovim's own
 *       {@code gd} where there is none or it cannot run here (the plain Text Editor). The Generic
 *       Editor binds F3 to Open Hyperlink, which Language Server files use for go to definition;
 *       there it counts only when a link is under the caret ({@link HyperlinkProbe}), so a plain
 *       {@code .txt} file behaves the same in the Generic Editor as in the Text Editor.</li>
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
     * @param viewer       the editor's text viewer, or {@code null}; for the hyperlink check of {@code gd}
     * @param caret        the caret as a document offset, for the same
     * @param neovimNormal runs Neovim's own keys, bypassing the plug-in's mappings ({@code gd}'s fallback)
     * @param trail        Neovim's trail of {@code Ctrl-O} steps, told of Back steps that went nowhere
     */
    static void run(IdeAction action, ITextEditor editor, ITextViewer viewer, int caret, Consumer<String> neovimNormal,
            NavigationHistorySteps.Trail trail) {
        IdeKeys.Command command = IdeKeys.COMMANDS.get(action.name());
        if (command != null) {
            runCommand(action.name(), command, editor, viewer, caret, neovimNormal);
            return;
        }
        switch (action.name()) {
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

    /**
     * Runs what {@link #choose} picks for {@code command}: the command, Neovim's own keys,
     * or the message {@code K: Show Tooltip Description is not available in this editor}.
     */
    private static void runCommand(String action, IdeKeys.Command command, ITextEditor editor, ITextViewer viewer,
            int caret, Consumer<String> neovimNormal) {
        Choice choice = choose(action, command, editor, viewer, caret);
        String key = IdeKeys.keyOf(action).orElse(action);
        LOG.fine(() -> key + ": " + choice);
        switch (choice.kind()) {
            case COMMAND -> execute(editor, choice.value(), key);
            case NEOVIM -> neovimNormal.accept(choice.value());
            case MESSAGE -> HostCommands.showError(editor, choice.value());
        }
    }

    /**
     * What an action does in an editor right now: run a command ({@code value} its id), run Neovim's
     * own keys, or show a message.
     */
    record Choice(Kind kind, String value) {
        enum Kind { COMMAND, NEOVIM, MESSAGE }
    }

    /** {@link #choose} for an action of {@link IdeKeys#COMMANDS} by name; for tests, which check the choice without running it. */
    static Choice choose(String action, ITextEditor editor, ITextViewer viewer, int caret) {
        return choose(action, IdeKeys.COMMANDS.get(action), editor, viewer, caret);
    }

    /**
     * The first of {@code command}'s ways that finds something to run here (see {@link IdeKeys}); else
     * Neovim's own keys, or the message. UI thread.
     */
    private static Choice choose(String action, IdeKeys.Command command, ITextEditor editor, ITextViewer viewer,
            int caret) {
        for (IdeKeys.Way way : command.ways()) {
            switch (way.kind()) {
                case BOUND_TO -> {
                    String bound = commandBoundTo(way.value());
                    // Open Hyperlink (the Generic Editor's F3) counts only with a link under the caret.
                    boolean noLinkHere = ITextEditorActionDefinitionIds.OPEN_HYPERLINK.equals(bound)
                            && (viewer == null || !HyperlinkProbe.isAt(editor, viewer, caret));
                    if (bound != null && !noLinkHere && runsHere(editor, bound)) {
                        return new Choice(Choice.Kind.COMMAND, bound);
                    }
                }
                case COMMAND -> {
                    if (runsHere(editor, way.value())) {
                        return new Choice(Choice.Kind.COMMAND, way.value());
                    }
                }
            }
        }
        if (!command.neovimKeys().isEmpty()) {
            return new Choice(Choice.Kind.NEOVIM, command.neovimKeys());
        }
        return new Choice(Choice.Kind.MESSAGE, unavailable(IdeKeys.keyOf(action).orElse(action), command));
    }

    private static String unavailable(String key, IdeKeys.Command command) {
        return key + ": " + command.label() + " is not available in this editor";
    }

    /**
     * Whether command {@code id} exists in this Eclipse and has an enabled handler in {@code editor}
     * now. A command bound to a key may still not run in this editor: another plug-in can bind the
     * key for every text editor while only its own editors handle it; a command of JDT does not
     * exist without Java tooling.
     */
    private static boolean runsHere(ITextEditor editor, String id) {
        ICommandService commands = editor.getSite().getService(ICommandService.class);
        Command command = commands == null ? null : commands.getCommand(id);
        return command != null && command.isDefined() && command.isHandled() && command.isEnabled();
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
     * (for example Java's Open Declaration) is the one that runs. {@link #choose} has checked that
     * it can run here; should that have changed meanwhile, or the command fail, it is logged.
     */
    private static void execute(ITextEditor editor, String commandId, String vimKey) {
        try {
            IHandlerService handlers = editor.getSite().getService(IHandlerService.class);
            handlers.executeCommand(commandId, null);
        } catch (NotDefinedException | NotHandledException | NotEnabledException e) {
            LOG.fine(() -> vimKey + ": " + commandId + " is not available here: " + e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, vimKey + ": running " + commandId + " failed", e);
        }
    }
}
