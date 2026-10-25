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
 *   No Neovim found in the default folders. Extract a Neovim release archive into /home/you/.local/share/neovim4eclipse-plugin/neovim
 *   No Neovim found at /opt/nvim. Clear the field to search the default folders, or enter a value as described.
 * </pre>
 *
 * <p>"As described" points at the page's description, which says what the field takes.
 *
 * <p>The version comes first and the path last: the version is what you look for, and a long path
 * then cannot push it out of view. No full stop follows the path, which would read as part of it.
 *
 * <p>{@link #describe} runs {@code nvim --version} ({@link InstalledVersions}), which takes some
 * milliseconds and may hang on a broken install, so the caller runs it off the UI thread.
 * {@link #note} only builds the note, and is what the unit tests check.
 */
public final class NvimProbe {

    private NvimProbe() {
    }

    /**
     * What the note says, and whether it reports a problem: Neovim would not start with this value
     * (nothing found, a program that is not Neovim, or one too old). The page shows a problem in the
     * theme's error color, with an error mark on the field.
     *
     * @param text    the note's text
     * @param problem whether starting Neovim with this value would fail
     */
    public record Note(String text, boolean problem) {
    }

    /**
     * Resolves {@code configured} as starting a session would ({@link NvimExecutable#resolve}), asks
     * the program found for its version, and describes the result.
     *
     * @param configured the preference value as typed (not yet saved)
     */
    public static Note describe(String configured) {
        NvimExecutable.Resolved resolved = NvimExecutable.find(configured);
        Path program = NvimExecutable.locate(resolved.command());
        if (program == null) {
            return note(configured, null, null, 0, NvimExecutable.dedicatedFolder());
        }
        return note(configured, program, InstalledVersions.of(program), resolved.among(),
                NvimExecutable.dedicatedFolder());
    }

    /**
     * The note.
     *
     * @param configured the field's text; blank means the default search
     * @param program    the program found, or {@code null}
     * @param version    its version, or {@code null} if it did not run as Neovim
     * @param among      how many installs in the same folder it was chosen from (1: no choice)
     * @param dedicated  the default Neovim folder, suggested when the default search finds nothing
     */
    static Note note(String configured, Path program, NeovimVersion version, int among, Path dedicated) {
        if (program == null) {
            if (configured == null || configured.isBlank()) {
                return problem("No Neovim found in the default folders. Extract a Neovim release archive into "
                        + dedicated);
            }
            return problem("No Neovim found at " + configured.strip()
                    + ". Clear the field to search the default folders, or enter a value as described.");
        }
        if (version == null) {
            return problem("Found: no Neovim version (it did not answer --version), " + program);
        }
        List<String> remarks = new ArrayList<>();
        if (among > 1) {
            remarks.add("newest of " + among + " in this folder");
        }
        if (!version.isSupported()) {
            remarks.add("too old: Neovim for Eclipse needs " + NeovimVersion.MINIMUM + " or newer");
        }
        String remark = remarks.isEmpty() ? "" : " (" + String.join("; ", remarks) + ")";
        return new Note("Found: Neovim " + version + remark + ", " + program, !version.isSupported());
    }

    private static Note problem(String text) {
        return new Note(text, true);
    }
}
