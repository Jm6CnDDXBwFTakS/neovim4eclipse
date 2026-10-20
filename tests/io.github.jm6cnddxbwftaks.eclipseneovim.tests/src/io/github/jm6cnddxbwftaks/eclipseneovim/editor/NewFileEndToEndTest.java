// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import static io.github.jm6cnddxbwftaks.eclipseneovim.editor.UiTestSupport.type;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.eclipse.core.resources.IFile;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Test;

/**
 * Editors on files that do not exist yet, as {@code :e newfile} opens them (docs/NEW_FILE.md).
 */
class NewFileEndToEndTest extends EndToEndTestBase {

    /**
     * The first key typed in a new workspace file used to be lost: {@code ihello} gave {@code ello}.
     * An existing empty file and a new file outside the workspace did not lose it.
     */
    @Test
    void theFirstKeyTypedInANewWorkspaceFileIsKept() throws Exception {
        IFile missing = project.getFile("new.txt");
        assertFalse(missing.exists());
        ITextEditor opened = (ITextEditor) IDE.openEditor(page(), missing, TEXT_EDITOR, true);
        waitForAttach(opened, "new.txt");
        StyledText target = widgetOf(opened);

        type(target, 'i');
        settle(); // insert mode reached Eclipse, so Eclipse types what follows (not Neovim)
        type(target, "hello");
        UiTestSupport.escape(target);
        expectText(opened, "hello");
        assertFalse(missing.exists(), "typing writes nothing");
        page().closeEditor(opened, false);
    }
}
