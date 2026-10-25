// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The version of a Neovim program on disk, found by running {@code nvim --version}.
 *
 * <p>Used where a folder holds several Neovims and the newest must be chosen
 * ({@link NvimExecutable#findInFolder}), and for the note on the preference page
 * ({@link NvimProbe}). Running a program takes some milliseconds, so each answer is remembered
 * per path, together with the file's modification time: replacing the program (extracting a newer
 * release over it) asks again.
 *
 * <p>Example: {@code InstalledVersions.of(Path.of("/opt/nvim-linux-x86_64/bin/nvim"))} gives
 * {@code 0.12.6}; a program that is not Neovim, cannot run on this machine (an archive for another
 * processor), or takes longer than {@link #TIMEOUT_MS} gives {@code null}.
 */
public final class InstalledVersions {

    /** How long {@code nvim --version} may take before the program counts as not working. */
    static final long TIMEOUT_MS = 3000;

    private record Known(long modified, NeovimVersion version) {
    }

    private static final Map<Path, Known> CACHE = new ConcurrentHashMap<>();

    private InstalledVersions() {
    }

    /**
     * The version of {@code program}, or {@code null} if it does not run as Neovim. Blocks while the
     * program runs (the first time for each file): call it off the SWT UI thread.
     */
    public static NeovimVersion of(Path program) {
        long modified;
        try {
            modified = Files.getLastModifiedTime(program).toMillis();
        } catch (IOException | SecurityException e) {
            return null;
        }
        Known known = CACHE.get(program);
        if (known != null && known.modified() == modified) {
            return known.version();
        }
        NeovimVersion version = NeovimVersion.fromVersionOutput(runVersion(program));
        CACHE.put(program, new Known(modified, version));
        return version;
    }

    /** Runs {@code program --version}; its output, or {@code null} if it failed or took too long. */
    private static String runVersion(Path program) {
        Process process = null;
        try {
            process = new ProcessBuilder(List.of(program.toString(), "--version")).redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return null;
            }
            try (InputStream in = process.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null; // not executable here, e.g. an archive for another processor
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
