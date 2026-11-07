// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SaveAsFrames}: a Save As is told by its methods on the call stack, here methods of the
 * same names as Eclipse's ({@code IEditorPart.doSaveAs}, {@code AbstractTextEditor.performSaveAs}).
 */
class SaveAsFramesTest {

    /** As AbstractTextEditor.performSaveAs: the new input is set (and heard) a few calls deeper. */
    private boolean performSaveAs() {
        return setInput();
    }

    /** As IEditorPart.doSaveAs, the workbench's entry. */
    private boolean doSaveAs() {
        return setInput();
    }

    /** As a reused editor's setInput, or the one inside a Save As: the input listener asks. */
    private boolean setInput() {
        return SaveAsFrames.onStack();
    }

    @Test
    void anInputSetInsideASaveAsIsOne() {
        assertTrue(performSaveAs(), "performSaveAs, as from the deleted-file prompt");
        assertTrue(doSaveAs(), "doSaveAs, as from File > Save As");
    }

    @Test
    void anInputSetOutsideIsNot() {
        assertFalse(setInput(), "a reused editor's new input");
    }

    @Test
    void onlyTheTwoNamesCount() {
        assertTrue(SaveAsFrames.isSaveAs("doSaveAs"));
        assertTrue(SaveAsFrames.isSaveAs("performSaveAs"));
        assertFalse(SaveAsFrames.isSaveAs("doSave"));
        assertFalse(SaveAsFrames.isSaveAs("setInput"));
    }
}
