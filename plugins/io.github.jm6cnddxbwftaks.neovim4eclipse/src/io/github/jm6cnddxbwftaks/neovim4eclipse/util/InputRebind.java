// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * What happens to an attached editor's Neovim buffer when the editor is given another document
 * (Eclipse's {@code setInput}): the buffer moves with the editor, or the editor gives the old
 * document up and the new one gets a buffer of its own. The rules of
 * {@code editor/EditorAttachManager.rebind}, without Eclipse, so that {@code tests/unit} tests each
 * case ({@code InputRebindTest}); the manager finds the facts.
 *
 * <p><b>Why it matters.</b> A buffer that moves keeps its number, undo history and marks, and is
 * renamed after the new file: right for a file that was renamed, moved or saved under another name,
 * which is still the same text the user worked on. Wrong for an editor Eclipse reuses for another
 * file (search results open each match in the same editor): that file would get the old file's
 * undo history, and {@code u} would turn it into the old file. Two empty files have the same text,
 * so the text cannot tell the cases apart; where the old file went can.
 *
 * <p>Example: rename {@code a.txt} to {@code b.txt} in the Project Explorer. Eclipse moves the file,
 * then gives the editor {@code b.txt}; {@code a.txt} is gone, so the buffer moves and is renamed.
 * A search that shows its next match, in another file, in the same editor: both files are there,
 * there was no Save As, so the new file gets its own buffer.
 */
public final class InputRebind {

    private InputRebind() {
    }

    /**
     * What the manager knows when an editor's input changed.
     *
     * @param hasBuffer            the old document has a Neovim buffer
     * @param oldFileGone          the file the buffer is named after no longer exists (renamed or
     *                             moved: Eclipse moved it, then gave the editor the new one)
     * @param duringSaveAs         the new input came during a Save As (the command, or inside the
     *                             editor's {@code doSaveAs} or {@code performSaveAs})
     * @param newDocumentHasBuffer the new document has a buffer already (another editor shows it)
     * @param everyEditorMoved     every editor of the old document shows the new one now. Clones
     *                             (Window › Editor › Clone) share a document, and each moves with
     *                             the file by its own {@code setInput}, all in one notification.
     */
    public record Facts(boolean hasBuffer, boolean oldFileGone, boolean duringSaveAs, boolean newDocumentHasBuffer,
            boolean everyEditorMoved) {
    }

    /**
     * Whether the old document's buffer moves to the new document: the editor moved with its file
     * (the old file is gone, or a Save As), the new document has no buffer to clash with, and no
     * editor is left on the old document.
     */
    public static boolean keepsBuffer(Facts facts) {
        boolean movedWithItsFile = facts.hasBuffer() && (facts.oldFileGone() || facts.duringSaveAs());
        return movedWithItsFile && !facts.newDocumentHasBuffer() && facts.everyEditorMoved();
    }

    /**
     * Whether the attached editor is attached again after the change: only when it was attached to
     * the old document, and then, if the buffer moves, when it is one of the editors that move (this
     * one or a clone), else only when it is this editor.
     *
     * @param attachedToOldDocument the attached editor shows the old document
     * @param keepsBuffer           {@link #keepsBuffer}'s answer
     * @param attachedSharesOld     the attached editor is one of the old document's editors
     * @param attachedIsThisEditor  the attached editor is the one whose input changed
     */
    public static boolean reattaches(boolean attachedToOldDocument, boolean keepsBuffer, boolean attachedSharesOld,
            boolean attachedIsThisEditor) {
        return attachedToOldDocument && (keepsBuffer ? attachedSharesOld : attachedIsThisEditor);
    }
}
