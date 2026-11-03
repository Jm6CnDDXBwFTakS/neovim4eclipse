// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Says which Neovim a preference value names, for the note under the field on the preference page:
 *
 * <pre>
 *   Found: dedicated Neovim 0.12.6, checked, /home/you/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim
 *   No dedicated Neovim yet. Get Neovim 0.12.6 installs it into /home/you/.local/share/neovim4eclipse-plugin/neovim
 *   The dedicated Neovim's files differ from Neovim 0.12.6's release (an older one, or damaged): Get Neovim replaces it (/home/you/.../neovim/la/bin/nvim)
 *   Found: Neovim 0.13.0, /usr/bin/nvim. Not the dedicated Neovim: not checked by the plug-in, and unsupported. Clear the field to use the dedicated one.
 *   Found: Neovim 0.9.5 (too old: Neovim for Eclipse needs 0.12.6 or newer), /usr/bin/nvim
 *   Found: no Neovim version (it did not answer --version), /usr/local/bin/nvim
 *   /opt/nvim is a folder: enter the Neovim program inside it, such as /opt/nvim/bin/nvim
 *   No Neovim program at /opt/nvim/bin/nvim. Clear the field to use the dedicated Neovim.
 * </pre>
 *
 * <p>"Dedicated" marks the plug-in's own Neovim, the one Get Neovim installs into the plug-in's
 * folder ({@link PluginNeovim}); "checked" says its files matched the pinned release
 * ({@link PluginNeovim#verified}). A program the user names is run as it is: nothing about it is
 * checked, so the note says so.
 *
 * <p>When the note finds the verified dedicated Neovim, the page has nothing to get: it hides its
 * <em>Get Neovim</em> button and offers a small <em>Reinstall...</em> link instead
 * ({@link Note#current}).
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
 * then cannot push it out of view. No full stop follows a path that ends a line, which would read
 * as part of it.
 *
 * <p>{@link #describe} hashes the dedicated Neovim the first time (about 0.2 s) and runs a named
 * program's {@code --version} ({@link InstalledVersions}), which may hang on a broken one, so the
 * caller runs it off the UI thread. {@link #note} only builds the note, and is what the unit tests
 * check.
 */
public final class NvimProbe {

    /** The first of the {@link #unusedLines}. */
    public static final String UNUSED_HEADING = "Not used by the plug-in, you can delete:";

    private NvimProbe() {
    }

    /**
     * What the note says, and whether it reports a problem: Neovim would not start with this value,
     * or would start one too old. The page shows a problem in the theme's error color, with an error
     * mark on the field.
     *
     * @param text    the note's text
     * @param problem whether starting Neovim with this value would fail
     * @param current whether it is the verified dedicated Neovim: then there is nothing to get, and
     *                the page offers only a reinstall
     */
    public record Note(String text, boolean problem, boolean current) {

        /** A note that does not find the verified dedicated Neovim (all but one kind). */
        public Note(String text, boolean problem) {
            this(text, problem, false);
        }
    }

    /**
     * Resolves {@code configured} as starting a session would ({@link NvimExecutable#find}), asks a
     * named program for its version, and describes the result.
     *
     * @param configured the preference value as typed (not yet saved)
     */
    public static Note describe(String configured) {
        NvimExecutable.Resolved resolved = NvimExecutable.find(configured);
        Path folder = NvimExecutable.dedicatedFolder();
        NeovimVersion version = resolved.kind() == NvimExecutable.Kind.PROGRAM
                ? InstalledVersions.of(Path.of(resolved.command())) : null;
        Optional<PluginNeovim.Unverified> unverified = resolved.kind() == NvimExecutable.Kind.NO_DEDICATED
                ? PluginNeovim.unverified(PluginNeovim.searchHomes(), PlatformUtil.isWindows(), NeovimRelease.bundled())
                : Optional.empty();
        Note note = note(resolved, version, unverified, folder, NeovimRelease.bundled().version());
        String unused = unusedLines(PluginNeovim.unusedEntries(folder), folder);
        return unused == null ? note : new Note(note.text() + "\n" + unused, note.problem(), note.current());
    }

    /**
     * The line about a dedicated Neovim that fails the check. Example:
     * {@code The dedicated Neovim's files differ from Neovim 0.12.6's release (an older one, or damaged): Get Neovim replaces it (/home/you/.../neovim/la/bin/nvim)}
     */
    public static String unverifiedLine(PluginNeovim.Unverified install, NeovimVersion offered) {
        return "The dedicated Neovim's files differ from Neovim " + offered + "'s release (an older one, or "
                + "damaged): Get Neovim replaces it (" + install.program() + ")";
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
     * @param resolved   what the value names ({@link NvimExecutable#find})
     * @param version    a named program's version ({@link NvimExecutable.Kind#PROGRAM}), or
     *                   {@code null} if it did not run as Neovim or is not asked
     * @param unverified a dedicated Neovim that fails the check, for a blank value that found none
     * @param dedicated  the plug-in's Neovim folder: where Get Neovim installs
     * @param offered    the version Get Neovim installs ({@link NeovimRelease#version})
     */
    static Note note(NvimExecutable.Resolved resolved, NeovimVersion version,
            Optional<PluginNeovim.Unverified> unverified, Path dedicated, NeovimVersion offered) {
        String program = resolved.command();
        return switch (resolved.kind()) {
            case DEDICATED -> new Note("Found: dedicated Neovim " + offered + ", checked, " + program, false, true);
            case NO_DEDICATED -> problem(unverified.map(u -> unverifiedLine(u, offered))
                    .orElse("No dedicated Neovim yet. Get Neovim " + offered + " installs it into " + dedicated));
            case PROGRAM -> {
                if (version == null) {
                    yield problem("Found: no Neovim version (it did not answer --version), " + program);
                }
                if (!version.isSupported()) {
                    yield problem("Found: Neovim " + version + " (too old: Neovim for Eclipse needs "
                            + NeovimVersion.MINIMUM + " or newer), " + program);
                }
                yield new Note("Found: Neovim " + version + ", " + program + ". Not the dedicated Neovim: not "
                        + "checked by the plug-in, and unsupported. Clear the field to use the dedicated one.", false);
            }
            case FOLDER -> problem(program + " is a folder: enter the Neovim program inside it, such as "
                    + Path.of(program).resolve("bin").resolve(NvimExecutable.defaultCommandName()));
            case MISSING -> problem("No Neovim program at " + program + ". Clear the field to use the dedicated Neovim.");
            case NOT_ABSOLUTE -> problem("Enter the absolute path of a Neovim program (a leading ~ is fine), or clear "
                    + "the field to use the dedicated Neovim.");
        };
    }

    private static Note problem(String text) {
        return new Note(text, true);
    }
}
