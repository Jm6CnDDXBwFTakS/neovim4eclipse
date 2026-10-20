// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TabNavigation;

/**
 * Vim's tab commands ({@code gt}, {@code gT}, {@code :tabn}, {@code :tabp}, {@code :tabfirst},
 * {@code :tablast}) applied to the tabs of the editor area.
 *
 * <p><b>Background: the E4 model.</b> Since Eclipse 4, the workbench window is described by an
 * application model: an editor is an {@link MPart}, and a row of editor tabs is an
 * {@code MPartStack}, the {@link MElementContainer} that is its parent. The children of that stack
 * are in the order the tabs are shown (including tabs the user dragged around), which is the order
 * Vim counts in. The model part of an editor is a service of its site
 * ({@code site.getService(MPart.class)}), and {@link EPartService} activates another part.
 *
 * <p>Only the tabs next to the current editor count: with a split editor area, {@code gt} stays
 * within the current half. The arithmetic is in {@link TabNavigation}. UI thread only.
 */
final class EditorTabs {

    private static final Logger LOG = Logger.getLogger(EditorTabs.class.getName());

    private EditorTabs() {
    }

    /**
     * Activates the tab that {@code action} with {@code count} leads to. When the tabs cannot be
     * found, says so in the status line: the command was taken from Neovim, so silence would look
     * like a key that does nothing.
     *
     * @return {@code false} if the tabs could not be found (nothing changed)
     */
    static boolean go(ITextEditor editor, TabNavigation.Action action, int count) {
        try {
            // A multi-page editor (e.g. the POM editor) is the tab; the attached text editor is a page in it.
            IEditorPart tab = EditorAttachManager.getInstance().workbenchPartOf(editor);
            IWorkbenchPartSite site = tab.getSite();
            MPart current = site.getService(MPart.class);
            EPartService partService = site.getService(EPartService.class);
            if (current == null || partService == null || current.getParent() == null) {
                LOG.fine("No E4 model for the editor's tab; tab command ignored");
                HostCommands.showError(editor, "Cannot switch tabs from this editor");
                return false;
            }
            List<MPart> tabs = visibleParts(current.getParent());
            int index = tabs.indexOf(current);
            int target = TabNavigation.target(action, index, tabs.size(), count);
            if (target >= 0 && target != index) {
                partService.activate(tabs.get(target), true);
            }
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Tab command " + action + " failed", e);
            HostCommands.showError(editor, "Switching tabs failed: " + e.getMessage());
            return false;
        }
    }

    /** The parts of a tab stack that have a tab: rendered and visible, in tab order. */
    private static List<MPart> visibleParts(MElementContainer<MUIElement> stack) {
        List<MPart> parts = new ArrayList<>();
        for (MUIElement child : stack.getChildren()) {
            if (child instanceof MPart part && part.isToBeRendered() && part.isVisible()) {
                parts.add(part);
            }
        }
        return parts;
    }
}
