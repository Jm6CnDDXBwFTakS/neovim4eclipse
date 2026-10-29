// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntConsumer;
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

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HistorySteps;

/**
 * Eclipse's Back and Forward for {@code Ctrl-O} and {@code Ctrl-I} past the end of a Neovim
 * window's jump history, so that {@code Ctrl-I} retraces {@code Ctrl-O}. The decisions (which
 * entries to step over, where Forward goes) are {@link HistorySteps}; its class comment has the
 * why and an example. This class is the Eclipse half:
 * <ul>
 *   <li>{@link #activateForNeovim}: an editor switch the plug-in makes because a Neovim jump went
 *       into another editor's file. Eclipse records it in its history as any activation; the entry
 *       is noted as the plug-in's. Eclipse adds the entry a moment later ({@code markEditor} posts
 *       it to the UI thread), so the note is posted after it.</li>
 *   <li>{@link #back} and {@link #forward}: the steps, through the workbench window's handler
 *       service (Backward History, Forward History), since the first step may activate another
 *       editor and the old editor's site would no longer be the command's context. Posted once more
 *       to the UI thread first: a key held down can arrive before the history entry of the switch
 *       the previous key made, and the steps must count it.</li>
 * </ul>
 *
 * <p>Only public API: {@link INavigationHistory} gives the entries and the current one, but no way
 * to leave an entry out or to go to one directly; hence the steps. One {@link HistorySteps} per
 * workbench page, as Eclipse keeps one history per page. UI thread only.
 */
final class NavigationHistorySteps {

    private static final Logger LOG = Logger.getLogger(NavigationHistorySteps.class.getName());

    /** An editor and its selection, to come back to when Eclipse's Forward no longer can. */
    record Place(IEditorPart editor, int offset, int length) {
    }

    private static final Map<IWorkbenchPage, HistorySteps<INavigationLocation, Place>> STEPS = new WeakHashMap<>();

    private NavigationHistorySteps() {
    }

    /** Activates {@code target} for Neovim (a jump into its file), noting Eclipse's entry as the plug-in's. */
    static void activateForNeovim(IWorkbenchPage page, IEditorPart target) {
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
                    steps(page).markOwn(now);
                }
            });
        }
    }

    /**
     * {@code Ctrl-O} at the start of the window's jump history: Back, past the plug-in's entries,
     * one press per step of the count, each remembered for {@code Ctrl-I}.
     *
     * @param nowhere told how many of the steps went nowhere (the start of the history), for
     *                Neovim's trail of {@code Ctrl-O} steps (LuaScripts.SETUP)
     */
    static void back(IWorkbenchPage page, int count, IntConsumer nowhere) {
        later(() -> {
            int presses = Math.max(1, count);
            for (int press = 0; press < presses; press++) {
                if (!backOnce(page)) {
                    LOG.fine("Ctrl-O: no older entry in Eclipse's navigation history");
                    nowhere.accept(presses - press);
                    return;
                }
            }
        });
    }

    /** One press of Back; {@code false} at the start of the history. */
    private static boolean backOnce(IWorkbenchPage page) {
        INavigationHistory history = page.getNavigationHistory();
        List<INavigationLocation> entries = Arrays.asList(history.getLocations());
        INavigationLocation current = history.getCurrentLocation();
        HistorySteps<INavigationLocation, Place> steps = steps(page);
        int n = steps.backSteps(entries, current, 1);
        if (n == 0) {
            return false;
        }
        Place start = placeOf(page.getActiveEditor());
        steps.wentBack(entries, current, start, 1);
        run(page, IWorkbenchCommandConstants.NAVIGATE_BACKWARD_HISTORY, n, "Ctrl-O");
        // A Back that changed nothing the user can see is no press: the entry is the place the
        // last Neovim jump already brought the editor to. Example: click test.txt's tab, :e
        // other.txt, :e #, Ctrl-O: the jump back to test.txt is Neovim's, and Back's first
        // entry is the tab click, test.txt again, with the same caret. Each round goes back at
        // least one entry, so this ends.
        while (start != null && start.equals(placeOf(page.getActiveEditor()))) {
            int more = steps.backSteps(Arrays.asList(history.getLocations()), history.getCurrentLocation(), 1);
            if (more == 0 || !run(page, IWorkbenchCommandConstants.NAVIGATE_BACKWARD_HISTORY, more, "Ctrl-O")) {
                return true;
            }
        }
        return true;
    }

    /** {@code Ctrl-I} at the end of the window's jump history: back to where the last Back started. */
    static void forward(IWorkbenchPage page, int count) {
        later(() -> {
            INavigationHistory history = page.getNavigationHistory();
            HistorySteps<INavigationLocation, Place> steps = steps(page);
            switch (steps.forward(Arrays.asList(history.getLocations()), history.getCurrentLocation(), count)) {
                case HistorySteps.Steps<Place> s -> run(page, IWorkbenchCommandConstants.NAVIGATE_FORWARD_HISTORY,
                        s.steps(), "Ctrl-I");
                case HistorySteps.Restore<Place> r -> restore(page, r.state(), steps);
            }
        });
    }

    /**
     * Shows {@code place} again: Eclipse's history lost it (a later switch of the plug-in's
     * deleted it as a forward entry). If its editor was closed meanwhile, nothing happens, and the
     * remembered Backs are forgotten.
     */
    private static void restore(IWorkbenchPage page, Place place, HistorySteps<INavigationLocation, Place> steps) {
        if (place == null || !isOpen(page, place.editor())) {
            steps.forget();
            LOG.fine("Ctrl-I: the editor a Back left is closed");
            return;
        }
        activateForNeovim(page, place.editor());
        if (place.offset() >= 0 && place.editor() instanceof ITextEditor text
                && !place.equals(placeOf(text))) {
            text.selectAndReveal(place.offset(), place.length()); // a Back inside one editor moved its caret
        }
    }

    /** The active editor and its text selection ({@code -1} for an editor without one). */
    private static Place placeOf(IEditorPart editor) {
        if (editor == null) {
            return null;
        }
        ISelection selection = editor.getSite().getSelectionProvider() == null ? null
                : editor.getSite().getSelectionProvider().getSelection();
        return selection instanceof ITextSelection text ? new Place(editor, text.getOffset(), text.getLength())
                : new Place(editor, -1, 0);
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
