// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;

/**
 * A text editor whose Save As asks nothing: registered for {@code *.saveas} files in
 * {@code fragment.xml}, for the test of File &gt; Save As keeping the Neovim buffer. Eclipse's own
 * {@code performSaveAs} opens a dialog for the new name; this one takes {@link #target} and does
 * what Eclipse's does after the dialog: the document provider saves the text to the new input, and
 * the editor is given that input. Run through the Save As command, as from the File menu, so that
 * the command's listeners (the plug-in's {@code SaveAsWatch}) hear it as they would.
 */
public class TestSaveAsEditor extends TextEditor {

    /** The file the next Save As writes. Set by the test before it runs the command. */
    static volatile IFile target;

    @Override
    protected void performSaveAs(IProgressMonitor monitor) {
        IEditorInput newInput = new FileEditorInput(target);
        IDocumentProvider provider = getDocumentProvider();
        try {
            provider.aboutToChange(newInput);
            provider.saveDocument(monitor, newInput, provider.getDocument(getEditorInput()), true);
        } catch (CoreException e) {
            throw new IllegalStateException("Save As to " + target + " failed", e);
        } finally {
            provider.changed(newInput);
        }
        setInput(newInput);
    }
}
