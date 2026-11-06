// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The <em>tree hash</em> of a Neovim install: one SHA-256 that stands for every file in it, so that
 * the plug-in runs the dedicated Neovim only if each of its files is exactly what Neovim's release
 * archive holds (docs/design/DOWNLOAD_NEOVIM.md, "Checked at start").
 *
 * <h2>The rule</h2>
 * <ol>
 *   <li>Every regular file below the install's folder ({@code neovim/la}), by its path relative to
 *       that folder with {@code /} between names: {@code bin/nvim}, {@code share/nvim/runtime/filetype.lua}.
 *       Folders count only through their files. Files named like those the operating system adds
 *       on its own ({@link PluginNeovim#isSystemFile}: {@code .DS_Store} when Finder opens a folder,
 *       {@code ._*}, {@code Thumbs.db}, {@code desktop.ini}) are not part of it: the plug-in deletes
 *       them first ({@link #removeSystemFiles}), and {@link #of(Path)} counts an install that still
 *       has one as no install. Why not simply skip them: a file is a file to whatever loads it by
 *       name, and Neovim would run a {@code share/nvim/runtime/lua/._x.lua} that a
 *       {@code require("._x")} asks for, unchecked. Release archives have none of them, so the
 *       pinned hashes are the same either way.</li>
 *   <li>One line per file, {@code <sha256 in lowercase hex><two spaces><path><newline>}, in the
 *       order of the paths' UTF-8 bytes.</li>
 *   <li>The tree hash is the SHA-256 of those lines, as UTF-8.</li>
 * </ol>
 * The lines are exactly what {@code sha256sum} prints, so the hash can be checked by hand:
 * <pre>
 *   cd ~/.local/share/neovim4eclipse-plugin/neovim/la
 *   find . -type f ! -iname .DS_Store ! -iname Thumbs.db ! -iname desktop.ini ! -name '._*' \
 *       | sed 's|^\./||' | LC_ALL=C sort | tr '\n' '\0' | xargs -0 sha256sum | sha256sum
 * </pre>
 * ({@code shasum -a 256} in place of both {@code sha256sum} on macOS; in Git Bash on Windows,
 * whose {@code sha256sum} writes {@code <hash> *<path>}, add {@code | sed 's/ \*/  /'} before the
 * last {@code sha256sum}, as {@code releng/install-neovim.sh} does). The four {@code !} tests
 * skip the files of point 1 that a file manager may have added since the plug-in last deleted them
 * (Finder writes a {@code .DS_Store} as soon as it shows the folder).
 * {@code releng/install-neovim.sh} checks every CI run's Neovim that way, and {@code releng/UpdateNeovimDownloads.java} computes the
 * same hash from the archive's entries, with the archive's top folder ({@code nvim-linux-arm64/})
 * left out, and pins it as {@code <code>.tree} in {@code neovim-downloads.properties}.
 *
 * <p>A link or any other special file makes the install no install at all ({@link #of} answers
 * empty): the archives have none (their links are refused when unpacking, {@link ArchiveExtractor}),
 * and a link could point the check at one file and Neovim at another.
 *
 * <p>Cost: about 0.2 s for the 2,100 files and 41 MB of Neovim 0.12.6 (Linux, warm cache), so it
 * runs off the UI thread and its answer is kept ({@link PluginNeovim#verified}).
 */
public final class InstallTree {

    private InstallTree() {
    }

    /**
     * Deletes every file below {@code home} named like a file manager's own
     * ({@link PluginNeovim#isSystemFile}), so that {@link #of(Path)} can count the install. Example:
     * {@code la/.DS_Store} and {@code la/share/nvim/runtime/desktop.ini} are deleted, nothing else
     * is touched. Links are not followed (the walk does not enter a linked folder, and {@link #of}
     * refuses the install anyway). A read-only file (Explorer marks {@code desktop.ini} so) loses
     * that mark first, as Windows refuses to delete it otherwise.
     *
     * @return whether none is left: {@code false} if one could not be deleted (held open, or no
     *         permission), and then {@link #of(Path)} fails the check until it is gone
     */
    public static boolean removeSystemFiles(Path home) {
        boolean[] left = { false };
        try {
            Files.walkFileTree(home.toAbsolutePath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && PluginNeovim.isSystemFile(String.valueOf(file.getFileName()))) {
                        left[0] |= !delete(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE; // of() fails on it, if it matters
                }
            });
        } catch (IOException | SecurityException e) {
            return false;
        }
        return !left[0];
    }

    private static boolean delete(Path file) {
        try {
            if (Files.getFileStore(file).supportsFileAttributeView(DosFileAttributeView.class)) {
                Files.setAttribute(file, "dos:readonly", false);
            }
            Files.deleteIfExists(file);
            return true;
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * The tree hash of the install in {@code home}, or empty if it holds a link or another special
     * file, a file named like a file manager's own (call {@link #removeSystemFiles} first), or
     * cannot be read.
     */
    public static Optional<String> of(Path home) {
        Map<String, String> files = new TreeMap<>(InstallTree::compareUtf8);
        boolean[] special = { false };
        try {
            Path root = home.toAbsolutePath();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink() || attrs.isOther()) {
                        special[0] = true; // a Windows junction reads as "other"
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile()) {
                        special[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (PluginNeovim.isSystemFile(String.valueOf(file.getFileName()))) {
                        special[0] = true; // not deleted: never hashed, so never run unchecked
                        return FileVisitResult.TERMINATE;
                    }
                    files.put(relative(root, file), sha256(file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
        return special[0] || files.isEmpty() ? Optional.empty() : Optional.of(of(files));
    }

    /**
     * The tree hash of {@code files}, each relative path ({@code bin/nvim}) to its SHA-256 in
     * lowercase hex; the rule's steps 2 and 3. The map's own order does not matter.
     */
    public static String of(Map<String, String> files) {
        List<String> paths = new ArrayList<>(files.keySet());
        paths.sort(InstallTree::compareUtf8);
        StringBuilder lines = new StringBuilder();
        for (String path : paths) {
            lines.append(files.get(path)).append("  ").append(path).append('\n');
        }
        return HexFormat.of().formatHex(digest().digest(lines.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** Orders paths by their UTF-8 bytes, as {@code LC_ALL=C sort} does. */
    static int compareUtf8(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String relative(Path root, Path file) {
        List<String> names = new ArrayList<>();
        for (Path name : root.relativize(file)) {
            names.add(name.toString());
        }
        return String.join("/", names);
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0;) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java has SHA-256", e);
        }
    }
}
