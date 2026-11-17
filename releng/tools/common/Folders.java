// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Folder chores the tools share: unpacking a downloaded ZIP safely, deleting a work folder. */
public final class Folders {

    private Folders() {
    }

    /**
     * Unpacks {@code zip} into {@code folder}, refusing an entry whose name would land outside it
     * ({@code ../x}, an absolute path; "zip slip"): a downloaded file is untrusted until checked,
     * and unpacking it is how it gets checked.
     */
    public static void unzip(Path zip, Path folder) {
        try (InputStream in = Files.newInputStream(zip)) {
            unzip(zip.getFileName().toString(), in, folder, Long.MAX_VALUE);
        } catch (IOException e) {
            throw new ToolFailure("cannot unpack " + zip + ": " + e.getMessage());
        }
    }

    /**
     * The same for a ZIP held in memory, with a limit. For a caller that has checked the hash of
     * exactly these bytes: unpacking them, not the file again, leaves no moment in which the file
     * could be replaced between the check and the unpacking. {@code mostBytes} bounds what is
     * written: a small archive can be made to unpack into gigabytes (a "zip bomb"), and no
     * repository's archive needs that.
     *
     * @param name what to call the archive in a message
     */
    public static void unzip(String name, byte[] zip, Path folder, long mostBytes) {
        try {
            unzip(name, new ByteArrayInputStream(zip), folder, mostBytes);
        } catch (IOException e) {
            throw new ToolFailure("cannot unpack " + name + ": " + e.getMessage());
        }
    }

    private static void unzip(String name, InputStream zip, Path folder, long mostBytes) throws IOException {
        Path base = folder.toAbsolutePath().normalize();
        long written = 0;
        byte[] buffer = new byte[1 << 16];
        try (ZipInputStream in = new ZipInputStream(zip)) {
            for (ZipEntry e = next(name, in); e != null; e = next(name, in)) {
                Path target;
                try {
                    target = base.resolve(e.getName()).normalize();
                } catch (InvalidPathException bad) { // a name this system cannot have as a file's (a NUL; on Windows a colon)
                    throw new ToolFailure(name + " has an entry this system cannot store: " + bad.getReason());
                }
                if (!target.startsWith(base) || target.equals(base)) {
                    throw new ToolFailure(name + " has an entry outside its folder: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                // Whatever has the name already is replaced itself, not written into: for a link
                // left there, that is the link, not the file it points at.
                Files.deleteIfExists(target);
                try (OutputStream out = Files.newOutputStream(target)) {
                    for (int n; (n = in.read(buffer)) > 0;) {
                        written += n;
                        if (written > mostBytes) {
                            throw new ToolFailure(name + " unpacks to more than " + mostBytes + " bytes; it was not unpacked further");
                        }
                        out.write(buffer, 0, n);
                    }
                }
            }
        }
    }

    /** The ZIP's next entry; an entry whose name is no text (not valid UTF-8) stops the tool like any other bad archive. */
    private static ZipEntry next(String name, ZipInputStream in) throws IOException {
        try {
            return in.getNextEntry();
        } catch (IllegalArgumentException bad) {
            throw new ToolFailure(name + " has an entry with a malformed name: " + bad.getMessage());
        }
    }

    /** Deletes {@code folder} and everything in it; nothing happens if it does not exist. */
    public static void deleteTree(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new ToolFailure("cannot delete " + folder + ": " + e.getMessage());
        }
    }
}
