// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Whether a place an editor showed is still a file on this machine. Plain {@code java.nio}, so
 * it is tested in {@code tests/unit} ({@code LocalFilesTest}). {@code editor/NavigationHistorySteps}
 * asks it when {@code Ctrl-O} or {@code Ctrl-I} reaches a place whose file may be gone, and
 * {@code editor/EditorAttachManager} when an editor's input changed ({@link InputRebind}).
 */
public final class LocalFiles {

    private static final Logger LOG = Logger.getLogger(LocalFiles.class.getName());

    private LocalFiles() {
    }

    /**
     * Whether {@code uri} names a local file ({@code file:}) that does not exist. Anything that is
     * not a plain local path counts as there: {@code null}, another scheme, or a {@code file:} URI
     * that Java cannot turn into a path.
     *
     * <p>Example of the last: {@code file://server/share/notes.txt}. On Windows it is the UNC path
     * {@code \\server\share\notes.txt} and is checked; on Linux and macOS {@link Path#of(URI)}
     * refuses a {@code file:} URI with a host ({@link IllegalArgumentException}, "URI has an
     * authority component"). Such a place may well exist (a share Eclipse reaches through another
     * file system), and a place that counts as gone is closed ({@code NavigationHistorySteps}), so
     * the safe answer is "there": the step stays an ordinary step. Uncaught, the exception would
     * end {@code Ctrl-O} halfway, inside a posted runnable (Eclipse logs "Unhandled event loop
     * exception"), without telling Neovim's trail which steps went nowhere, so the two histories
     * would no longer match.
     */
    public static boolean isMissing(URI uri) {
        if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        try {
            return !Files.exists(Path.of(uri));
        } catch (IllegalArgumentException | FileSystemNotFoundException | SecurityException e) {
            LOG.log(Level.FINE, "Not a local path, taken as there: " + uri, e);
            return false;
        }
    }

    /**
     * Whether {@code name}, a Neovim buffer's name, is an absolute path no file has: the file was
     * renamed or moved. A name that is no absolute path (a URI, an untitled document's name, empty)
     * counts as there. Example: {@code /home/you/a.txt} after a.txt was renamed to b.txt is missing;
     * {@code Untitled 1} is not.
     */
    public static boolean isMissingPath(String name) {
        try {
            return !name.isEmpty() && Path.of(name).isAbsolute() && !Files.exists(Path.of(name));
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
