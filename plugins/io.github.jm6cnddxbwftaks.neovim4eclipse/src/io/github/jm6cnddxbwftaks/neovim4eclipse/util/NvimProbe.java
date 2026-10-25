// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Says which Neovim a preference value finds, for the note under the field on the preference page:
 *
 * <pre>
 *   Found: Neovim 0.12.6, /home/you/.local/share/neovim4eclipse-plugin/neovim/bin/nvim
 *   Found: Neovim 0.13.0 (newest of 2 in this folder), /home/you/.local/share/neovim4eclipse-plugin/neovim/nvim-0.13.0/bin/nvim
 *   Found: Neovim 0.9.5 (too old: Neovim for Eclipse needs 0.12.6 or newer), /usr/bin/nvim
 *   Found: no Neovim version (it did not answer --version), /usr/local/bin/nvim
 *   No Neovim found. Extract a release archive into /home/you/.local/share/neovim4eclipse-plugin/neovim, or choose ...
 * </pre>
 *
 * <p>The version comes first and the path last: the version is what you look for, and a long path
 * then cannot push it out of view. No full stop follows the path, which would read as part of it.
 *
 * <p>{@link #describe} runs {@code nvim --version} ({@link InstalledVersions}), which takes some
 * milliseconds and may hang on a broken install, so the caller runs it off the UI thread.
 * {@link #note} only builds the text, and is what the unit tests check.
 */
public final class NvimProbe {

    private NvimProbe() {
    }

    /**
     * Resolves {@code configured} as starting a session would ({@link NvimExecutable#resolve}), asks
     * the program found for its version, and describes the result.
     *
     * @param configured the preference value as typed (not yet saved)
     */
    public static String describe(String configured) {
        NvimExecutable.Resolved resolved = NvimExecutable.find(configured);
        Path program = NvimExecutable.locate(resolved.command());
        if (program == null) {
            return note(null, null, 0, NvimExecutable.dedicatedFolder());
        }
        return note(program, InstalledVersions.of(program), resolved.among(), NvimExecutable.dedicatedFolder());
    }

    /**
     * The note's text.
     *
     * @param program   the program found, or {@code null}
     * @param version   its version, or {@code null} if it did not run as Neovim
     * @param among     how many installs in the same folder it was chosen from (1: no choice)
     * @param dedicated the plug-in's own Neovim folder, suggested when nothing is found
     */
    static String note(Path program, NeovimVersion version, int among, Path dedicated) {
        if (program == null) {
            return "No Neovim found. Extract a release archive into " + dedicated
                    + ", or choose the folder of another Neovim " + NeovimVersion.MINIMUM + " or newer.";
        }
        if (version == null) {
            return "Found: no Neovim version (it did not answer --version), " + program;
        }
        List<String> remarks = new ArrayList<>();
        if (among > 1) {
            remarks.add("newest of " + among + " in this folder");
        }
        if (!version.isSupported()) {
            remarks.add("too old: Neovim for Eclipse needs " + NeovimVersion.MINIMUM + " or newer");
        }
        String remark = remarks.isEmpty() ? "" : " (" + String.join("; ", remarks) + ")";
        return "Found: Neovim " + version + remark + ", " + program;
    }
}
