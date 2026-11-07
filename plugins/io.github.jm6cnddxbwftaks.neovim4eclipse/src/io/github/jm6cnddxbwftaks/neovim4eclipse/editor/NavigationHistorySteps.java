// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.commands.NotEnabledException;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.INavigationHistory;
import org.eclipse.ui.INavigationLocation;
import org.eclipse.ui.IPageListener;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
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
     * is held weakly: a closed editor is not kept alive by a remembered Back.
     */
    record Place(WeakReference<IEditorPart> editor, int offset, int length) {
        boolean sameAs(Place other) {
            return other != null && editor.get() != null && editor.get() == other.editor.get()
                    && offset == other.offset && length == other.length;
        }
    }

    /**
     * The steps of each workbench page, removed when the page closes ({@link #forgetWhenClosed}).
     *
     * <p>Why not a {@code WeakHashMap}: its keys are weak, but its values are strong, and the values
     * here reach their own key. A {@link HistorySteps} holds {@link INavigationLocation}s, a text
     * editor's location holds its editor, the editor its site, and the site its page. So the page
     * stays reachable through its own entry and would never be collected: each closed workbench
     * window would keep its page and every editor in that history alive. Removing the entry when
     * Eclipse closes the page has no such loop. UI thread only, so a plain map.
     */
    private static final Map<IWorkbenchPage, HistorySteps<INavigationLocation, Place>> STEPS = new HashMap<>();

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
        // A place whose file is gone (closeIfGone) is no step either: closed, and Back goes on.
        while (true) {
            Set<IEditorPart> open = openEditors(page);
            if (!run(page, IWorkbenchCommandConstants.NAVIGATE_BACKWARD_HISTORY, n, "Ctrl-O")) {
                break;
            }
            if (!closeIfGone(page, open) && (start == null || !start.sameAs(placeOf(page.getActiveEditor())))) {
                break;
            }
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
                forwardPastGone(page, steps.plainForwardSteps(entries(history), history.getCurrentLocation(), count));
                return;
            }
            switch (steps.forward(oldest, entries(history), history.getCurrentLocation())) {
                case HistorySteps.Steps<Place> s -> forwardPastGone(page, s.steps());
                case HistorySteps.Restore<Place> r -> restore(page, r.state(), steps);
            }
        });
    }

    /** Forward {@code steps} entries, then one more for each place whose file is gone ({@link #closeIfGone}). */
    private static void forwardPastGone(IWorkbenchPage page, int steps) {
        int next = steps;
        while (true) {
            Set<IEditorPart> open = openEditors(page);
            if (!run(page, IWorkbenchCommandConstants.NAVIGATE_FORWARD_HISTORY, next, "Ctrl-I")
                    || !closeIfGone(page, open)) {
                return;
            }
            next = 1; // stepped over a gone place: one more
        }
    }

    /**
     * Closes the active editor if Back or Forward just opened it ({@code openBefore} did not have
     * it) on a file that no longer exists, with nothing typed in it. Eclipse's history keeps the entries of closed editors, also after their file is
     * deleted (its project deleted, say); going back to one, Eclipse cannot open the file and shows
     * an error page in a new editor tab instead ("Unable to create part", in the Error Log too).
     * Eclipse's own Back does the same. {@code Ctrl-O} and {@code Ctrl-I} step over such a place
     * instead, as there is nothing to show there. Never an editor that was open already: one on a
     * new file not written yet ({@code :e new.txt}) is a place to go back to, as in Vim. Only a
     * local file, a workspace one or one outside ({@link #isGone}), so an editor whose input exists
     * in another way (a compare editor, say) is never closed. Eclipse's error message is still logged: its
     * history has no public way to tell such an entry before going to it (a closed editor's entry
     * gives no input), nor to remove one.
     *
     * @return whether it closed one
     */
    private static boolean closeIfGone(IWorkbenchPage page, Set<IEditorPart> openBefore) {
        IEditorPart active = page.getActiveEditor();
        if (active == null || openBefore.contains(active) || active.isDirty() || !isGone(active.getEditorInput())) {
            return false;
        }
        LOG.fine(() -> "Ctrl-O/Ctrl-I: " + active.getEditorInput().getName() + " is gone; its place is stepped over");
        page.closeEditor(active, false);
        return true;
    }

    /**
     * Whether {@code input} is a local file that is not there: a workspace file (its project
     * deleted, say) or a file outside the workspace. Anything else (a compare, a class file in a
     * jar) is not, whatever its {@code exists()} says.
     */
    private static boolean isGone(IEditorInput input) {
        IFile file = input.getAdapter(IFile.class);
        if (file != null) {
            return !file.exists();
        }
        if (input instanceof IURIEditorInput uri) {
            return isMissingLocalFile(uri.getURI());
        }
        return false;
    }

    /**
     * Whether {@code uri} names a local file ({@code file:}) that does not exist. Anything that is
     * not a plain local path counts as there: {@code null}, another scheme, or a {@code file:} URI
     * that Java cannot turn into a path.
     *
     * <p>Example of the last: {@code file://server/share/notes.txt}. On Windows it is the UNC path
     * {@code \\server\share\notes.txt} and is checked; on Linux and macOS {@link Path#of(URI)}
     * refuses a {@code file:} URI with a host ({@link IllegalArgumentException}, "URI has an
     * authority component"). Such a place may well exist (a share Eclipse reaches through another
     * file system), and a place that counts as gone is closed ({@link #closeIfGone}), so the safe
     * answer is "there": the step stays an ordinary step. Uncaught, the exception would end
     * {@code Ctrl-O} halfway, inside a posted runnable (Eclipse logs "Unhandled event loop
     * exception"), without telling Neovim's trail which steps went nowhere, so the two histories
     * would no longer match.
     */
    static boolean isMissingLocalFile(URI uri) {
        if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        try {
            return !Files.exists(Path.of(uri));
        } catch (IllegalArgumentException | FileSystemNotFoundException | SecurityException e) {
            LOG.log(Level.FINE, "Not a local path, taken as there: " + uri, e);
            return false;
        }
    }

    /** The editors open now, restored or not loaded yet ({@code null}s left out). */
    private static Set<IEditorPart> openEditors(IWorkbenchPage page) {
        Set<IEditorPart> open = new HashSet<>();
        for (IEditorReference ref : page.getEditorReferences()) {
            IEditorPart part = ref.getEditor(false);
            if (part != null) {
                open.add(part);
            }
        }
        return open;
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
        HistorySteps<INavigationLocation, Place> steps = STEPS.get(page);
        if (steps == null) {
            steps = new HistorySteps<>();
            STEPS.put(page, steps);
            forgetWhenClosed(page);
        }
        return steps;
    }

    /**
     * Drops {@code page}'s steps when Eclipse closes it (see {@link #STEPS}): its workbench window
     * closes, or Eclipse shuts down. Eclipse tells a window's page listeners then
     * ({@code WorkbenchWindow.hardClose}); the listener removes itself too, so nothing of the page is
     * left behind.
     */
    private static void forgetWhenClosed(IWorkbenchPage page) {
        IWorkbenchWindow window = page.getWorkbenchWindow();
        if (window == null) {
            return;
        }
        window.addPageListener(new IPageListener() {
            @Override
            public void pageClosed(IWorkbenchPage closed) {
                if (closed == page) {
                    STEPS.remove(page);
                    window.removePageListener(this);
                }
            }

            @Override
            public void pageActivated(IWorkbenchPage activated) {
                // nothing: only the close matters
            }

            @Override
            public void pageOpened(IWorkbenchPage opened) {
                // nothing: only the close matters
            }
        });
    }

    /** For tests: whether {@code page} has steps kept for it. UI thread. */
    static boolean hasStepsFor(IWorkbenchPage page) {
        return STEPS.containsKey(page);
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
