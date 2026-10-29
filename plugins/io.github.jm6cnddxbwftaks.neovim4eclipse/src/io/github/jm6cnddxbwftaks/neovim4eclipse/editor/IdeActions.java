// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.e4.core.commands.EHandlerService;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeAction;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeKeys;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TabNavigation;

/**
 * Carries out the keys Neovim hands back to the IDE ({@link IdeAction}): {@code gd}, {@code K}, the
 * {@code gr*} keys, {@code gt}, {@code gT}, {@code ZZ}, {@code ZQ}, and {@code Ctrl-O} /
 * {@code Ctrl-I} past Neovim's jumps.
 *
 * <p><b>Commands, not code.</b> "Open Declaration" or "Rename" is different in every kind of editor
 * (Java, C, an LSP editor), so the plug-in does not implement it: it runs the editor's own Eclipse
 * <em>command</em>. Which one, and how it is found, is the one table {@link IdeKeys#COMMANDS}, which
 * this class only interprets ({@link #pick}), the same way for every key:
 * <ul>
 *   <li>{@code gd}: the command bound to F3 (Open Declaration in the Java editor); Neovim's own
 *       {@code gd} where there is none or it cannot run here (the plain Text Editor). The Generic
 *       Editor binds F3 to Open Hyperlink, which Language Server files use for go to definition;
 *       there it counts only when a link is under the caret ({@link HyperlinkProbe}), so a plain
 *       {@code .txt} file behaves the same in the Generic Editor as in the Text Editor.</li>
 *   <li>{@code K}: Show Tooltip Description (F2), the hover information of the text editor.</li>
 *   <li>{@code grn} Rename (the command bound to Alt+Shift+R), {@code gra} Quick Fix (Ctrl+1, on the
 *       selection in visual mode), {@code grr} References (bound to Ctrl+Shift+G), {@code gri} Open
 *       Implementation, {@code grt} Open Declared Type (JDT's Ctrl+click link), {@code gO} Quick
 *       Outline. Where the editor has none: {@code grn: Rename is not available in this editor}.</li>
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
     * @param viewer       the editor's text viewer, or {@code null}; for the hyperlinks of {@code gd} and {@code grt}
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
     * Runs {@code command}: the first of its ways that finds something and runs it; else Neovim's own
     * keys, or the message {@code grn: Rename is not available in this editor}.
     */
    private static void runCommand(String action, IdeKeys.Command command, ITextEditor editor, ITextViewer viewer,
            int caret, Consumer<String> neovimNormal) {
        Choice choice = pick(action, command, editor, viewer, caret, true);
        LOG.fine(() -> IdeKeys.keyOf(action).orElse(action) + ": " + choice);
        switch (choice.kind()) {
            case COMMAND, LINK -> { } // already run
            case NEOVIM -> neovimNormal.accept(choice.value());
            case MESSAGE -> HostCommands.showError(editor, choice.value());
        }
    }

    /**
     * What an action does in an editor right now: run a command ({@code value} its id), open a link
     * ({@code value} the detector's id), run Neovim's own keys, or show a message.
     */
    record Choice(Kind kind, String value) {
        enum Kind { COMMAND, LINK, NEOVIM, MESSAGE }
    }

    /**
     * What the action {@code action} of {@link IdeKeys#COMMANDS} would do here, without running
     * anything; for tests.
     *
     * @throws IllegalArgumentException for an action Eclipse does not run as a command ({@code next_tab})
     */
    static Choice choose(String action, ITextEditor editor, ITextViewer viewer, int caret) {
        IdeKeys.Command command = IdeKeys.COMMANDS.get(action);
        if (command == null) {
            throw new IllegalArgumentException(action + " is no action of IdeKeys.COMMANDS");
        }
        return pick(action, command, editor, viewer, caret, false);
    }

    /**
     * Goes through {@code command}'s ways in order (see {@link IdeKeys}) and returns the first that
     * finds something to run here, running it when {@code run} is set; else Neovim's own keys, or the
     * message. With {@code run}, a way whose command turns out not to run after all (Eclipse says it
     * is not handled or not enabled when it is executed) passes to the next, so {@code gd} still ends
     * in Neovim's {@code gd}. One loop for choosing and running, so the two never disagree, and a link
     * is detected once: {@link HyperlinkProbe#open} detects and opens. UI thread.
     */
    private static Choice pick(String action, IdeKeys.Command command, ITextEditor editor, ITextViewer viewer,
            int caret, boolean run) {
        String key = IdeKeys.keyOf(action).orElse(action);
        for (IdeKeys.Way way : command.ways()) {
            switch (way.kind()) {
                case BOUND_TO, COMMAND -> {
                    String id = way.kind() == IdeKeys.Way.Kind.COMMAND ? way.value() : commandBoundTo(way.value());
                    // Open Hyperlink (the Generic Editor's F3) counts only with a link under the caret.
                    boolean noLinkHere = ITextEditorActionDefinitionIds.OPEN_HYPERLINK.equals(id)
                            && (viewer == null || !HyperlinkProbe.isAt(editor, viewer, caret));
                    if (id != null && !noLinkHere && runsHere(editor, id) && (!run || execute(editor, id, key))) {
                        return new Choice(Choice.Kind.COMMAND, id);
                    }
                }
                case HYPERLINK -> {
                    boolean found = viewer != null && (run ? HyperlinkProbe.open(editor, viewer, caret, way.value())
                            : HyperlinkProbe.isAt(editor, viewer, caret, way.value()));
                    if (found) {
                        return new Choice(Choice.Kind.LINK, way.value());
                    }
                }
            }
        }
        if (!command.neovimKeys().isEmpty()) {
            return new Choice(Choice.Kind.NEOVIM, command.neovimKeys());
        }
        return new Choice(Choice.Kind.MESSAGE, key + ": " + command.label() + " is not available in this editor");
    }

    /**
     * Whether command {@code id} exists in this Eclipse and can run in {@code editor} now, asked of
     * the editor's own Eclipse 4 context: {@link EHandlerService#canExecute} finds the handler the way
     * running the command through the editor's site does, so the answer is that of the run (a page of
     * a multi-page editor, or a handler only that editor's site activates, included). A command bound
     * to a key may still not run here: another plug-in can bind the key for every text editor while
     * only its own editors handle it; a command of JDT does not exist without Java tooling.
     */
    private static boolean runsHere(ITextEditor editor, String id) {
        ICommandService commands = editor.getSite().getService(ICommandService.class);
        IEclipseContext context = editor.getSite().getService(IEclipseContext.class);
        EHandlerService handlers = context == null ? null : context.get(EHandlerService.class);
        Command command = commands == null ? null : commands.getCommand(id);
        if (handlers == null || command == null || !command.isDefined()) {
            return false;
        }
        try {
            return handlers.canExecute(new ParameterizedCommand(command, null));
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Could not ask whether " + id + " can run", e);
            return false;
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
     * @return {@code false} if it did not run after all (not defined, handled or enabled when
     *         executed): the caller then tries its next way; {@code true} if it ran, or failed while
     *         running (logged)
     */
    private static boolean execute(ITextEditor editor, String commandId, String vimKey) {
        try {
            IHandlerService handlers = editor.getSite().getService(IHandlerService.class);
            handlers.executeCommand(commandId, null);
            return true;
        } catch (NotDefinedException | NotHandledException | NotEnabledException e) {
            LOG.fine(() -> vimKey + ": " + commandId + " is not available here: " + e.getMessage());
            return false;
        } catch (Exception e) {
            LOG.log(Level.WARNING, vimKey + ": running " + commandId + " failed", e);
            return true;
        }
    }
}
