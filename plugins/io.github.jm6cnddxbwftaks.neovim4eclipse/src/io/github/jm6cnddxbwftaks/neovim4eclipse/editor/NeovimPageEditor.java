// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.INavigationLocation;
import org.eclipse.ui.INavigationLocationProvider;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.NavigationLocation;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.part.EditorPart;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.EditorWindow;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.IdeAction;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageKind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewContent;

/**
 * A page editor: {@code :help}, {@code :messages} or {@code :checkhealth} in an editor of the
 * editor area, beside the code, as Vim shows them in a window of their own. Each is a window of the
 * editing Neovim, the one behind the code ({@link WindowPane}), on a buffer of its kind
 * ({@link PageKind}): the messages buffer the plug-in fills, a help buffer, a report. Any number of
 * editors of a kind may be open, each its own window ({@code :split} in the page makes a second);
 * the next command of the kind shows in the one used last. It is an editor of Eclipse's like any
 * other (a tab, a stack, a split, dragged anywhere in the editor area), but no text editor: the
 * plug-in attaches no Neovim to it ({@code EditorAttachManager.textEditorOf} is {@code null}), and
 * there is nothing to save.
 *
 * <ul>
 *   <li>The title is the kind's, "Neovim: help" ({@link NeovimPageInput#getName}).</li>
 *   <li>Every key goes to the editing Neovim, in the page's window, except the shortcuts Eclipse
 *       binds; Ctrl+W is Neovim's through a key context of the page editors' own
 *       ({@link #KEY_CONTEXT_ID}), turned on while the screen has the keyboard.</li>
 *   <li>{@code q} and {@code :q} are commands from its window ({@link PageCommandsRun}), which close
 *       it; so does Neovim closing the window, or Restart Neovim ({@link #closeEditingPages}).</li>
 *   <li>Opened on anything but a {@link NeovimPageInput} (a file through Open With), it refuses.</li>
 * </ul>
 *
 * <p>Not brought back at the next start: see {@link #closeLeftovers}. Not a stop for {@code Ctrl-O}
 * and {@code Ctrl-I} from the code ({@code util/HistorySteps}): its entries in Eclipse's history
 * carry a {@link PageLocation}, whose input says whose they are (without one, Eclipse records a
 * {@code null} entry, which tells nothing). UI thread.
 */
public final class NeovimPageEditor extends EditorPart implements INavigationLocationProvider {

    /** The editor's id in {@code plugin.xml}. */
    public static final String ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.editors.page";

    /**
     * The key context active while a page editor's screen has the keyboard ({@code plugin.xml}):
     * there Ctrl+W runs the plug-in's Ctrl-W command, which {@link SendKeyHandler} sends to this
     * editor's Neovim, instead of Eclipse's Close Editor (on macOS Close is Cmd+W, and Ctrl+W is
     * free anyway).
     */
    static final String KEY_CONTEXT_ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.context.page";

    private WindowPane pane;
    /** The editor of each kind used last ({@link #lastUsed}): the kind's next command shows there. UI thread. */
    private static final Map<PageKind, WeakReference<NeovimPageEditor>> LAST_USED = new EnumMap<>(PageKind.class);
    /** The key context while the screen has the keyboard; {@code null} otherwise. */
    private IContextActivation keyContext;
    /**
     * Every Ctrl letter but {@code w} (the page's own context binds that one, and two active
     * bindings of a key are a conflict), while a plug-in's window over the page has Neovim's
     * cursor: without it {@code Ctrl+N} in a picker's prompt opened from {@code :help} would run
     * Eclipse's New wizard ({@code util/KeyContextRule}). {@link SendKeyHandler} sends each to
     * this page.
     */
    private final VimKeyContexts floatKeys = new VimKeyContexts();

    @Override
    public void init(IEditorSite site, IEditorInput input) throws PartInitException {
        if (!(input instanceof NeovimPageInput)) {
            throw new PartInitException("The Neovim page editor shows only :help, :messages and :checkhealth");
        }
        setSite(site);
        setInput(input);
        setPartName(input.getName());
    }

