// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.part.ViewPart;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ScreenInput;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewContent;

/**
 * The Neovim view: a second Neovim's whole screen in an Eclipse view, for {@code :messages},
 * {@code :checkhealth} and {@code :help} ({@link NeovimViewOpener} opens it with what the editing
 * Neovim handed over). The screen, keys, mouse and the second Neovim itself are a
 * {@link ScreenPane}'s, which never writes a file; this class is the view around it: its title,
 * the key context, and the way back to the editor.
 *
 * <h2>What the user sees</h2>
 * <ul>
 *   <li>The title says what the view holds: "Neovim: messages", "Neovim: checkhealth"
 *       ({@link ScreenInput#title}). New text replaces the old.</li>
 *   <li>Every key goes to the second Neovim ({@code /} searches, {@code y} yanks into the editing
 *       Neovim's register, {@code q} or {@code :q} closes the view), except the shortcuts Eclipse
 *       binds, which Eclipse keeps as in any view. {@code Ctrl-W w}, {@code p} and {@code k} give
 *       the keyboard back to the editor the text came from.</li>
 *   <li>The left button and the wheel are Neovim's ({@link GridMouse}).</li>
 *   <li>When the second Neovim ends ({@code :q}, a crash), the view closes; the next
 *       {@code :messages} opens it with a new one.</li>
 * </ul>
 *
 * <p>UI thread.
 */
public final class NeovimView extends ViewPart {

    /** The view's id in {@code plugin.xml}. */
    public static final String ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.views.neovim";

    /**
     * The key context active while the view's canvas has the keyboard ({@code plugin.xml}): there
     * Ctrl+W runs the plug-in's Ctrl-W command, which {@link SendKeyHandler} sends to this view,
     * instead of Eclipse's Close, which would close the editor behind the view.
     */
    static final String KEY_CONTEXT_ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.context.view";

    private ScreenPane pane;
    /** The editor the last content came from, for {@code Ctrl-W p}; {@code null} if none. */
    private IEditorPart cameFrom;
    /** The view's key context while the canvas has the keyboard ({@link #KEY_CONTEXT_ID}); {@code null} otherwise. */
    private IContextActivation keyContext;

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
                NeovimView.this.toEditor();
            }

            @Override
            public void ended() {
                getSite().getPage().hideView(NeovimView.this);
            }
        });
        // Bound to the view's site: Eclipse also ends the activation when the view stops being the
        // active part, so the context never outlives the focus.
        IContextService contexts = getSite().getService(IContextService.class);
        if (contexts != null) {
            pane.canvas().addListener(SWT.FocusIn, e -> {
                if (keyContext == null) {
                    keyContext = contexts.activateContext(KEY_CONTEXT_ID);
                }
            });
            pane.canvas().addListener(SWT.FocusOut, e -> {
                if (keyContext != null) {
                    contexts.deactivateContext(keyContext);
                    keyContext = null;
                }
            });
        }
        setPartName(ScreenInput.title(""));
    }

    @Override
    public void setFocus() {
        if (pane != null) {
            pane.setFocus();
        }
    }

    /**
     * Shows {@code content}, replacing what the view held ({@link ScreenPane#show}). {@code from} is
     * the editor it came from: its colors are the view's, and {@code Ctrl-W p} goes back to it. UI
     * thread.
     *
     * @param from the editor whose Neovim produced the content, or {@code null} if none is active
     * @param fg   that editor's text color, {@code 0xRRGGBB}
     * @param bg   its background
     */
    void show(ViewContent content, IEditorPart from, int fg, int bg) {
        cameFrom = from;
        pane.show(content, fg, bg);
    }

    /**
     * A Vim key Eclipse would have taken for a shortcut, from the plug-in's key binding
     * ({@link SendKeyHandler}): {@code <C-w>}, typed in the view. UI thread.
     */
    void sendKey(String key) {
        pane.sendKey(key);
    }

    /**
     * {@code Ctrl-W w}, {@code p} or {@code k}: the editor of the file the text came from, or the
     * page's active one. The editor found by its file, not {@link #cameFrom} itself: if that editor
     * was closed and its file opened again, the open one is a new part.
     */
    private void toEditor() {
        IWorkbenchPage page = getSite().getPage();
        IEditorPart found = cameFrom == null ? null : page.findEditor(cameFrom.getEditorInput());
        IEditorPart editor = found != null ? found : page.getActiveEditor();
        if (editor != null) {
            page.activate(editor);
        }
    }

    /** The canvas the screen is drawn on, for tests. UI thread. */
    GridCanvas canvasForTests() {
        return pane.canvas();
    }

    @Override
    public void dispose() {
        if (pane != null) {
            pane.dispose();
        }
        super.dispose();
    }
}
