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
 * {@link IdeKeys#pick} goes through the same way for every key; this class is its Eclipse side
 * ({@link EclipseEditor}):
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

    /** Runs {@code command} in {@code editor} ({@link IdeKeys#pick}); then Neovim's own keys, or the message. */
    private static void runCommand(String action, IdeKeys.Command command, ITextEditor editor, ITextViewer viewer,
            int caret, Consumer<String> neovimNormal) {
        IdeKeys.Choice choice = IdeKeys.pick(action, command, new EclipseEditor(editor, viewer, caret, action, command));
        LOG.fine(() -> IdeKeys.keyOf(action).orElse(action) + ": " + choice);
        switch (choice.kind()) {
            case COMMAND, LINK -> { } // already run
            case NEOVIM -> neovimNormal.accept(choice.value());
            case MESSAGE -> HostCommands.showError(editor, choice.value());
        }
    }

    /**
     * What the action {@code action} of {@link IdeKeys#COMMANDS} would do here, without running
     * anything ({@link IdeKeys#dryRun}); for tests.
     *
     * @throws IllegalArgumentException for an action Eclipse does not run as a command ({@code next_tab})
     */
    static IdeKeys.Choice choose(String action, ITextEditor editor, ITextViewer viewer, int caret) {
        IdeKeys.Command command = IdeKeys.COMMANDS.get(action);
        if (command == null) {
            throw new IllegalArgumentException(action + " is no action of IdeKeys.COMMANDS");
        }
        return IdeKeys.pick(action, command, IdeKeys.dryRun(new EclipseEditor(editor, viewer, caret, action, command)));
    }

    /**
     * The Eclipse side of {@link IdeKeys.Editor}: one editor, its viewer and caret, for one action (whose
     * key and label go into a failure's message). Thin glue: the decisions are {@link IdeKeys#pick}'s.
     * UI thread.
     *
     * @param viewer the editor's text viewer, or {@code null} (then no link is found)
     */
    record EclipseEditor(ITextEditor editor, ITextViewer viewer, int caret, String action, IdeKeys.Command command)
            implements IdeKeys.Editor {

        private String key() {
            return IdeKeys.keyOf(action).orElse(action);
        }

        /** Shows a failure the way Vim shows an error: {@code grt: Open Declared Type failed: ...}. */
        private void showFailure(Exception e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            HostCommands.showError(editor, key() + ": " + command.label() + " failed: " + why);
        }

        @Override
        public String commandBoundTo(String eclipseKey) {
            try {
                return KeyBindings.commandIdFor(eclipseKey);
            } catch (Exception e) {
                LOG.log(Level.FINE, "Key binding lookup for " + eclipseKey + " failed", e);
                return null;
            }
        }

        /**
         * Asked of the editor's own Eclipse 4 context: {@link EHandlerService#canExecute} finds the
         * handler the way running the command through the editor's site does, so the answer is that of
         * the run (a multi-page editor's text page included; there the outer editor's context was found to
         * give the same answer, 2026-10-29, as Eclipse looks up the active part's innermost handler). A
         * command bound to a key may still not run here: another plug-in can bind the key for every
         * text editor while only its own editors handle it; a command of JDT does not exist without
         * Java tooling.
         */
        @Override
        public boolean runsHere(String id) {
            ICommandService commands = editor.getSite().getService(ICommandService.class);
            IEclipseContext context = editor.getSite().getService(IEclipseContext.class);
            EHandlerService handlers = context == null ? null : context.get(EHandlerService.class);
            Command eclipseCommand = commands == null ? null : commands.getCommand(id);
            if (handlers == null || eclipseCommand == null || !eclipseCommand.isDefined()) {
                return false;
            }
            try {
                return handlers.canExecute(new ParameterizedCommand(eclipseCommand, null));
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Could not ask whether " + id + " can run", e);
                return false;
            }
        }

        /**
         * Through the editor's handler service, so that the editor's own handler (Java's Open
         * Declaration) runs. A command that fails while running is shown in the message line
         * ({@code grr: References failed: ...}) and counts as run.
         */
        @Override
        public boolean execute(String id) {
            try {
                editor.getSite().getService(IHandlerService.class).executeCommand(id, null);
                return true;
            } catch (NotDefinedException | NotHandledException | NotEnabledException e) {
                LOG.fine(() -> key() + ": " + id + " is not available here: " + e.getMessage());
                return false;
            } catch (Exception e) {
                LOG.log(Level.WARNING, key() + ": running " + id + " failed", e);
                showFailure(e);
                return true;
            }
        }

        @Override
        public boolean anyLinkAt() {
            return viewer != null && HyperlinkProbe.isAt(editor, viewer, caret);
        }

        @Override
        public boolean linkAt(String detectorId) {
            return viewer != null && HyperlinkProbe.isAt(editor, viewer, caret, detectorId);
        }

        /** A link that fails to open (JDT cannot open the type) is shown in the message line and counts as opened. */
        @Override
        public boolean openLink(String detectorId) {
            try {
                return viewer != null && HyperlinkProbe.open(editor, viewer, caret, detectorId);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, key() + ": opening the link of " + detectorId + " failed", e);
                showFailure(e);
                return true;
            }
        }
    }
}
