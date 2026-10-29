// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.INavigationHistory;
import org.eclipse.ui.INavigationLocation;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HistorySteps;

/**
 * Eclipse's Back and Forward for {@code Ctrl-O} and {@code Ctrl-I} past the end of a Neovim
 * window's jump history, so that {@code Ctrl-I} retraces {@code Ctrl-O}. The decisions (which
 * entries to step over, where Forward goes) are {@link HistorySteps}; its class comment has the
 * why and an example. This class is the Eclipse half:
 * <ul>
 *   <li>{@link #activateForNeovim}: an editor switch the plug-in makes because Neovim moved into
 *       another editor's file. Eclipse records it in its history as any activation; when the move
 *       was a Neovim jump, the entry is noted as the plug-in's. Eclipse adds the entry a moment
 *       later ({@code markEditor} posts it to the UI thread), so the note is posted after it.</li>
 *   <li>{@link #back} and {@link #forward}: the steps, through the workbench window's handler
 *       service (Backward History, Forward History), since the first step may activate another
 *       editor and the old editor's site would no longer be the command's context. Posted once more
 *       to the UI thread first: a key held down can arrive before the history entry of the switch
 *       the previous key made, and the steps must count it.</li>
 *   <li>{@link #afterSwitch}: after any editor activation, whether it was the user's own (a tab
 *       click, {@code F3}); then the remembered Back steps are forgotten, and so is Neovim's trail.</li>
 * </ul>
 *
 * <p>Only public API: {@link INavigationHistory} gives the entries and the current one, but no way
 * to leave an entry out or to go to one directly; hence the steps. One {@link HistorySteps} per
 * workbench page, as Eclipse keeps one history per page. UI thread only.
 */
final class NavigationHistorySteps {

    private static final Logger LOG = Logger.getLogger(NavigationHistorySteps.class.getName());

    /** What Neovim's trail of {@code Ctrl-O} steps is told (LuaScripts.SETUP). */
    interface Trail {
        /** Back steps {@code first} .. {@code first + steps - 1} went nowhere. */
        void wentNowhere(long first, int steps);

        /** The user switched editors: forget the trail. */
        void forget();
    }

    /** The trail of {@code session}'s Neovim. */
    static Trail trailOf(NeovimSession session) {
        return new Trail() {
            @Override
            public void wentNowhere(long first, int steps) {
                session.historyBacksWentNowhereAsync(first, steps);
            }

            @Override
            public void forget() {
                session.historyForgetAsync();
            }
        };
    }

    /**
     * An editor and its selection, to come back to when Eclipse's Forward no longer can. The editor
     * is held weakly: a closed editor is not kept alive by a remembered Back (and the page, which
     * keys {@link #STEPS} weakly, is not kept alive through it either).
     */
    record Place(WeakReference<IEditorPart> editor, int offset, int length) {
        boolean sameAs(Place other) {
            return other != null && editor.get() != null && editor.get() == other.editor.get()
                    && offset == other.offset && length == other.length;
        }
    }

    private static final Map<IWorkbenchPage, HistorySteps<INavigationLocation, Place>> STEPS = new WeakHashMap<>();

    private NavigationHistorySteps() {
    }

    /**
     * Activates {@code target} because Neovim moved into its file.
     *
     * @param neovimJump whether the move is in Neovim's jump history; only then is Eclipse's entry
     *                   for the switch one for Back to step over. Either way it is the plug-in's,
     *                   not the user's: it does not forget the remembered Back steps
     */
    static void activateForNeovim(IWorkbenchPage page, IEditorPart target, boolean neovimJump) {
        INavigationHistory history = page.getNavigationHistory();
        INavigationLocation before = history.getCurrentLocation();
        page.activate(target);
        Display display = Display.getCurrent();
        if (display != null) {
            // After Eclipse's own asyncExec that adds the entry (NavigationHistory.markEditor).
            // Unchanged: the switch merged into the current entry, or there was none (same editor).
            display.asyncExec(() -> {
                INavigationLocation now = history.getCurrentLocation();
                if (now != before) {
                    if (neovimJump) {
                        steps(page).markOwn(now);
                    } else {
                        steps(page).markKnown(now);
                    }
                }
            });
        }
    }

    /**
     * An editor was activated, by anyone. Checked twice deferred: after Eclipse's entry for it
     * (posted during the activation) and after {@link #activateForNeovim}'s note (posted after
     * it), so that only an entry the user made counts as one. Then the remembered Back steps and
     * Neovim's trail are forgotten, and {@code Ctrl-I} works as without them.
     */
    static void afterSwitch(IWorkbenchPage page, Trail trail) {
        later(() -> later(() -> {
            if (steps(page).userMovedOn(entries(page.getNavigationHistory()))) {
                trail.forget();
            }
        }));
    }

    /**
     * {@code Ctrl-O} at the start of the window's jump history: Back, past the plug-in's entries,
     * one press per step of the count, each remembered for {@code Ctrl-I} under its step id.
     *
     * @param first the first step's id (Neovim's; the others follow)
     * @param trail told of the steps that went nowhere (the start of the history)
     */
    static void back(IWorkbenchPage page, int count, long first, Trail trail) {
        later(() -> {
            int presses = Math.max(1, count);
            for (int press = 0; press < presses; press++) {
                if (!backOnce(page, first + press)) {
                    LOG.fine("Ctrl-O: no older place in Eclipse's navigation history");
                    trail.wentNowhere(first + press, presses - press);
                    return;
                }
            }
        });
    }

