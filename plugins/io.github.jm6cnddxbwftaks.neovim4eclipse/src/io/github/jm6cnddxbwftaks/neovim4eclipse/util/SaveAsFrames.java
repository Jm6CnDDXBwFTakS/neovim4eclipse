// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.Set;

/**
 * Tells whether the current thread is inside an editor's Save As: a method named
 * {@code doSaveAs} or {@code performSaveAs} is on its call stack. Read by
 * {@code editor/SaveAsWatch} when an editor gets a new input, to tell a Save As from an editor
 * Eclipse reuses for another file.
 *
 * <p>Why the call stack: every Save As of an Eclipse text editor sets the new input inside these
 * two methods, whatever started it, and nothing else does. {@code IEditorPart.doSaveAs} is the
 * workbench's entry (File &gt; Save As, an editor's own Save As action, another plug-in);
 * {@code AbstractTextEditor.performSaveAs} is where the text editor does the work, and the paths
 * that skip {@code doSaveAs} go there too: the prompt for a file deleted while open
 * ({@code handleEditorInputChanged}), and Save of a deleted file ({@code doSave}). Each ends with
 * {@code setInput(newInput)}, which fires the editor's {@code PROP_INPUT} synchronously, so the
 * listener still runs inside the method. Neither the document provider nor the editor sends an
 * event of its own for it: {@code TextFileDocumentProvider} fires {@code elementMoved} only for a
 * file moved in the workspace (a rename), and saving to the new input creates that file without
 * telling the old one's listeners. Comparing the texts misfires on a reused editor with the same
 * text (two empty files).
 *
 * <p>Example: Save As of {@code a.txt} as {@code b.txt} from the deleted-file prompt; at the new
 * input the stack holds {@code ...performSaveAs, handleEditorInputChanged...}, so the buffer and
 * its undo history move to {@code b.txt}. {@code b.txt} opened from a search result in a reused
 * editor has neither method on the stack, and gets a buffer of its own.
 *
 * <p>No Eclipse types: matched by name, so that subclasses' overrides
 * ({@code CompilationUnitEditor.performSaveAs}, a multi-page editor's {@code doSaveAs}) count as
 * well, and tested without Eclipse ({@code SaveAsFramesTest}).
 */
public final class SaveAsFrames {

    /** The method names of a Save As, in Eclipse's editor API. */
    private static final Set<String> SAVE_AS_METHODS = Set.of("doSaveAs", "performSaveAs");

    private SaveAsFrames() {
    }

    /**
     * Whether the current thread runs inside a Save As. Walks the stack, so cheap enough for an
     * input change (rare), not for every key.
     */
    public static boolean onStack() {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> isSaveAs(f.getMethodName())));
    }

    /** Whether {@code methodName} is one of a Save As ({@code doSaveAs}, {@code performSaveAs}). */
    static boolean isSaveAs(String methodName) {
        return SAVE_AS_METHODS.contains(methodName);
    }
}
