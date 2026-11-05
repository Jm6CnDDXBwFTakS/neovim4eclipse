// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Says which Neovim this Eclipse runs, for the note in the Neovim group of the preference page: the
 * dedicated Neovim, or the program named by the startup option {@code -Dneovim4eclipse.nvim}
 * ({@link NeovimOption}):
 *
 * <pre>
 *   Found: dedicated Neovim 0.12.6, checked, /home/you/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim
 *   No dedicated Neovim yet. "Get Neovim 0.12.6" installs it into /home/you/.local/share/neovim4eclipse-plugin/neovim
 *   The dedicated Neovim's files differ from Neovim 0.12.6's release (an older one, or damaged): "Get Neovim" replaces it (/home/you/.../neovim/la/bin/nvim)
 *   Found: Neovim 0.13.0, /usr/bin/nvim, from -Dneovim4eclipse.nvim. Not the dedicated Neovim: not checked by the plug-in, and unsupported. Remove the option to use the dedicated one.
 *   Found: Neovim 0.9.5 (too old: Neovim for Eclipse needs 0.12.6 or newer), /usr/bin/nvim, from -Dneovim4eclipse.nvim
 *   Not a working Neovim (no answer to --version): /usr/local/bin/nvim, from -Dneovim4eclipse.nvim
 *   /opt/nvim (from -Dneovim4eclipse.nvim) is a folder: name the Neovim program inside it, such as /opt/nvim/bin/nvim
 *   No Neovim program at /opt/nvim/bin/nvim (from -Dneovim4eclipse.nvim). Remove the option to use the dedicated Neovim.
 * </pre>
 *
 * <p>Every note about a named program says where the name came from ("from -Dneovim4eclipse.nvim"):
 * the page has no field for it any more, so the note is the one place that shows it, and someone
 * who forgot the option in {@code eclipse.ini} sees why the dedicated Neovim is not used.
 * "Get Neovim" is in quotes where it names the button or the menu entry.
 *
 * <p>"Dedicated" marks the plug-in's own Neovim, the one Get Neovim installs into the plug-in's
 * folder ({@link PluginNeovim}); "checked" says its files matched the pinned release
 * ({@link PluginNeovim#verified}). A program the user names is run as it is: nothing about it is
 * checked, so the note says so.
 *
 * <p>When the note finds the verified dedicated Neovim, the page has nothing to get: it hides its
 * <em>Get Neovim</em> button and offers a small <em>Reinstall...</em> link instead
 * ({@link Note#current}). It also hides the button while the option names another program and the
 * dedicated Neovim checks out ({@link #offering}): installing the same files again would change nothing.
 *
 * <p>If the plug-in's folder holds anything it no longer uses ({@link PluginNeovim#unusedEntries}),
 * such as an archive extracted there by hand before 0.2.1, the note carries lines that say so
 * ({@link Note#unused}, shown under it in the normal color), one full path a line, so each stands apart and can be copied whole ({@link #unusedLines}):
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
 * caller runs it off the UI thread. A named program is always run here: it is the one the option
 * chose on purpose, and the plug-in runs it anyway (before 0.2.1's change to a startup option, a
 * program half-typed into the field was not, as it could name another user's file). {@link #note} only builds the note, and is what the unit tests
 * check.
 */
public final class NvimProbe {

    /** The first of the {@link #unusedLines}. */
    public static final String UNUSED_HEADING = "Not used by the plug-in, you can delete:";

    private NvimProbe() {
    }

    /**
     * What the note says, and whether it reports a problem: Neovim would not start, or would start
     * one too old. The page shows a problem in the theme's error color.
     *
     * @param text    the note's text
     * @param problem whether starting Neovim with this value would fail
     * @param current whether it is the verified dedicated Neovim: then there is nothing to get, and
     *                the page offers only a reinstall
     * @param getNeovim whether the page shows the <em>Get Neovim</em> button: not when the note is
     *                {@code current}, and not when the option names another program while the
     *                dedicated Neovim checks out, since the fix is then to remove the option and
     *                installing the same files again changes nothing
     *                ({@link StartFailureReport#offersGetNeovim}, the notice's same rule)
     * @param unused  the {@link #unusedLines} about the plug-in's folder, or {@code null}: shown apart
     *                from the note, in the normal color, as they are no part of a problem
     */
    public record Note(String text, boolean problem, boolean current, boolean getNeovim, String unused) {

        /** A note with the button offered unless it is {@code current}, and no unused lines. */
        public Note(String text, boolean problem, boolean current) {
            this(text, problem, current, !current, null);
        }

        /** A note that does not find the verified dedicated Neovim (all but one kind). */
        public Note(String text, boolean problem) {
            this(text, problem, false);
        }

        /** This note with the button offered or not. */
        public Note withGetNeovim(boolean offered) {
            return new Note(text, problem, current, offered, unused);
        }

        /** This note with the unused lines, or {@code null} for none. */
        public Note withUnused(String lines) {
            return new Note(text, problem, current, getNeovim, lines);
        }
    }

    /**
     * Resolves {@code configured} as starting a session would ({@link NvimExecutable#find}), asks a
     * named program for its version, and describes the result.
     *
     * @param configured the option's value ({@link NeovimOption#value}), blank for the dedicated Neovim
     */
    public static Note describe(String configured) {
        NvimExecutable.Resolved resolved = NvimExecutable.find(configured);
        Path folder = NvimExecutable.dedicatedFolder();
        boolean ask = resolved.kind() == NvimExecutable.Kind.PROGRAM;
        NeovimVersion version = ask ? InstalledVersions.of(Path.of(resolved.command())) : null;
        Optional<PluginNeovim.Unverified> unverified = resolved.kind() == NvimExecutable.Kind.NO_DEDICATED
                ? PluginNeovim.unverified(PluginNeovim.searchHomes(), PlatformUtil.isWindows(), NeovimRelease.bundled())
                : Optional.empty();
        Note note = note(resolved, version, unverified, folder, NeovimRelease.bundled().version());
        boolean named = resolved.kind() != NvimExecutable.Kind.DEDICATED
                && resolved.kind() != NvimExecutable.Kind.NO_DEDICATED;
        if (named) { // only then does the answer depend on the dedicated Neovim checking out
            boolean windows = PlatformUtil.isWindows();
            boolean verified = PluginNeovim.searchHomes().stream().anyMatch(h -> PluginNeovim.verified(h, windows));
            note = offering(note, resolved, verified);
        }
        return note.withUnused(unusedLines(PluginNeovim.unusedEntries(folder), folder));
    }

    /**
     * The line about a dedicated Neovim that fails the check. Example:
     * {@code The dedicated Neovim's files differ from Neovim 0.12.6's release (an older one, or damaged): Get Neovim replaces it (/home/you/.../neovim/la/bin/nvim)}
     */
    public static String unverifiedLine(PluginNeovim.Unverified install, NeovimVersion offered) {
        return "The dedicated Neovim's files differ from Neovim " + offered + "'s release (an older one, or "
                + "damaged): \"Get Neovim\" replaces it (" + install.program() + ")";
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
     * The note, from what {@link #describe} found: pure, so tests and the checks gallery
     * (ChecksGalleryTest) can show any state without one on disk.
     *
     * @param resolved   what the value names ({@link NvimExecutable#find})
     * @param version    a named program's version ({@link NvimExecutable.Kind#PROGRAM}), or
     *                   {@code null} if it did not run as Neovim
     * @param unverified a dedicated Neovim that fails the check, for a blank value that found none
     * @param dedicated  the plug-in's Neovim folder: where Get Neovim installs
     * @param offered    the version Get Neovim installs ({@link NeovimRelease#version})
     */
    public static Note note(NvimExecutable.Resolved resolved, NeovimVersion version,
            Optional<PluginNeovim.Unverified> unverified, Path dedicated, NeovimVersion offered) {
        String program = resolved.command();
        String from = "from -D" + NeovimOption.PROPERTY;
        return switch (resolved.kind()) {
            case DEDICATED -> new Note("Found: dedicated Neovim " + offered + ", checked, " + program, false, true);
            case NO_DEDICATED -> problem(unverified.map(u -> unverifiedLine(u, offered))
                    .orElse("No dedicated Neovim yet. \"Get Neovim " + offered + "\" installs it into " + dedicated));
            case PROGRAM -> {
                if (version == null) {
                    // Not "Found:": nothing usable was found (a script, another program, a broken copy).
                    yield problem("Not a working Neovim (no answer to --version): " + program + ", " + from);
                }
                if (!version.isSupported()) {
                    yield problem("Found: Neovim " + version + " (too old: Neovim for Eclipse needs "
                            + NeovimVersion.MINIMUM + " or newer), " + program + ", " + from);
                }
                yield new Note("Found: Neovim " + version + ", " + program + ", " + from + ". Not the dedicated "
                        + "Neovim: not checked by the plug-in, and unsupported. Remove the option to use the dedicated "
                        + "one.", false);
            }
            case FOLDER -> problem(program + " (" + from + ") is a folder: name the Neovim program inside it, such as "
                    + Path.of(program).resolve("bin").resolve(NvimExecutable.defaultCommandName()));
            case MISSING -> problem("No Neovim program at " + program + " (" + from + "). Remove the option to use "
                    + "the dedicated Neovim.");
            case NOT_ABSOLUTE -> problem("-D" + NeovimOption.PROPERTY + " must name the absolute path of a Neovim "
                    + "program (a leading ~ is fine): " + program + ". Remove the option to use the dedicated Neovim.");
        };
    }

    /**
     * {@code note} with the Get Neovim button offered as the "Neovim not started" notice offers it
     * ({@link StartFailureReport#offersGetNeovim}): for a program the option names, only while the
     * dedicated Neovim is missing or fails the check. Example: {@code -Dneovim4eclipse.nvim=/opt/nvim}
     * (a folder) with the dedicated Neovim installed gives no button: removing the option is the fix.
     */
    static Note offering(Note note, NvimExecutable.Resolved resolved, boolean dedicatedVerified) {
        return note.withGetNeovim(!note.current() && StartFailureReport.offersGetNeovim(resolved, dedicatedVerified));
    }

    private static Note problem(String text) {
        return new Note(text, true);
    }
}
