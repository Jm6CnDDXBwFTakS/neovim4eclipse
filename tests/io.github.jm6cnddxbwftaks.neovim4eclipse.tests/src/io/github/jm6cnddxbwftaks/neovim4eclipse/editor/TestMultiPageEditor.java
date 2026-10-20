// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.part.MultiPageEditorPart;

/**
 * A stand-in for a multi-page editor such as the Maven POM editor: a text page (Source) and a
 * page without text (Overview). Registered for {@code *.mpage} files in {@code fragment.xml}, so
 * only the UI tests open it. The editor tab is this part, while the plug-in drives the text page
 * inside it ({@code EditorAttachManager.textEditorOf}); tab commands must use the tab.
 */
public class TestMultiPageEditor extends MultiPageEditorPart {

    private TextEditor source;

    @Override
    protected void createPages() {
        try {
            source = new TextEditor();
            setPageText(addPage(source, getEditorInput()), "Source");
        } catch (PartInitException e) {
            throw new IllegalStateException(e);
        }
        Composite overview = new Composite(getContainer(), SWT.NONE);
        new Label(overview, SWT.NONE).setText("A page without text");
        setPageText(addPage(overview), "Overview");
        setPartName(getEditorInput().getName());
    }

    @Override
    public void doSave(IProgressMonitor monitor) {
        source.doSave(monitor);
    }

    @Override
    public void doSaveAs() {
        // not needed by the tests
    }

    @Override
    public boolean isSaveAsAllowed() {
        return false;
    }
}
