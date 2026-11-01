// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Turns a multi-page editor to its text page when Neovim moves into its file.
 *
 * <p>A multi-page editor (the plug-in manifest editor of {@code plugin.xml} and {@code MANIFEST.MF},
 * the Maven POM editor) has form pages next to one or more text pages, and may open on a form page:
 * the manifest editor opens on the page it showed last. On a form page Neovim lets go of the keys
 * (EditorAttachManager, "switched to a form page"), which is right when the user clicked that tab.
 * When Neovim itself moved there ({@code :cnext} through the workspace's problems, {@code gf},
 * {@code :b}), it is not: the user meant to keep editing the file in Neovim, and their next
 * {@code :cnext} would go to the form page's widgets instead. Example: on GTK, typing {@code :clist}
 * into the manifest editor's Extensions tree opens GTK's type-to-search box under the tree.
 *
 * <p>The text page to show is the one that edits the file Neovim moved into, found with the
 * editor's public {@link MultiPageEditorPart#findEditors(IEditorInput)}: one editor can have several
 * text pages, each for its own file (the manifest editor shows {@code MANIFEST.MF},
 * {@code plugin.xml} and {@code build.properties}). The protected {@code setActivePage(int)} is not
 * needed, as {@link MultiPageEditorPart#setActiveEditor(IEditorPart)} turns to a nested editor's page.
 */
final class TextPages {

    private TextPages() {
    }

    /**
     * Shows the text page of {@code editor} that edits {@code input}, when {@code editor} is a
     * multi-page editor on another page (a form page, or another file's text page). Nothing
     * otherwise: a plain text editor, a multi-page editor already on that page, or one with no text
     * page for {@code input}. UI thread.
     *
     * @param input the file Neovim moved into, as an editor input ({@link #inputOf(IFile)}); {@code null}
     *        does nothing
     */
    static void show(IEditorPart editor, IEditorInput input) {
        if (!(editor instanceof MultiPageEditorPart multiPage) || input == null) {
            return;
        }
        ITextEditor shown = EditorAttachManager.textEditorOf(multiPage);
        if (shown != null && input.equals(shown.getEditorInput())) {
            return; // already there
        }
        for (IEditorPart page : multiPage.findEditors(input)) {
            if (page instanceof ITextEditor) {
                multiPage.setActiveEditor(page);
                return;
            }
        }
    }

    /** {@code file} as the editor input a text page for it has. */
    static IEditorInput inputOf(IFile file) {
        return file == null ? null : new FileEditorInput(file);
    }

    /**
     * The editor input of the workspace file whose text {@code document} is, or {@code null} for a
     * document of no workspace file (a file outside the workspace, an untitled one). Eclipse's
     * file buffers know each open file's document; {@link FileBuffers#getWorkspaceFileAtLocation}
     * takes either kind of location a buffer has (a workspace path or a path on disk).
     */
    static IEditorInput inputOf(IDocument document) {
        ITextFileBuffer buffer = document == null ? null
                : FileBuffers.getTextFileBufferManager().getTextFileBuffer(document);
        IPath location = buffer == null ? null : buffer.getLocation();
        return inputOf(location == null ? null : FileBuffers.getWorkspaceFileAtLocation(location));
    }
}
