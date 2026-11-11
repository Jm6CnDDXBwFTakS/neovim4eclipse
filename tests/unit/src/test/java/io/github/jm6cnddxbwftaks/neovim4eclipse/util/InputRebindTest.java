// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link InputRebind}: when an editor is given another document, whether its Neovim buffer moves
 * with it, and whether the attached editor is attached again. The rename, Save As and reused-editor
 * cases through a real workbench are {@code EditorEndToEndTest}.
 */
class InputRebindTest {

    /** A rename or move: the old file is gone, nothing else shows either document. */
    private static final InputRebind.Facts RENAMED = new InputRebind.Facts(true, true, false, false, true);

    @Test
    void aRenamedFileKeepsItsBuffer() {
        assertTrue(InputRebind.keepsBuffer(RENAMED));
    }

    /** A Save As: the old file is still there, but the new input came inside the Save As. */
    @Test
    void aSaveAsKeepsTheBufferThoughTheOldFileIsThere() {
        assertTrue(InputRebind.keepsBuffer(new InputRebind.Facts(true, false, true, false, true)));
    }

    /**
     * An editor reused for another file (a search's next match): both files are there and there was
     * no Save As, so the new file gets its own buffer, not the old one's undo history. Whatever the
     * texts are: two empty files look the same.
     */
    @Test
    void anEditorReusedForAnotherFileDoesNot() {
        assertFalse(InputRebind.keepsBuffer(new InputRebind.Facts(true, false, false, false, true)));
    }

    @Test
    void aDocumentWithoutABufferHasNothingToKeep() {
        assertFalse(InputRebind.keepsBuffer(new InputRebind.Facts(false, true, true, false, true)));
    }

    /** The new document is open in another editor with a buffer of its own: two buffers cannot be one. */
    @Test
    void aNewDocumentWithABufferOfItsOwnDoesNotTakeTheOldOne() {
        assertFalse(InputRebind.keepsBuffer(new InputRebind.Facts(true, true, false, true, true)));
    }

    /** A clone still on the old document keeps it, and its buffer, alive. */
    @Test
    void anEditorLeftOnTheOldDocumentKeepsTheBufferThere() {
        assertFalse(InputRebind.keepsBuffer(new InputRebind.Facts(true, true, false, false, false)));
    }

    @Test
    void onlyAnEditorAttachedToTheOldDocumentIsAttachedAgain() {
        assertFalse(InputRebind.reattaches(false, true, true, true));
        assertFalse(InputRebind.reattaches(false, false, true, true));
    }

    /** When the buffer moves, any of its editors that was attached (this one or a clone) is attached again. */
    @Test
    void aMovedBufferReattachesWhicheverOfItsEditorsWasAttached() {
        assertTrue(InputRebind.reattaches(true, true, true, false), "a clone");
        assertTrue(InputRebind.reattaches(true, true, true, true), "this editor");
        assertFalse(InputRebind.reattaches(true, true, false, false), "another document's editor");
    }

    /** When it does not, only this editor is: a clone stays on the old document, attached as it was. */
    @Test
    void aReleasedBufferReattachesOnlyThisEditor() {
        assertTrue(InputRebind.reattaches(true, false, true, true));
        assertFalse(InputRebind.reattaches(true, false, true, false), "a clone on the old document");
    }
}
