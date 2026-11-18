// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.List;

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
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.part.EditorPart;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageKind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewContent;

/**
 * A page editor: {@code :help}, {@code :messages} or {@code :checkhealth} in an editor of the
 * editor area, beside the code, as Vim shows them in a window of their own. One per kind
 * ({@link NeovimPageInput}, {@link PageKind}), each holding a {@link ScreenPane} with a second
 * Neovim of that kind. It is an editor of Eclipse's like any other (a tab, a stack, a split,
 * dragged anywhere in the editor area), but no text editor: the plug-in attaches no Neovim to it
 * ({@code EditorAttachManager.textEditorOf} is {@code null}), and there is nothing to save.
 *
 * <ul>
 *   <li>The title is the kind's, "Neovim: help" ({@link NeovimPageInput#getName}).</li>
 *   <li>Every key goes to the second Neovim, except the shortcuts Eclipse binds; Ctrl+W is
 *       Neovim's through the same key context as the Neovim view's
 *       ({@link NeovimView#KEY_CONTEXT_ID}), turned on while the screen has the keyboard.</li>
 *   <li>When the second Neovim ends ({@code q}, {@code :q}, a crash), the editor closes; the next
 *       command of its kind opens a new one.</li>
 *   <li>Opened on anything but a {@link NeovimPageInput} (a file through Open With), it refuses.</li>
 * </ul>
 *
 * <p>Not brought back at the next start: see {@link #closeLeftovers}. UI thread.
 */
public final class NeovimPageEditor extends EditorPart {

    /** The editor's id in {@code plugin.xml}. */
    public static final String ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.editors.page";

    private ScreenPane pane;
    /** The editor the last content came from, for the way back; {@code null} if none. */
    private IEditorPart cameFrom;
    /** The key context while the screen has the keyboard; {@code null} otherwise. */
    private IContextActivation keyContext;

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
        pane = new ScreenPane(parent, new ScreenPane.Host() {
            @Override
            public void titleChanged(String title) {
                setPartName(title);
            }

            @Override
            public void toEditor() {
                NeovimPageEditor.this.toEditor();
            }

            @Override
            public void ended() {
                getSite().getPage().closeEditor(NeovimPageEditor.this, false);
            }
        });
        // Bound to the editor's site: Eclipse also ends the activation when the editor stops being
        // the active part, so the context never outlives the focus.
        IContextService contexts = getSite().getService(IContextService.class);
        if (contexts != null) {
            pane.canvas().addListener(SWT.FocusIn, e -> {
                if (keyContext == null) {
                    keyContext = contexts.activateContext(NeovimView.KEY_CONTEXT_ID);
                }
            });
            pane.canvas().addListener(SWT.FocusOut, e -> {
                if (keyContext != null) {
                    contexts.deactivateContext(keyContext);
                    keyContext = null;
                }
            });
        }
    }

    @Override
    public void setFocus() {
        if (pane != null) {
            pane.setFocus();
        }
    }

    /**
     * Shows {@code content}, which must be of this editor's kind, replacing what it held
     * ({@link ScreenPane#show}). {@code from} is the editor it came from: its colors are this
     * editor's, and the way back leads to it. UI thread.
     *
     * @param from the editor whose Neovim produced the content, or {@code null} if none is active
     * @param fg   that editor's text color, {@code 0xRRGGBB}
     * @param bg   its background
     */
    void show(ViewContent content, IEditorPart from, int fg, int bg) {
        if (PageKind.of(content) != kind()) {
            throw new IllegalArgumentException(content.title() + " is not shown in the " + getPartName() + " editor");
        }
        cameFrom = from;
        pane.show(content, fg, bg);
    }

    /** {@code <C-w>} from the plug-in's key binding ({@link SendKeyHandler}), typed here. UI thread. */
    void sendKey(String key) {
        pane.sendKey(key);
    }

    /**
     * {@code Ctrl-W w}, {@code p} or {@code k}: the editor of the file the content came from, or
     * the page's active one. The editor found by its file: if that editor was closed and its file
     * opened again, the open one is a new part.
     */
    private void toEditor() {
        IWorkbenchPage page = getSite().getPage();
        IEditorPart found = cameFrom == null ? null : page.findEditor(cameFrom.getEditorInput());
        if (found != null && found != this) {
            page.activate(found);
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
    public void dispose() {
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
