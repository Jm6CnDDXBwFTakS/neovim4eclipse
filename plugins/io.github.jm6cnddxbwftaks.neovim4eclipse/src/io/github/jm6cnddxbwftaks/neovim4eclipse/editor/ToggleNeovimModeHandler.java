// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ResourceLocator;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ToggleButtonState;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ToggleButtonState.Editor;

/**
 * Toggles between Neovim key handling and plain Eclipse editing on the active editor (the Neovim
 * toolbar button, its menu checkbox and its key binding). Neovim stays attached in Eclipse mode,
 * so switching back is instant. See {@link AttachHandler} for how handlers work.
 *
 * <h2>Pressed, raised, greyed out</h2>
 * The toolbar button and the menu entry are {@code style="toggle"} items, which Eclipse draws
 * pressed (checked) or raised. Two handler features drive them:
 * <ul>
 *   <li><b>{@link IElementUpdater}</b>: Eclipse calls {@link #updateElement} for every item bound to
 *       this command (the button, the menu entry) when someone calls
 *       {@code ICommandService.refreshElements}; the handler sets each item's checked state and
 *       tooltip there, and the toolbar button's icon (color in Neovim mode, grey in Eclipse mode:
 *       macOS barely shows a pressed button).</li>
 *   <li><b>Enablement</b>: {@link #setBaseEnabled} greys every item out at once and also makes the
 *       key binding do nothing, while the active editor has no Neovim attached (an image, a form
 *       page, a text editor after <em>Detach</em>). Neovim can stay attached to a text editor in
 *       the background while an image is active; the button then still greys out, since it is
 *       about the editor in front.</li>
 * </ul>
 * {@link ToggleButtonRefresh} triggers both when Neovim attaches, detaches or switches mode; what
 * they show is decided by {@link ToggleButtonState}.
 */
public class ToggleNeovimModeHandler extends AbstractHandler implements IElementUpdater {

    /**
     * The command parameter that the toolbar button passes ({@code plugin.xml}) and the menu entry
     * does not: {@link #updateElement} gets an item's parameters, and only the button gets an icon
     * (a menu checkbox with an icon next to it would look odd).
     */
    static final String SHOWS_ICON_PARAMETER = "io.github.jm6cnddxbwftaks.neovim4eclipse.commands.toggleMode.showsIcon";

    /** Loaded once per path; descriptors are cheap, the images are made and freed by Eclipse. */
    private static final Map<String, ImageDescriptor> ICONS = new ConcurrentHashMap<>();

    private final Runnable recompute = this::recomputeEnabled;

    public ToggleNeovimModeHandler() {
        recomputeEnabled();
        ToggleButtonRefresh.addHandler(recompute);
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        NeovimEditorController controller = controllerOfActiveEditor();
        if (controller == null) {
            // Normally unreachable (the command is disabled then), but a refresh may still be on its way.
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Neovim",
                    "Neovim is not attached to this editor. It attaches to text editors by itself; if it"
                            + " did not, it may have failed to start (Window > Preferences > Neovim names the"
                            + " executable). To try again: Neovim > Attach Neovim, or"
                            + " Neovim: Attach to Active Editor in Quick Access (Ctrl+3).");
            return null;
        }
        // No confirmation dialog: the button and the status line show the resulting mode.
        controller.toggleNeovimMode(); // asks ToggleButtonRefresh to redraw the button
        return null;
    }

    /**
     * Called by Eclipse for each toolbar button and menu entry of this command, on the UI thread.
     * Without it, a toggle item would flip its own pressed state on every click and drift out of
     * step with the editor (switch editors, and the button would still show the old one's mode).
     */
    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        ToggleButtonState state = state();
        element.setChecked(state.checked());
        element.setTooltip(state.tooltip());
        if (parameters != null && "true".equals(parameters.get(SHOWS_ICON_PARAMETER))) {
            ImageDescriptor icon = icon(state.icon());
            if (icon != null) {
                element.setIcon(icon);
            }
        }
    }

    /**
     * The icon at {@code path} in this plug-in. {@link ResourceLocator} returns a URL-based
     * descriptor, which loads {@code name@2x.png} by itself on a 200% screen, as plugin.xml icons do.
     */
    private static ImageDescriptor icon(String path) {
        return ICONS.computeIfAbsent(path,
                p -> ResourceLocator.imageDescriptorFromBundle(ToggleNeovimModeHandler.class, p).orElse(null));
    }

    @Override
    public void dispose() {
        ToggleButtonRefresh.removeHandler(recompute);
        super.dispose();
    }

    /** Greys the command out, or enables it, for the active editor; fires a change if it changed. */
    private void recomputeEnabled() {
        setBaseEnabled(controllerOfActiveEditor() != null);
    }

    private static ToggleButtonState state() {
        NeovimEditorController controller = controllerOfActiveEditor();
        return ToggleButtonState.of(editorKind(), controller != null && controller.isNeovimModeActive(),
                WelcomeNotice.toggleKey(),
                WelcomeNotice.keyBinding(ToggleButtonState.ATTACH_COMMAND_ID, null));
    }

    private static Editor editorKind() {
        IEditorPart active = activeEditor();
        if (active == null) {
            return Editor.NONE;
        }
        if (EditorAttachManager.textEditorOf(active) == null) {
            return Editor.NOT_TEXT;
        }
        return controllerOfActiveEditor() != null ? Editor.ATTACHED : Editor.TEXT_WITHOUT_NEOVIM;
    }

    /** The controller of the editor in front, or {@code null} if Neovim is attached elsewhere or nowhere. */
    private static NeovimEditorController controllerOfActiveEditor() {
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        if (controller == null || controller.isClosed()) {
            return null;
        }
        IEditorPart active = activeEditor();
        return active != null && EditorAttachManager.textEditorOf(active) == controller.getEditor() ? controller : null;
    }

    /**
     * The workbench's active editor (the last one used, even while a view has the focus). While a
     * dialog has the focus there is no active window; the first window's editor counts then.
     */
    private static IEditorPart activeEditor() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return null;
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
            window = windows.length > 0 ? windows[0] : null;
        }
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        return page == null ? null : page.getActiveEditor();
    }
}
