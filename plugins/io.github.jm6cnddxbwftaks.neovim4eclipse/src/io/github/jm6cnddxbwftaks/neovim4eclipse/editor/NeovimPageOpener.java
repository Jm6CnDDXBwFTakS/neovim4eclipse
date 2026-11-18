// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageKind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PagePlacement;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewContent;

/**
 * Opens a page editor ({@link NeovimPageEditor}) with what the editing Neovim handed over:
 * {@code :messages}, {@code :checkhealth} and {@code :help} ({@code NeovimSession.addViewContentListener},
 * which {@code Activator} points here when it starts the session). The editor of the content's
 * kind takes the keyboard, as Vim's {@code :help} window, {@code :messages} pager and
 * {@code :checkhealth} window do, in the active workbench window. Where it goes is
 * {@link PagePlacement}'s rule: the kind's editor where it is; else a tab beside another page
 * editor; else a new stack below the editor the content came from.
 *
 * <p>Example: {@code :help} typed in a lone editor: "Neovim: help" opens below it, with its
 * colors. {@code :messages} next: "Neovim: messages" as a tab beside the help editor.
 */
public final class NeovimPageOpener {

    private static final Logger LOG = Logger.getLogger(NeovimPageOpener.class.getName());

    private NeovimPageOpener() {
    }

    /** Shows {@code content} (text or a help page) in its page editor; any thread (the RPC reader thread, usually). */
    public static void show(ViewContent content) {
        if (!PlatformUI.isWorkbenchRunning()) {
            return;
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.asyncExec(() -> showOnUi(content));
    }

    private static void showOnUi(ViewContent content) {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
            window = windows.length > 0 ? windows[0] : null;
        }
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        if (page == null) {
            LOG.fine(() -> "No workbench page to show " + content.title() + " in");
            return;
        }
        IEditorPart from = cameFrom(page);
        int foreground = 0x000000;
        int background = 0xFFFFFF;
        if (from != null && from.getAdapter(Control.class) instanceof StyledText styled && !styled.isDisposed()) {
            foreground = rgbOf(styled.getForeground());
            background = rgbOf(OverlayFrame.intendedBackground(styled));
        }
        PageKind kind = PageKind.of(content);
        NeovimPageInput input = new NeovimPageInput(kind);
        IEditorPart open = page.findEditor(input);
        IEditorPart another = anotherPage(page, kind);
        PagePlacement placement = PagePlacement.of(open != null, another != null, from != null);
        try {
            IEditorPart opened = open != null ? open : page.openEditor(input, NeovimPageEditor.ID, false);
            switch (placement) {
                case BESIDE_ANOTHER -> EditorSplits.intoStackOf(another, opened);
                case BELOW -> EditorSplits.intoNewStack(from, opened, EModelService.BELOW);
                case WHERE_IT_IS, WHERE_ECLIPSE_OPENS -> {
                    // where it is, or where Eclipse put it
                }
            }
            page.activate(opened);
            opened.setFocus();
            if (opened instanceof NeovimPageEditor editor) {
                editor.show(content, foreground, background);
            }
        } catch (PartInitException e) {
            LOG.log(Level.WARNING, "Could not open the " + input.getName() + " editor", e);
        }
    }

    /**
     * The editor the content came from: the one Neovim is attached to (where the command was
     * typed), else the active editor unless it is a page editor itself. {@code null} if none.
     */
    private static IEditorPart cameFrom(IWorkbenchPage page) {
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        IEditorPart attached = controller == null || controller.isClosed() ? null
                : EditorAttachManager.getInstance().workbenchPartOf(controller.getEditor());
        if (attached != null && attached.getSite().getPage() == page) {
            return attached;
        }
        IEditorPart active = page.getActiveEditor();
        return active instanceof NeovimPageEditor ? null : active;
    }

    /** An open page editor of another kind than {@code kind}, or {@code null}. */
    private static IEditorPart anotherPage(IWorkbenchPage page, PageKind kind) {
        for (PageKind other : PageKind.values()) {
            if (other != kind) {
                IEditorPart editor = page.findEditor(new NeovimPageInput(other));
                if (editor != null) {
                    return editor;
                }
            }
        }
        return null;
    }

    private static int rgbOf(Color color) {
        return (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }
}
