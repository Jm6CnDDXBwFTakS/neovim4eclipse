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

    /** Links followed before {@link #finalTarget} gives up, as the kernel's limit for a chain (Linux: 40). */
    private static final int MAX_HOPS = 40;

    /**
     * The missing file a write through the dangling link {@code link} would create: its target,
     * followed through further links, with a relative target taken from the link's folder. Example:
     * {@code /ws/proj/notes.local} to {@code ../gone.txt} gives {@code /ws/proj/../gone.txt}; to
     * {@code next.local}, itself a link to {@code /tmp/gone.txt}, gives {@code /tmp/gone.txt}.
     * {@code null} when {@code link} is not dangling, or the chain cannot be read or is too long
     * (a loop).
     *
     * <p>Why: an editor for a new file that is such a link is opened on this path, not on the link
     * ({@code NewFiles.open}). On Windows, Eclipse's file system finds the link itself and so counts
     * it as an existing file, and reading it then fails ("File not found"), so an editor on the link
     * shows that error instead of its text. The missing target reads as missing everywhere.
     */
    static Path finalTarget(Path link) {
        if (!isDangling(link)) {
            return null;
        }
        try {
            Path current = link.toAbsolutePath();
            for (int hop = 0; hop < MAX_HOPS && Files.isSymbolicLink(current); hop++) {
                Path parent = current.getParent();
                Path target = Files.readSymbolicLink(current);
                // Not normalized: "dir/../x" is then resolved by the file system, as it resolves the
                // link's own target, also where dir is itself a link.
                current = parent == null ? target : parent.resolve(target);
            }
            return Files.isSymbolicLink(current) ? null : current;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return null;
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
