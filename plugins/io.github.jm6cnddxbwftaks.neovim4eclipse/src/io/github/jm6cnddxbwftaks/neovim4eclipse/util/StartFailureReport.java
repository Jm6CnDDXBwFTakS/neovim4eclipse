// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides when to tell the user that Neovim could not be started, and what to say: the part of
 * {@code editor/StartFailureNotice} that needs no Eclipse, so that it is unit-tested. The notice
 * itself (the dialog, the UI thread) stays in that class.
 *
 * <h2>Once per executable</h2>
 * Attaching is attempted every time a text editor is activated, so without a guard a missing
 * {@code nvim} would raise a dialog on every editor switch. {@link #shouldReport} says yes once for
 * each configured executable:
 *
 * <pre>
 *   shouldReport("nvim")       true    first failure: show the notice
 *   shouldReport("nvim")       false   the same executable failed again: stay quiet
 *   shouldReport("/opt/nvim")  true    the preference changed, and that one failed too
 *   started()                          a start succeeded ...
 *   shouldReport("/opt/nvim")  true    ... so a later failure is news again
 * </pre>
 *
 * <p>Not thread-safe: the caller uses one instance from one thread (the SWT UI thread).
 */
public final class StartFailureReport {

    /** The dialog's title. */
    public static final String TITLE = "Neovim not started";

    /**
     * Where Neovim's release archives are, for an install by hand. Get Neovim installs the plug-in's
     * own if the system's is older than {@link NeovimVersion#MINIMUM}; an archive extracted anywhere
     * works too.
     */
    public static final String RELEASES_URL = "https://github.com/neovim/neovim/releases";

    /** Where the Neovim preference page is, for readers who close the dialog without Open Preferences. */
    static final String PREFERENCES_PATH = "Window > Preferences > Neovim";

    /** The executable whose failure was last reported; {@code null} after a successful start. */
    private String reportedFor;

    /** Neovim started: a later failure will be reported again. */
    public void started() {
        reportedFor = null;
    }

    /**
     * Whether a failure to start {@code configured} should be shown, and if so, remembers that it was.
     *
     * @param configured the preference value (blank, a folder or a path) that failed
     */
    public boolean shouldReport(String configured) {
        if (Objects.equals(reportedFor, configured)) {
            return false;
        }
        reportedFor = configured;
        return true;
    }

    /**
     * The text of the notice. For example, with {@code nvim} not installed:
     *
     * <pre>
     * Neovim for Eclipse could not start Neovim, so editors keep their normal Eclipse keys.
     *
     * Executable: nvim
     * Error: Cannot run program "nvim": error=2, No such file or directory
     *
     * Install Neovim 0.12.6 or newer. The simplest way: Get Neovim 0.12.6 downloads it from github.com,
     * or installs the release archive from a file, into ~/.local/share/neovim4eclipse-plugin/neovim; leave
     * the Neovim preference blank, which uses it first, then PATH, then the usual install folders. Or
     * extract a release archive from ... anywhere and enter its folder on the Neovim preference page
     * (Window > Preferences > Neovim).
     * Eclipse started from the Dock, a desktop menu or a PDE launch may not see your shell's PATH or ...
     * </pre>
     *
     * If the plug-in's folder holds entries it no longer uses, a last paragraph names them
     * ({@link NvimProbe#unusedLines}).
     *
     * With an old Neovim the error line says so: {@code Error: Neovim 0.12.4 is too old: Neovim for
     * Eclipse needs 0.12.6 or newer.}
     *
     * @param executable the executable that was run, as {@link NvimExecutable#resolve} found it
     * @param error      why the start failed; its innermost cause is shown ({@link #rootMessage})
     * @param unused     entries of the plug-in's Neovim folder it no longer uses
     *                   ({@link PluginNeovim#unusedEntries}); may be empty
     * @param outdated   the plug-in's own Neovim if it is older than the minimum
     *                   ({@link PluginNeovim#outdated}), named first in the advice; may be empty
     */
    public static String message(String executable, Throwable error, List<String> unused,
            Optional<PluginNeovim.Outdated> outdated) {
        String unusedLines = NvimProbe.unusedLines(unused, NvimExecutable.dedicatedFolder());
        String outdatedLine = outdated.map(o -> NvimProbe.outdatedLine(o) + ".\n").orElse("");
        return "Neovim for Eclipse could not start Neovim, so editors keep their normal Eclipse keys.\n\n"
                + "Executable: " + executable + "\n"
                + "Error: " + rootMessage(error) + "\n\n"
                + outdatedLine
                + "Install Neovim " + NeovimVersion.MINIMUM + " or newer. The simplest way: Get Neovim "
                + NeovimVersion.MINIMUM + " downloads it from github.com, or installs the release archive from a "
                + "file, into " + NvimExecutable.dedicatedFolder() + "; leave the Neovim preference blank, which "
                + "uses it first, then PATH, then the usual install folders. Or extract a release archive from "
                + RELEASES_URL + " anywhere and enter its folder on the Neovim preference page ("
                + PREFERENCES_PATH + ").\n"
                + "Eclipse started from the Dock, a desktop menu or a PDE launch may not see your shell's PATH "
                + "or environment variables."
                + (unusedLines == null ? "" : "\n\n" + unusedLines);
    }

    /**
     * The message of the innermost cause, which says what actually went wrong: the start fails as a
     * wrapper exception (the session could not start) around the {@code IOException} from
     * {@code ProcessBuilder.start()} ("No such file or directory"). A cause without a message is
     * named by its class instead, for example {@code TimeoutException}.
     */
    static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
