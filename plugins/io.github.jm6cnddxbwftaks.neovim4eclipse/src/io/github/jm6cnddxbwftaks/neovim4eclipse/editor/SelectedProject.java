// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;

/**
 * The project selected in the workbench window's project tree, for an editor that has no file of
 * its own: an untitled document (<i>File → New → Untitled Text File</i>). Its Neovim window works
 * in that project's root ({@code :lcd}), so {@code :w c.txt} writes {@code c.txt} into the project
 * the user right-clicked, not into the folder of the editor that was active before.
 *
 * <p>Why the selection: Eclipse's untitled document has no file, so its editor input says nothing
 * about a project, and the wizard ignores the selection it is given. The project tree's selection
 * is what Eclipse itself shows as "the" project at that moment. Example: {@code a.txt} of project
 * {@code mt} is open; right-click project {@code mtplugin}, <i>New → Untitled Text File</i>,
 * {@code :w c.txt}: the file is {@code mtplugin/c.txt} (found 2026-10-31; before, {@code mt/c.txt}).
 *
 * <p>The trees are looked up by id, not through the active part: when the editor's window opens,
 * the new editor is already the active part, and its selection is text. The Project Explorer comes
 * first, then JDT's Package Explorer (named by id, so the plug-in needs no JDT); a tree that is
 * visible wins over one in a hidden tab. A file or folder selected inside a project counts as its
 * project. Nothing selected, or a closed project: {@code null}, and the window takes the folder of
 * the editor before it, as Vim's windows inherit theirs. UI thread.
 */
final class SelectedProject {

    /** The project trees, in the order they are asked. */
    private static final List<String> TREES = List.of(IPageLayout.ID_PROJECT_EXPLORER,
            "org.eclipse.jdt.ui.PackageExplorer");

    private SelectedProject() {
    }

    /** The root folder of the project selected in {@code window}'s project tree, or {@code null}. */
    static Path root(IWorkbenchWindow window) {
        if (window == null) {
            return null;
        }
        ISelectionService selections = window.getSelectionService();
        IWorkbenchPage page = window.getActivePage();
        Path hidden = null;
        for (String tree : TREES) {
            Path root = rootOf(selections.getSelection(tree));
            if (root == null) {
                continue;
            }
            IViewPart view = page == null ? null : page.findView(tree);
            if (view != null && page.isPartVisible(view)) {
                return root;
            }
            if (hidden == null) {
                hidden = root;
            }
        }
        return hidden;
    }

    /** The root of the project of the selection's first element, or {@code null}. */
    private static Path rootOf(ISelection selection) {
        if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty()) {
            return null;
        }
        IResource resource = Adapters.adapt(structured.getFirstElement(), IResource.class);
        IProject project = resource == null ? null : resource.getProject();
        if (project == null || !project.isOpen()) {
            return null; // the workspace root, or a closed project
        }
        IPath location = project.getLocation();
        return location == null ? null : Path.of(location.toOSString()); // null: not on the local disk
    }
}
