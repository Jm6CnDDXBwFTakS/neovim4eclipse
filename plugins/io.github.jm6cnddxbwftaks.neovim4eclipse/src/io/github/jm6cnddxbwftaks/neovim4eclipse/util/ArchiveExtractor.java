// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks a Neovim release archive: a {@code .tar.gz} (Linux, macOS) with a small tar reader of our
 * own, or a {@code .zip} (Windows) with {@code java.util.zip}. No library is needed for either.
 *
 * <h2>The top folder is dropped</h2>
 * Every release archive holds one top folder named after itself ({@code nvim-linux-arm64/bin/nvim}).
 * The plug-in installs into a folder named by platform code instead ({@code neovim/la/bin/nvim},
 * {@link PluginNeovim}), so each entry loses its first path element. An entry outside a top folder,
 * or a second top folder, means the archive is not a Neovim release, and unpacking stops.
 *
 * <h2>Safety: "zip slip"</h2>
 * An archive names its files itself, and a crafted one could name {@code ../../.bashrc} or an
 * absolute path to write outside the target. Every name is resolved inside the target and refused if
 * it leaves it (the "zip slip" attack, https://security.snyk.io/research/zip-slip-vulnerability).
 * Links (symbolic and hard) and device files are skipped: Neovim's archives have none, and a link
 * could point outside the target too. The archive was already checked against the pinned SHA-256
 * before unpacking, so this is a second line of defense.
 *
 * <h2>The tar format, briefly</h2>
 * A tar file is a list of 512-byte headers, each followed by the file's bytes padded to a multiple
 * of 512, and ends with two empty blocks. A header holds the name (100 bytes, with a 155-byte
 * {@code prefix} in the ustar format), the mode in octal (so the execute bits), the size in octal
 * and a type flag ({@code 0} file, {@code 5} folder, {@code 1}/{@code 2} links). A name longer than
 * that comes in an extra header before the entry: a PAX header ({@code x}) with a {@code path=}
 * record, or GNU's {@code L} entry. Example: Neovim 0.12.6's {@code nvim-linux-arm64.tar.gz} has
 * 2127 entries, no links, and its longest path is 84 characters.
 *
 * <h2>Execute bits</h2>
 * On Linux and macOS a file whose tar mode has an execute bit becomes executable ({@code bin/nvim}
 * and the tree-sitter parsers' helpers); Windows needs none. Files written by Java carry no
 * quarantine flag (macOS) and no Mark of the Web (Windows), so the unpacked Neovim runs at once.
 */
public final class ArchiveExtractor {

    private static final int BLOCK = 512;

    private ArchiveExtractor() {
    }

    /**
     * Unpacks {@code archive} into {@code target} (created if missing), dropping the top folder.
     *
     * @param kind     the archive's format
     * @param progress told about each entry; may cancel
     * @throws IOException             if reading or writing fails
     * @throws NeovimInstallException  if the archive is not laid out as a Neovim release (an entry
     *                                 outside the top folder, two top folders, a name leaving the
     *                                 target), or the user cancelled
     */
    public static void extract(Path archive, NeovimRelease.Kind kind, Path target, InstallProgress progress)
            throws IOException {
        Files.createDirectories(target);
        Path root = target.toAbsolutePath().normalize();
        Unpacker unpacker = new Unpacker(root, progress);
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive))) {
            if (kind == NeovimRelease.Kind.ZIP) {
                unzip(raw, unpacker);
            } else {
                untar(new BufferedInputStream(new GZIPInputStream(raw, 64 * 1024)), unpacker);
            }
        }
    }

    private static void unzip(InputStream in, Unpacker unpacker) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.isDirectory()) {
                    unpacker.folder(entry.getName());
                } else {
                    unpacker.file(entry.getName(), zip, false);
                }
            }
        }
    }

    private static void untar(InputStream in, Unpacker unpacker) throws IOException {
        byte[] header = new byte[BLOCK];
        String longName = null; // from a PAX "path" record or a GNU "L" entry, for the next entry
        while (true) {
            if (!readBlock(in, header)) {
                return; // end of stream without the two empty blocks: tolerated, as GNU tar does
            }
            if (isZero(header)) {
                return; // end of archive
            }
            String name = string(header, 0, 100);
            String prefix = string(header, 345, 155);
            if (!prefix.isEmpty() && "ustar".equals(string(header, 257, 5))) {
                name = prefix + "/" + name;
            }
            long size = octal(header, 124, 12);
            int mode = (int) octal(header, 100, 8);
            char type = (char) header[156];
            if (longName != null) {
                name = longName;
                longName = null;
            }
            switch (type) {
                case 'x' -> longName = paxPath(readData(in, size));
                case 'L' -> longName = string(readData(in, size), 0, (int) size);
                case 'g' -> skip(in, size); // global PAX header: nothing the extractor needs
                case '5' -> {
                    unpacker.folder(name);
                    skip(in, size);
                }
                case '0', '\0', '7' -> {
                    unpacker.file(name, new Limited(in, size), (mode & 0111) != 0);
                    skip(in, padding(size));
                }
                default -> skip(in, size); // links, devices, FIFOs: skipped (see the class comment)
            }
        }
    }

    /** The {@code path} record of a PAX header, whose records read {@code "<length> path=<name>\n"}. */
    static String paxPath(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        String path = null;
        int at = 0;
        while (at < text.length()) {
            int space = text.indexOf(' ', at);
            if (space < 0) {
                break;
            }
            int length;
            try {
                length = Integer.parseInt(text.substring(at, space));
            } catch (NumberFormatException e) {
                break;
            }
            if (length <= 0 || at + length > text.length()) {
                break;
            }
            String record = text.substring(space + 1, at + length - 1); // without the newline
            if (record.startsWith("path=")) {
                path = record.substring(5);
            }
            at += length;
        }
        return path;
    }

    /** Writes entries below the target, each without its top folder; enforces one top folder. */
    private static final class Unpacker {
        private final Path root;
        private final InstallProgress progress;
        private final boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        private String top;

        Unpacker(Path root, InstallProgress progress) {
            this.root = root;
            this.progress = progress;
        }

        void folder(String name) throws IOException {
            Path path = resolve(name);
            if (path != null) {
                Files.createDirectories(path);
            }
        }

        void file(String name, InputStream data, boolean executable) throws IOException {
            progress.checkCancelled();
            Path path = resolve(name);
            if (path == null) {
                throw notARelease("a file at the top of the archive: " + name);
            }
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                data.transferTo(out);
            }
            if (executable && posix) {
                Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(path));
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(path, perms);
            }
            progress.worked(1);
        }

        /**
         * Where {@code name} goes: below the target, without the top folder. {@code null} for the top
         * folder itself.
         */
        private Path resolve(String name) {
            String clean = name.replace('\\', '/');
            while (clean.startsWith("./")) {
                clean = clean.substring(2);
            }
            if (clean.startsWith("/") || clean.matches("^[A-Za-z]:.*")) {
                throw notARelease("an absolute path: " + name);
            }
            int slash = clean.indexOf('/');
            String first = slash < 0 ? clean : clean.substring(0, slash);
            String rest = slash < 0 ? "" : clean.substring(slash + 1);
            if (first.isEmpty() || first.equals("..")) {
                throw notARelease("a name outside the archive's folder: " + name);
            }
            if (top == null) {
                top = first;
            } else if (!top.equals(first)) {
                throw notARelease("two top folders, " + top + " and " + first);
            }
            if (rest.isEmpty() || rest.equals("/")) {
                return null;
            }
            Path path = root.resolve(rest).normalize();
            if (!path.startsWith(root) || path.equals(root)) {
                throw notARelease("a name leaving the target folder: " + name);
            }
            return path;
        }
    }

    private static NeovimInstallException notARelease(String detail) {
        return new NeovimInstallException(NeovimInstallException.Reason.WRONG_ARCHIVE,
                "The archive is not laid out as a Neovim release (" + detail + ").");
    }

    /** At most {@code size} bytes of {@code in}; closing it does not close {@code in}. */
    private static final class Limited extends InputStream {
        private final InputStream in;
        private long left;

        Limited(InputStream in, long size) {
            this.in = in;
            this.left = size;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) {
                return -1;
            }
            int b = in.read();
            if (b < 0) {
                throw new EOFException("The archive ends inside a file.");
            }
            left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) {
                throw new EOFException("The archive ends inside a file.");
            }
            left -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.skipNBytes(left); // the caller stopped early: keep the tar stream aligned
            left = 0;
        }
    }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int n = in.readNBytes(block, 0, BLOCK);
        if (n == 0) {
            return false;
        }
        if (n < BLOCK) {
            throw new EOFException("The archive ends inside a header.");
        }
        return true;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > 1 << 20) {
            throw notARelease("an extended header of " + size + " bytes");
        }
        byte[] data = in.readNBytes((int) size);
        if (data.length < size) {
            throw new EOFException("The archive ends inside a header.");
        }
        skip(in, padding(size));
        return data;
    }

    private static void skip(InputStream in, long bytes) throws IOException {
        in.skipNBytes(bytes);
    }

    /** The bytes after {@code size} up to the next 512-byte boundary. */
    private static long padding(long size) {
        long rest = size % BLOCK;
        return rest == 0 ? 0 : BLOCK - rest;
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /** A NUL-terminated (or full-length) text field. */
    private static String string(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && end < b.length && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    /** An octal number field (spaces and NULs around it ignored). */
    private static long octal(byte[] b, int off, int len) {
        long value = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c >= '0' && c <= '7') {
                value = value * 8 + (c - '0');
            } else if (c != ' ' && c != 0) {
                throw notARelease("a header field that is not a number");
            }
        }
        return value;
    }
}
