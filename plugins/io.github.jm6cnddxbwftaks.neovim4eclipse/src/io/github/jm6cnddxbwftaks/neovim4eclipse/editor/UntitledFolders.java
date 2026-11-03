// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import org.eclipse.ui.IEditorInput;

/**
 * The folder an untitled editor opened by {@code :enew} works in: the folder of the editor
 * {@code :enew} was typed in, as a Vim window keeps its folder when {@code :enew} empties it.
 * Example: in {@code app/src/Main.java}, {@code :enew} then {@code :w notes.txt} writes
 * {@code app/notes.txt}, whatever the Project Explorer has selected. An untitled editor opened
 * from Eclipse's menu has no entry here and takes the selected project's folder instead
 * ({@link SelectedProject}).
 *
 * <p>Keyed by the editor's input, weakly: the entry goes when the editor and its input do. A
 * {@code null} folder is kept too (the editor before had none): the new window then inherits the
 * one of the window before it ({@code DocumentBuffers.folderOfNewWindow}). UI thread.
 */
final class UntitledFolders {

    private static final Map<IEditorInput, Optional<Path>> FOLDERS = new WeakHashMap<>();
    /**
     * Set while {@code :enew} runs the wizard: the editor it opens may attach (and ask for its
     * folder) before the wizard returns it, so the first untitled editor asking meanwhile takes this.
     */
    private static Optional<Path> opening;

    private UntitledFolders() {
    }

    /** Remembers {@code folder} (may be {@code null}) for the untitled editor of {@code input}. */
    static void put(IEditorInput input, Path folder) {
        if (input != null) {
            FOLDERS.put(input, Optional.ofNullable(folder));
        }
    }

    /** {@code :enew} is about to open an untitled editor that works in {@code folder}; see {@link #opening}. */
    static void opening(Path folder) {
        opening = Optional.ofNullable(folder);
    }

    /** The wizard returned: an untitled editor asking from now on was not opened by {@code :enew}. */
    static void opened() {
        opening = null;
    }

    /**
     * The folder remembered for the untitled editor of {@code input}: empty when there is no entry
     * (not opened by {@code :enew}); an empty {@code Optional} inside when the entry says "none".
     */
    static Optional<Optional<Path>> get(IEditorInput input) {
        if (input == null) {
            return Optional.empty();
        }
        if (!FOLDERS.containsKey(input) && opening != null) {
            FOLDERS.put(input, opening); // the editor :enew is opening, attached before the wizard returned
        }
        return Optional.ofNullable(FOLDERS.get(input));
    }
}
