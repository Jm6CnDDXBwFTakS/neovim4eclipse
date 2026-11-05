// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The note shown once when a workspace still holds the removed "Neovim program" preference: since
 * 0.2.1 a Neovim other than the dedicated one is named with the startup option
 * {@code -Dneovim4eclipse.nvim} ({@link NeovimOption}), which the plug-in cannot set for the user, so
 * the note gives the line to use and the old value to copy. {@code editor/OldNeovimProgramNotice}
 * shows it and then removes the value, so it never shows again. Example:
 *
 * <pre>
 *   The Neovim program preference is gone: Neovim for Eclipse no longer reads it, and runs the
 *   dedicated Neovim, which it checks at every start. Most setups need nothing more.
 *
 *   Was      [/usr/local/bin/nvim] (Copy)
 *   Instead  Only if you named that program yourself and need it (no official build for your
 *            system, or a security fix you need that "Get Neovim" does not offer yet), start
 *            Eclipse with:
 *            [-vmargs -Dneovim4eclipse.nvim=/usr/local/bin/nvim] (Copy)
 *            or add this line after -vmargs in eclipse.ini:
 *            [-Dneovim4eclipse.nvim=/usr/local/bin/nvim] (Copy)
 *
 *   That program runs unchecked and unsupported. This note shows once; the old setting is removed.
 * </pre>
 *
 * <p>The dedicated Neovim comes first: most who set the preference did so before 0.2.1, when it was
 * how the plug-in found Neovim at all, and for them nothing is left to do (Get Neovim opens by itself
 * if the dedicated Neovim is missing). Keeping one's own program is the exception, for the same two
 * cases as the preference page's help, and the note says what it costs.
 *
 * <p>"Only if you named that program yourself": the value may not be the user's. A preferences file
 * imported from elsewhere (<i>File &gt; Import &gt; Preferences</i>) can still set the old key,
 * and the note must not present a program planted that way as one to keep. Nothing runs from the
 * note in any case; the user would have to start Eclipse with the line.
 *
 * <p>No note when Eclipse already runs with the option (say, a second workspace that still holds
 * the old value after the first one's note): the old value is only removed, quietly, as the note's
 * "runs the dedicated Neovim" would be wrong then.
 *
 * <p>A value from 0.2.0 may name a folder (the preference was then "Neovim folder or program");
 * the option takes the program, so a folder with {@code bin/nvim} in it is offered as that program,
 * as the preference page's note would suggest ({@link #programFor}).
 *
 * Unit-tested: the words, when it shows, and the folder rule (which looks at the file system).
 */
public final class OldNeovimProgram {

    public static final String TITLE = "Neovim Program Setting Moved";

    /** The one button. */
    public static final List<String> BUTTONS = List.of("Close");

    private OldNeovimProgram() {
    }

    /** Whether a value is stored that should be removed: anything but unset or blank. */
    public static boolean isStored(String stored) {
        return stored != null && !stored.isBlank();
    }

    /**
     * The note for a stored value, or empty when there is none to show: unset, or blank (blank was
     * the default, the dedicated Neovim, which is still what runs), or Eclipse already runs with
     * the option, so the value is only removed.
     *
     * @param stored  the old preference's stored value, or {@code null}
     * @param option  the startup option's value this Eclipse runs with ({@link NeovimOption#value})
     * @param windows whether Windows names the program {@code nvim.exe}
     */
    public static Optional<HeadedMessage> message(String stored, String option, boolean windows) {
        if (!isStored(stored) || (option != null && !option.isBlank())) {
            return Optional.empty();
        }
        String value = stored.strip();
        String program = programFor(value, windows);
        List<HeadedMessage.Point> points = new ArrayList<>();
        points.add(new HeadedMessage.Point("Was", "").withCopy(value));
        points.addAll(NeovimOption.rows("Instead", "Only if you named that program yourself and need it (no official "
                + "build for your system, or a security fix you need that \"Get Neovim\" does not offer yet), start "
                + "Eclipse with:", program, null));
        return Optional.of(new HeadedMessage("The Neovim program preference is gone: Neovim for Eclipse no longer "
                + "reads it, and runs the dedicated Neovim, which it checks at every start. Most setups need nothing "
                + "more.", points, "That program runs unchecked and unsupported. This note shows once; the old "
                + "setting is removed."));
    }

    /**
     * The program to offer for a stored value: the value itself, or, for a folder that holds
     * {@code bin/nvim} ({@code bin\nvim.exe} on Windows), that program. Example: {@code /opt/nvim}
     * gives {@code /opt/nvim/bin/nvim}.
     */
    static String programFor(String value, boolean windows) {
        try {
            Path path = Path.of(NvimExecutable.expandHome(value));
            Path inside = path.resolve("bin").resolve(windows ? "nvim.exe" : "nvim");
            return Files.isDirectory(path) && Files.isRegularFile(inside) ? inside.toString() : value;
        } catch (InvalidPathException e) {
            return value; // not a path at all: shown as stored
        }
    }
}
