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
 *   Found: dedicated Neovim 0.12.6, /home/you/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim
 *   Found: Neovim 0.13.0 (newest of 2 in this folder), /home/you/opt/nvim-0.13.0/bin/nvim
 *   Found: Neovim 0.9.5 (too old: Neovim for Eclipse needs 0.12.6 or newer), /usr/bin/nvim
 *   Found: no Neovim version (it did not answer --version), /usr/local/bin/nvim
 *   No Neovim found in the default folders. Get Neovim 0.12.6 installs it into /home/you/.local/share/neovim4eclipse-plugin/neovim
 *   No Neovim found at /opt/nvim. Clear the field to search the default folders, or enter a value as described.
 * </pre>
 *
 * <p>"As described" points at the page's description, which says what the field takes. "Dedicated"
 * marks the plug-in's own Neovim, the one Get Neovim installs into the plug-in's folder
 * ({@link PluginNeovim}), so it is not taken for a Neovim the user installed themselves.
 *
 * <p>When the note finds the dedicated Neovim at the release Get Neovim installs, or newer, the
 * page has nothing to get: it hides its <em>Get Neovim</em> button and offers a small
 * <em>Reinstall...</em> link on the note instead ({@link Note#current}), for the rare copy that
 * answers {@code --version} but is damaged otherwise (a damaged copy that does not answer gets the
 * button back, as its version is then unknown).
 *
 * <p>With a blank preference, a plug-in Neovim older than the minimum (left from before an update of
 * the plug-in raised it) gets a line of its own ({@link #outdatedLine}):
 *
 * <pre>
 *   The plug-in's Neovim 0.12.6 is too old: Get Neovim 0.13.0 replaces it (/home/you/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim)
 * </pre>
 *
 * <p>If the plug-in's folder holds anything it no longer uses ({@link PluginNeovim#unusedEntries}),
 * such as an archive extracted there by hand before 0.2.1, the lines after say so, one full path a
 * line, so each stands apart and can be copied whole ({@link #unusedLines}):
 *
 * <pre>
 *   Not used by the plug-in, you can delete:
 *     /home/you/.local/share/neovim4eclipse-plugin/neovim/current
 *     /home/you/.local/share/neovim4eclipse-plugin/neovim/nvim-linux-arm64_0.12.6
 * </pre>
 *
 * <p>The version comes first and the path last: the version is what you look for, and a long path
 * then cannot push it out of view. No full stop follows the path, which would read as part of it.
 *
 * <p>{@link #describe} runs {@code nvim --version} ({@link InstalledVersions}), which takes some
 * milliseconds and may hang on a broken install, so the caller runs it off the UI thread.
 * {@link #note} only builds the note, and is what the unit tests check.
 */
public final class NvimProbe {

    /** The first of the {@link #unusedLines}. */
    public static final String UNUSED_HEADING = "Not used by the plug-in, you can delete:";

    private NvimProbe() {
    }

    /**
     * What the note says, and whether it reports a problem: Neovim would not start with this value
     * (nothing found, a program that is not Neovim, or one too old). The page shows a problem in the
     * theme's error color, with an error mark on the field.
     *
     * @param text    the note's text
     * @param problem whether starting Neovim with this value would fail
     * @param current whether it finds the dedicated Neovim at the release Get Neovim installs, or newer:
     *                then there is nothing to get, and the page offers only a reinstall
     */
    public record Note(String text, boolean problem, boolean current) {

        /** A note that does not find a current dedicated Neovim (all but one kind). */
        public Note(String text, boolean problem) {
            this(text, problem, false);
        }
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
        Path folder = NvimExecutable.dedicatedFolder();
        NeovimVersion offered = NeovimRelease.bundled().version();
        Note note = program == null ? note(configured, null, null, 0, folder, offered)
                : note(configured, program, InstalledVersions.of(program), resolved.among(), folder, offered);
        StringBuilder text = new StringBuilder(note.text());
        if (configured == null || configured.isBlank()) {
            PluginNeovim.outdated(PluginNeovim.searchHomes(), PlatformUtil.isWindows(), InstalledVersions::of)
                    .ifPresent(old -> text.append('\n').append(outdatedLine(old)));
        }
        String unused = unusedLines(PluginNeovim.unusedEntries(folder), folder);
        if (unused != null) {
            text.append('\n').append(unused);
        }
        return new Note(text.toString(), note.problem(), note.current());
    }

    /**
     * The line about the plug-in's own Neovim when it is older than the minimum. Example:
     * {@code The plug-in's Neovim 0.12.6 is too old: Get Neovim 0.13.0 replaces it (/home/you/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim)}
     */
    public static String outdatedLine(PluginNeovim.Outdated old) {
        return "The plug-in's Neovim " + old.version() + " is too old: Get Neovim " + NeovimVersion.MINIMUM
                + " replaces it (" + old.program() + ")";
    }

    /**
     * The lines about entries of the plug-in's Neovim folder that it does not use, or {@code null} if
     * there are none: a heading line, then each entry's full path on a line of its own, indented.
     * Example: {@code "Not used by the plug-in, you can delete:\n  /home/you/.../neovim/current"}.
     *
     * @param unused the entries' names ({@link PluginNeovim#unusedEntries})
     * @param folder the plug-in's Neovim folder
     */
    public static String unusedLines(List<String> unused, Path folder) {
        if (unused.isEmpty()) {
            return null;
        }
        StringBuilder lines = new StringBuilder(UNUSED_HEADING);
        for (String name : unused) {
            lines.append("\n  ").append(folder.resolve(name));
        }
        return lines.toString();
    }


    /**
     * The note.
     *
     * @param configured the field's text; blank means the default search
     * @param program    the program found, or {@code null}
     * @param version    its version, or {@code null} if it did not run as Neovim
     * @param among      how many installs in the same folder it was chosen from (1: no choice)
     * @param dedicated  the plug-in's Neovim folder: suggested when the default search finds nothing,
     *                   and a program inside it is the dedicated Neovim
     * @param offered    the version Get Neovim installs ({@link NeovimRelease#version})
     */
    static Note note(String configured, Path program, NeovimVersion version, int among, Path dedicated,
            NeovimVersion offered) {
        if (program == null) {
            if (configured == null || configured.isBlank()) {
                return problem("No Neovim found in the default folders. Get Neovim " + NeovimVersion.MINIMUM
                        + " installs it into " + dedicated);
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
        boolean isDedicated = FilePaths.inside(program.toString(), dedicated.toString(), FilePaths.Platform.current());
        return new Note("Found: " + (isDedicated ? "dedicated " : "") + "Neovim " + version + remark + ", " + program,
                !version.isSupported(), isDedicated && version.compareTo(offered) >= 0);
    }

    private static Note problem(String text) {
        return new Note(text, true);
    }
}
