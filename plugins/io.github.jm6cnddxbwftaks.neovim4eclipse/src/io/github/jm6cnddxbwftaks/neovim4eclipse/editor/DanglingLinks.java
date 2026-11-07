// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Symbolic links whose target does not exist ("dangling" links), which a write would create.
 *
 * <p><b>Why they need care.</b> {@link Files#exists} follows links, so a dangling link counts as a
 * missing file. A file the plug-in thinks is missing opens as a new, empty editor ({@code :e},
 * {@code "notes.local" [New]}), and its first {@code :w} creates the file; a copy to a missing file
 * ({@code :wq other.txt}) needs no {@code !}. Both writes go through the link and create its
 * target, wherever it points. A cloned repository can ship such a link: {@code notes.local} pointing
 * to {@code ~/.config/autostart/x.desktop}, and {@code :e notes.local}, some text, {@code :w} would
 * write a program that starts at the next login.
 *
 * <p><b>The rule.</b> Writing through a dangling link needs {@code !} ({@code :w!}, {@code :wq!
 * other.txt}), as overwriting an existing file does; without it the write is refused with a message
 * that names the link's target ({@link #refusal}). Opening one is allowed, as in Vim: reading
 * writes nothing.
 *
 * <p>Only {@code java.nio}, so {@code tests/text} tests it on real links in a temporary folder.
 */
final class DanglingLinks {

    private DanglingLinks() {
    }

    /**
     * Whether {@code path} is a symbolic link whose target does not exist. Example: {@code a.txt}
     * linked to {@code missing.txt} is; linked to an existing file it is not, nor is a plain missing
     * file. A path the security manager or the file system refuses to look at counts as no link.
     */
    static boolean isDangling(Path path) {
        try {
            return Files.isSymbolicLink(path) && !Files.exists(path);
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * The message for a write refused at a dangling link. Example:
     * {@code "notes.local" is a link to /home/you/.config/autostart/x.desktop, which does not exist; add ! to write there}.
     *
     * @param shown the name as the user knows it ({@code notes.local})
     */
    static String refusal(String shown, Path link) {
        return "\"" + shown + "\" is a link to " + targetOf(link) + ", which does not exist; add ! to write there";
    }

    /**
     * The note a new editor's {@code [New]} message gets for a dangling link, so the user knows
     * where a {@code :w!} would write. Example: {@code (a link to /tmp/gone.txt)}.
     */
    static String newFileNote(Path link) {
        return "(a link to " + targetOf(link) + ")";
    }

    /** The link's target as it is written in the link (relative to the link's folder if relative), or "?". */
    private static String targetOf(Path link) {
        try {
            return Files.readSymbolicLink(link).toString();
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return "?";
        }
    }
}
