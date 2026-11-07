// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.nio.file.Path;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.ui.IPersistableElement;
import org.eclipse.ui.ide.FileStoreEditorInput;

/**
 * The input of a new file's editor whose file is a dangling symbolic link ({@link DanglingLinks}):
 * the editor reads and writes the link's missing target, and is named after the link. Example:
 * {@code :e notes.local}, with {@code notes.local} a link to {@code /tmp/gone.txt}, opens an empty
 * editor titled {@code notes.local} on {@code /tmp/gone.txt}.
 *
 * <p><b>Why not on the link.</b> A text editor reads its file when it opens, through Eclipse's file
 * system, which on Windows does not follow the link ({@code FindFirstFile} finds the link itself):
 * the link counts as an existing file there, its workspace file is listed as existing after a
 * refresh, and the read then fails, so the editor shows "File not found: ...\notes.local." in front
 * of its text, and nothing can be typed. On Linux and macOS Eclipse's file system follows the link,
 * and the editor on the link opened as a new file. The missing target reads as a missing file on
 * every system, so this works the same everywhere, and its write creates the target, as a write
 * through the link would (only with {@code !}: {@code NewFiles.write} checks the link, not the
 * target).
 *
 * <p>The plug-in knows the file by the link ({@link #link}): the Neovim buffer's name
 * ({@code EditorAttachManager.fileOf}), the workspace file the first write switches to
 * ({@code NewFiles.missingFileOf}), the {@code !} rule. Eclipse's own code sees the target
 * ({@link #getURI}). Not kept across a restart ({@link #getPersistable}): it would come back as an
 * editor on the target, under the target's name. Equal to any input on the same target
 * ({@link FileStoreEditorInput#equals}), so two links to one missing file share one editor, as
 * they would share the file once it is written.
 */
final class DanglingLinkInput extends FileStoreEditorInput {

    private final Path link;

    /**
     * @param link   the dangling link, as opened ({@code /ws/proj/notes.local})
     * @param target its missing target, {@link DanglingLinks#finalTarget}
     */
    DanglingLinkInput(Path link, Path target) {
        super(EFS.getLocalFileSystem().getStore(target.toUri()));
        this.link = link;
    }

    /** The link the editor was opened on. */
    Path link() {
        return link;
    }

    /** The link's name, the one the user typed: {@code notes.local}, not {@code gone.txt}. */
    @Override
    public String getName() {
        return link.getFileName().toString();
    }

    @Override
    public String getToolTipText() {
        return link.toString();
    }

    @Override
    public IPersistableElement getPersistable() {
        return null;
    }
}