    /** The kind of content this editor shows. */
    PageKind kind() {
        return ((NeovimPageInput) getEditorInput()).kind();
    }

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());
        NeovimSession editing = Activator.getDefault().currentSession();
        if (editing == null) {
            // No editing Neovim (it ended between the command and this editor): nothing to show,
            // and nothing would draw here; the next command of the kind opens a new editor.
            getSite().getShell().getDisplay().asyncExec(() -> getSite().getPage().closeEditor(this, false));
            return;
        }
        pane = new WindowPane(parent, new WindowPane.Host() {
            @Override
            public IEditorSite site() {
                return getEditorSite();
            }

            @Override
            public void command(HostCommand command) {
                PageCommandsRun.run(NeovimPageEditor.this, command);
            }

            @Override
            public void action(IdeAction action) {
                PageCommandsRun.action(NeovimPageEditor.this, action);
            }

            @Override
            public void close() {
                getSite().getPage().closeEditor(NeovimPageEditor.this, false);
            }

            @Override
            public void floatsChanged() {
                applyFloatKeys();
            }
        }, editing);
        pane.canvas().addListener(SWT.FocusIn, e -> LAST_USED.put(kind(), new WeakReference<>(this)));
        pane.canvas().addListener(SWT.FocusOut, e -> pane.focusOut());
        // Bound to the editor's site: Eclipse also ends the activation when the editor stops being
        // the active part, so the context never outlives the focus.
        IContextService contexts = getSite().getService(IContextService.class);
        if (contexts != null) {
            // The text editors' contexts go off first, and come back only after this one is off:
            // two active Ctrl+W bindings are a conflict, and Eclipse runs neither
            // (EditorKeyContext.pageHasKeyboard).
            pane.canvas().addListener(SWT.FocusIn, e -> {
                if (keyContext == null) {
                    EditorKeyContext.pageHasKeyboard(true);
                    keyContext = contexts.activateContext(KEY_CONTEXT_ID);
                    applyFloatKeys();
                }
            });
            pane.canvas().addListener(SWT.FocusOut, e -> {
                if (keyContext != null) {
                    contexts.deactivateContext(keyContext);
                    keyContext = null;
                    applyFloatKeys(); // off before the text editors' letters come back
                    EditorKeyContext.pageHasKeyboard(false);
                }
            });
        }
    }

    /** Switches {@link #floatKeys} on while this page has the keyboard and a plug-in's window the cursor. UI thread. */
    private void applyFloatKeys() {
        IContextService contexts = getSite().getService(IContextService.class);
        if (contexts == null || pane == null) {
            return;
        }
        boolean wanted = keyContext != null && pane.hasFocusedFloat();
        if (wanted && !floatKeys.isActive()) {
            floatKeys.apply(contexts, letter -> letter != 'w');
        } else if (!wanted && floatKeys.isActive()) {
            floatKeys.deactivate(contexts);
        }
    }

    /**
     * Closes every page editor, in each workbench window: each is a window of the editing Neovim, and
     * Restart Neovim ends that Neovim, and their windows with it. The next {@code :help},
     * {@code :messages} or {@code :checkhealth} opens a new one on the new Neovim. UI thread.
     */
    public static void closeEditingPages(IWorkbench workbench) {
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
            for (var page : window.getPages()) {
                for (var reference : page.getEditorReferences()) {
                    if (ID.equals(reference.getId()) && reference.getEditor(false) instanceof NeovimPageEditor editor
                            && editor.windowPane() != null) {
                        page.closeEditor(editor, false);
                    }
                }
            }
        }
    }

    /** The editor of {@code kind} used last, if it is still open, or {@code null}. UI thread. */
    static NeovimPageEditor lastUsed(PageKind kind) {
        WeakReference<NeovimPageEditor> ref = LAST_USED.get(kind);
        NeovimPageEditor last = ref == null ? null : ref.get();
        return last != null && last.pane != null && !last.pane.canvas().isDisposed() ? last : null;
    }

    /** The pane, a window of the editing Neovim; {@code null} when the editor closes for want of one. */
    WindowPane windowPane() {
        return pane;
    }

    @Override
    public void setFocus() {
        if (pane != null) {
            pane.setFocus();
        }
    }

    /**
     * Shows {@code content}, which must be of this editor's kind, replacing what it held
     * ({@link WindowPane#show}), in the colors of the editor it came from. UI thread.
     *
     * @param fg that editor's text color, {@code 0xRRGGBB}
     * @param bg its background
     */
    void show(ViewContent content, int fg, int bg) {
        if (PageKind.of(content) != kind()) {
            throw new IllegalArgumentException(content.title() + " is not shown in the " + getPartName() + " editor");
        }
        if (pane != null) {
            pane.show(content, fg, bg);
        }
    }

    /**
     * Shows the page of {@code other}, of the same kind, in a window of this editor's own: this is the
     * second half of a {@code :split} of {@code other} ({@link WindowPane#showSameAs}). UI thread.
     */
    void showSameAs(NeovimPageEditor other, int fg, int bg) {
        WindowPane from = other.windowPane();
        if (pane != null && from != null && from.window() != EditorWindow.UNKNOWN) {
            pane.showSameAs(from.window(), fg, bg);
        }
    }

    /** {@code <C-w>} from the plug-in's key binding ({@link SendKeyHandler}), typed here. UI thread. */
    void sendKey(String key) {
        if (pane != null) {
            pane.sendKey(key);
        }
    }

    /** The canvas the screen is drawn on, for tests. UI thread. */
    GridCanvas canvasForTests() {
        return pane.canvas();
    }

    /**
     * Closes every page editor's tab Eclipse brought back from its saved layout, at the start, in
     * each window. Its input is not saved ({@link NeovimPageInput#getPersistable}), but Eclipse
     * keeps the tab itself in the layout, as a placeholder that would show nothing; found on Linux,
     * macOS and Windows. Such a placeholder no longer answers to the editor's id
     * ({@code IEditorReference.getId} is Eclipse's empty-tab id), but its model part keeps the id
     * as a tag, so the parts are found by that tag and hidden, which removes them. One check in one
     * place every start passes, so it holds after a crash too, when no shutdown hook ran. UI thread.
     */
    static void closeLeftovers(IWorkbench workbench) {
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
            MWindow model = window.getService(MWindow.class);
            EModelService models = window.getService(EModelService.class);
            EPartService parts = window.getService(EPartService.class);
            if (model == null || models == null || parts == null) {
                continue;
            }
            for (MPart leftover : models.findElements(model, null, MPart.class, List.of(ID))) {
                parts.hidePart(leftover, true);
            }
        }
    }

    @Override
    public INavigationLocation createEmptyNavigationLocation() {
        return new PageLocation(this);
    }

    @Override
    public INavigationLocation createNavigationLocation() {
        return new PageLocation(this);
    }

    /**
     * This editor's place in Eclipse's history: the editor and nothing more, since the page's place
     * is its Neovim window's to keep. Two entries of the same editor merge into one, as Eclipse merges a
     * text editor's entries at one place. Its input is the editor's ({@link NeovimPageInput}).
     */
    static final class PageLocation extends NavigationLocation {

        PageLocation(IEditorPart editor) {
            super(editor);
        }

        @Override
        public void saveState(IMemento memento) {
            // nothing: the editor is never saved either
        }

        @Override
        public void restoreState(IMemento memento) {
            // nothing
        }

        @Override
        public void restoreLocation() {
            // the editor coming to the front is all there is
        }

        @Override
        public boolean mergeInto(INavigationLocation location) {
            return location instanceof PageLocation other && Objects.equals(other.getInput(), getInput());
        }

        @Override
        public void update() {
            // nothing moves within it
        }
    }

    @Override
    public void dispose() {
        if (keyContext != null) {
            // Closed with the keyboard and no FocusOut seen: the text editors get their keys back.
            // Eclipse ends the site's activation itself.
            keyContext = null;
            EditorKeyContext.pageHasKeyboard(false);
        }
        if (pane != null) {
            pane.dispose();
        }
        super.dispose();
    }

    @Override
    public void doSave(IProgressMonitor monitor) {
        // nothing to save: the page is Neovim's, not a file's
    }

    @Override
    public void doSaveAs() {
        // nothing to save
    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public boolean isSaveAsAllowed() {
        return false;
    }
}
