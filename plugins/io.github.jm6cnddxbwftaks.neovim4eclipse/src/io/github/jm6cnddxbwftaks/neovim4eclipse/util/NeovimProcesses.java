// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Whether some process on this machine runs a program from a folder: the question Get Neovim asks
 * before it moves the dedicated Neovim's folder ({@link NeovimInstaller}).
 *
 * <p>Why the whole machine, not just this Eclipse: the dedicated Neovim's folder is per user
 * ({@link PluginFolders}), so every Eclipse the user runs shares it. On Linux and macOS a folder
 * can be moved and deleted under a running program; that Neovim keeps running, but the runtime
 * files it loads later ({@code ftplugin/}, {@code syntax/}, {@code :help}, Lua modules) are gone.
 * Example: Eclipse A and Eclipse B both use {@code neovim/la}; Get Neovim in A must not replace
 * {@code la} while B's {@code nvim} runs from it. (On Windows the move itself fails.)
 *
 * <p>The answer comes from {@link ProcessHandle#allProcesses()}: each process's program path, as
 * the OS reports it (Linux: {@code /proc/<pid>/exe}, always the real path). The user's own processes
 * are all visible; another user's may not be, which is fine, since the folder is the user's own.
 * Paths are compared under {@link FilePaths}' rule, which resolves links, so {@code ~/.local/share}
 * being a link does not hide a match. A process starting just after the answer is not seen: the window is the
 * few milliseconds of the two folder moves.
 */
public final class NeovimProcesses {

    private NeovimProcesses() {
    }

    /** Whether a running process's program is inside {@code folder}. {@code false} if the OS does not say. */
    public static boolean runFrom(Path folder) {
        try (Stream<ProcessHandle> processes = ProcessHandle.allProcesses()) {
            return anyInside(processes.map(p -> p.info().command()).flatMap(Optional::stream), folder,
                    FilePaths.Platform.current());
        } catch (SecurityException | UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * Whether one of {@code programs} (full paths) is inside {@code folder}, under {@link FilePaths}'
     * rule (links resolved, case as the system has it). Example:
     * {@code /home/u/.local/share/x/la/bin/nvim} is inside {@code /home/u/.local/share/x/la}.
     */
    static boolean anyInside(Stream<String> programs, Path folder, FilePaths.Platform platform) {
        String f = folder.toAbsolutePath().toString();
        return programs.anyMatch(program -> FilePaths.inside(program, f, platform));
    }
}
