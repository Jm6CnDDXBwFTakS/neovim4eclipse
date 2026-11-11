// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * What {@code :w name}, {@code :wq name} and {@code :x name} do when Eclipse carries them out
 * ({@code editor/EclipseFileCommands.writeCopy}): write nothing, save the editor's own file, refuse,
 * or write a copy. Neovim's own {@code :w other.txt} makes these checks before it asks Eclipse; for
 * {@code :wq name} the command is Eclipse's, so the plug-in makes them, in Vim's order. Pure, so
 * {@code tests/unit} tests every case ({@code WriteCopyChoiceTest}); the caller reads the editor
 * and the disk.
 *
 * <p>The checks, in order:
 * <ol>
 *   <li>{@code :x} ({@code onlyIfChanged}) with no unsaved changes writes nothing: {@link #NOTHING}.</li>
 *   <li>A name with no folder to resolve it against (a relative name for an editor without a file)
 *       cannot be written: {@link #NO_FOLDER}.</li>
 *   <li>The editor's own file under another spelling (other case on Windows and macOS, a link, a
 *       junction) is a save, as Vim's {@code :wq} of that name writes the buffer to its own file:
 *       {@link #SAVE_OWN}.</li>
 *   <li>Without {@code !}, an existing file is Vim's {@code E13}: {@link #FILE_EXISTS}.</li>
 *   <li>Without {@code !}, a symbolic link to a missing file is refused too ({@code DanglingLinks}):
 *       {@code Files.exists} follows the link and finds nothing, and the copy would create the
 *       link's target, wherever it points. {@link #DANGLING_LINK}.</li>
 *   <li>Else the copy is written (an untitled editor is saved as the file instead):
 *       {@link #COPY}.</li>
 * </ol>
 *
 * <p>Example: {@code :wq Notes.txt} in an editor on {@code notes.txt} on macOS is {@link #SAVE_OWN}
 * (one file, two spellings); {@code :wq other.txt} with other.txt there is {@link #FILE_EXISTS}, and
 * {@code :wq! other.txt} is {@link #COPY}.
 */
public enum WriteCopyChoice {

    /** Nothing to write ({@code :x} without unsaved changes): done, and the editor may close. */
    NOTHING,
    /** The name cannot be resolved: there is no folder to put it in. */
    NO_FOLDER,
    /** The name is the editor's own file: save the editor, no copy. */
    SAVE_OWN,
    /** Vim's E13: the file exists, and {@code !} was not given. */
    FILE_EXISTS,
    /** A link to a missing file, without {@code !}: the copy would create the link's target. */
    DANGLING_LINK,
    /** Write the copy (or save an untitled editor as the file). */
    COPY;

    /** Vim's error for {@link #FILE_EXISTS}. */
    public static final String E13 = "E13: File exists (add ! to override)";

    /**
     * What is known of the target, asked only as far as the checks go. {@link #isOwnFile} is always
     * asked (it resolves links, so it reads the disk too); {@link #exists} and
     * {@link #isDanglingLink} are not asked for the editor's own file, nor with {@code !} given.
     */
    public interface Target {
        /** Whether the target is the editor's own file, by the one rule for paths ({@link FilePaths}). */
        boolean isOwnFile();

        /** Whether a file is there ({@code Files.exists}, which follows links). */
        boolean exists();

        /** Whether it is a symbolic link to nothing ({@code DanglingLinks.isDangling}). */
        boolean isDanglingLink();
    }

    /**
     * @param dirty         whether the editor has unsaved changes
     * @param onlyIfChanged {@code :x}: nothing is written without changes
     * @param force         {@code !} was given
     * @param target        the resolved name, or {@code null} when it could not be resolved
     */
    public static WriteCopyChoice of(boolean dirty, boolean onlyIfChanged, boolean force, Target target) {
        if (onlyIfChanged && !dirty) {
            return NOTHING;
        }
        if (target == null) {
            return NO_FOLDER;
        }
        if (target.isOwnFile()) {
            return SAVE_OWN;
        }
        if (!force && target.exists()) {
            return FILE_EXISTS;
        }
        if (!force && target.isDanglingLink()) {
            return DANGLING_LINK;
        }
        return COPY;
    }
}