    /** One Back step; {@code false} if it moved nothing the user can see (the history's start). */
    private static boolean backOnce(IWorkbenchPage page, long id) {
        INavigationHistory history = page.getNavigationHistory();
        HistorySteps<INavigationLocation, Place> steps = steps(page);
        INavigationLocation current = history.getCurrentLocation();
        int n = steps.backSteps(entries(history), current);
        if (n == 0) {
            return false;
        }
        Place start = placeOf(page.getActiveEditor());
        steps.wentBack(id, entries(history), current, start);
        // A Back that changes nothing the user can see is no step: the entry is the place the
        // last Neovim jump already brought the editor to. Example: click test.txt's tab, :e
        // other.txt, :e #, Ctrl-O: the jump back to test.txt is Neovim's, and Back's first entry
        // is the tab click, test.txt again, with the same caret. Each round goes back at least one
        // entry, so this ends; at the history's start the step went nowhere.
        while (run(page, IWorkbenchCommandConstants.NAVIGATE_BACKWARD_HISTORY, n, "Ctrl-O")
                && start != null && start.sameAs(placeOf(page.getActiveEditor()))) {
            n = steps.backSteps(entries(history), history.getCurrentLocation());
            if (n == 0) {
                break;
            }
        }
        if (start != null && start.sameAs(placeOf(page.getActiveEditor()))) {
            steps.wentNowhere(id);
            return false;
        }
        return true;
    }

    /**
     * {@code Ctrl-I} at the end of the window's jump history, or with a Back step to undo.
     *
     * @param oldest the id of the oldest Back step to undo (Neovim's trail), or 0 for Eclipse's
     *               plain Forward
     */
    static void forward(IWorkbenchPage page, int count, long oldest) {
        later(() -> {
            INavigationHistory history = page.getNavigationHistory();
            HistorySteps<INavigationLocation, Place> steps = steps(page);
            if (oldest <= 0) {
                run(page, IWorkbenchCommandConstants.NAVIGATE_FORWARD_HISTORY,
                        steps.plainForwardSteps(entries(history), history.getCurrentLocation(), count), "Ctrl-I");
                return;
            }
            switch (steps.forward(oldest, entries(history), history.getCurrentLocation())) {
                case HistorySteps.Steps<Place> s -> run(page, IWorkbenchCommandConstants.NAVIGATE_FORWARD_HISTORY,
                        s.steps(), "Ctrl-I");
                case HistorySteps.Restore<Place> r -> restore(page, r.state(), steps);
            }
        });
    }

    /**
     * Shows {@code place} again: Eclipse's history lost it (a later switch of the plug-in's
     * deleted it as a forward entry). If its editor was closed meanwhile, nothing happens, and the
     * remembered Back steps are forgotten.
     */
    private static void restore(IWorkbenchPage page, Place place, HistorySteps<INavigationLocation, Place> steps) {
        IEditorPart editor = place == null ? null : place.editor().get();
        if (editor == null || !isOpen(page, editor)) {
            steps.forget();
            LOG.fine("Ctrl-I: the editor a Back left is closed");
            return;
        }
        // Not a Neovim jump: Eclipse's entry for it is the only record of this place now.
        activateForNeovim(page, editor, false);
        if (place.offset() >= 0 && editor instanceof ITextEditor text && !place.sameAs(placeOf(text))) {
            text.selectAndReveal(place.offset(), place.length()); // a Back inside one editor moved its caret
        }
    }

    /** The editor and its text selection ({@code -1} for an editor without one), or {@code null}. */
    private static Place placeOf(IEditorPart editor) {
        if (editor == null) {
            return null;
        }
        ISelection selection = editor.getSite().getSelectionProvider() == null ? null
                : editor.getSite().getSelectionProvider().getSelection();
        WeakReference<IEditorPart> ref = new WeakReference<>(editor);
        return selection instanceof ITextSelection text ? new Place(ref, text.getOffset(), text.getLength())
                : new Place(ref, -1, 0);
    }

    private static boolean isOpen(IWorkbenchPage page, IEditorPart editor) {
        for (IEditorReference ref : page.getEditorReferences()) {
            if (ref.getEditor(false) == editor) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs {@code commandId} {@code times} times; stops early if Eclipse disables it (the history's end).
     *
     * @return whether every step ran
     */
    private static boolean run(IWorkbenchPage page, String commandId, int times, String vimKey) {
        IHandlerService handlers = page.getWorkbenchWindow().getService(IHandlerService.class);
        for (int i = 0; i < times; i++) {
            try {
                handlers.executeCommand(commandId, null);
            } catch (NotHandledException | NotEnabledException e) {
                LOG.fine(() -> vimKey + ": no further entry in Eclipse's navigation history");
                return false;
            } catch (Exception e) {
                LOG.log(Level.WARNING, vimKey + ": running " + commandId + " failed", e);
                return false;
            }
        }
        return true;
    }

    private static List<INavigationLocation> entries(INavigationHistory history) {
        return Arrays.asList(history.getLocations());
    }

    private static HistorySteps<INavigationLocation, Place> steps(IWorkbenchPage page) {
        return STEPS.computeIfAbsent(page, p -> new HistorySteps<>());
    }

    /** Runs {@code work} after what is already queued on the UI thread (see the class comment). */
    private static void later(Runnable work) {
        Display display = Display.getCurrent();
        if (display == null) {
            work.run();
        } else {
            display.asyncExec(work);
        }
    }
}
