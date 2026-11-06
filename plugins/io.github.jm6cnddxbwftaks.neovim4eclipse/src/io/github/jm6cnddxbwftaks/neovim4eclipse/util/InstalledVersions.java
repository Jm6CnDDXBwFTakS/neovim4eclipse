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
 * <p>Used for the note on the preference page about a program the user named ({@link NvimProbe}),
 * after the dedicated Neovim's check ({@link NvimExecutable}: one for another processor, which
 * cannot run here, gives no version), and by the installer. Running a program takes some
 * milliseconds, so each answer is remembered
 * per path, together with the file's modification time: replacing the program (extracting a newer
 * release over it) asks again.
 *
 * <p>Example: {@code InstalledVersions.of(Path.of("/opt/nvim-linux-x86_64/bin/nvim"))} gives
 * {@code 0.12.6}; a program that is not Neovim, cannot run on this machine (an archive for another
 * processor), or does not answer in time gives {@code null}.
 *
 * <h2>Slow first runs</h2>
 * A program's very first run can be much slower than later ones: Windows Defender scans a freshly
 * unpacked {@code nvim.exe} before it lets it start, and a loaded machine (or one core) adds to
 * that. So a run that takes longer than {@link #TIMEOUT_MS} is tried once more with
 * {@link #SLOW_TIMEOUT_MS}, and a program that times out both times gives {@code null} without
 * being remembered: the next question runs it again. Only a clear answer is kept (a version, or
 * output that is not Neovim's, or a program that cannot be started). Without this, one slow first
 * run would mark a good Neovim as not working for the rest of the session, and the installer would
 * refuse a fresh download.
 */
public final class InstalledVersions {

    /** How long {@code nvim --version} may take before it is tried again, with {@link #SLOW_TIMEOUT_MS}. */
    static final long TIMEOUT_MS = 3000;

    /** The limit of the second try, after a first run that timed out. */
    static final long SLOW_TIMEOUT_MS = 15000;

    private record Known(long modified, NeovimVersion version) {
    }

    private static final Map<Path, Known> CACHE = new ConcurrentHashMap<>();

    private InstalledVersions() {
    }

    /**
     * The version of {@code program}, or {@code null} if it does not run as Neovim. Blocks while the
     * program runs (the first time for each file; up to 18 s for one that hangs): call it off the
     * SWT UI thread.
     */
    public static NeovimVersion of(Path program) {
        return of(program, TIMEOUT_MS, SLOW_TIMEOUT_MS);
    }

    /** {@link #of(Path)} with the two time limits given (tests). */
    static NeovimVersion of(Path program, long timeoutMs, long slowTimeoutMs) {
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
        Run run = runVersion(program, timeoutMs);
        if (run.timedOut()) {
            run = runVersion(program, slowTimeoutMs);
        }
        if (run.timedOut()) {
            return null; // not remembered: a later question tries again
        }
        NeovimVersion version = NeovimVersion.fromVersionOutput(run.output());
        CACHE.put(program, new Known(modified, version));
        return version;
    }

    /**
     * What a run of {@code --version} gave.
     *
     * @param output   what it printed; {@code null} if it could not be started
     * @param timedOut whether it gave no answer in time (then {@code output} is {@code null} too)
     */
    private record Run(String output, boolean timedOut) {
        static final Run TIMED_OUT = new Run(null, true);
        static final Run FAILED = new Run(null, false);
    }

    /** Runs {@code program --version} for at most {@code timeoutMs}. */
    private static Run runVersion(Path program, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder(List.of(program.toString(), "--version")).redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                return Run.TIMED_OUT;
            }
            try (InputStream in = process.getInputStream()) {
                return new Run(new String(in.readAllBytes(), StandardCharsets.UTF_8), false);
            }
        } catch (IOException e) {
            return Run.FAILED; // not executable here, e.g. an archive for another processor
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Run.TIMED_OUT; // no answer: not remembered
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
