// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        Path base = folder.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                Path target = base.resolve(e.getName()).normalize();
                if (!target.startsWith(base) || target.equals(base)) {
                    throw new ToolFailure(zip.getFileName() + " has an entry outside its folder: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new ToolFailure("cannot unpack " + zip + ": " + e.getMessage());
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
