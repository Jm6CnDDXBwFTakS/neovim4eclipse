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
 *       Folders count only through their files. Files the operating system adds on its own are left
 *       out ({@link PluginNeovim#isSystemFile}: {@code .DS_Store} when Finder opens a folder,
 *       {@code Thumbs.db}, {@code desktop.ini}, {@code ._*}); they hold no code Neovim runs.</li>
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
 * ({@code shasum -a 256} in place of both {@code sha256sum} on macOS). The four {@code !} tests are
 * the left-out files of point 1; leave one out and a {@code Thumbs.db} added by hand (or by Explorer)
 * makes the hash differ from the pinned one although the plug-in accepts the install.
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
     * The tree hash of the install in {@code home}, or empty if it holds a link or another special
     * file, or cannot be read.
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
                    if (!PluginNeovim.isSystemFile(String.valueOf(file.getFileName()))) {
                        files.put(relative(root, file), sha256(file));
                    }
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
