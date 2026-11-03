// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage.Point;

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

    /** The executable whose failure was last reported; {@code null} after a successful start. */
    private String reportedFor;

    /** Neovim started: a later failure will be reported again. */
    public void started() {
        reportedFor = null;
    }

    /**
     * Whether a failure to start {@code configured} should be shown, and if so, remembers that it was.
     *
     * @param configured the preference value (blank, or a program's path) that failed
     */
    public boolean shouldReport(String configured) {
        if (Objects.equals(reportedFor, configured)) {
            return false;
        }
        reportedFor = configured;
        return true;
    }

    /** The notice's opening line, the same in every case. */
    static final String INTRO = "Neovim for Eclipse could not start Neovim. Editors keep their normal Eclipse keys.";

    /**
     * The fix when the preference names a program: the dedicated Neovim (installed, or Get Neovim
     * installs it), or a working Neovim of the user's own, with the warning that the plug-in runs that
     * program ({@code --version}, then {@code --embed}) without checking what it is.
     */
    static String programFix(boolean dedicatedVerified) {
        return "Clear the Neovim program preference to use the dedicated Neovim, which "
                + (dedicatedVerified ? "is installed." : "can be installed with Get Neovim.")
                + " Otherwise, point the preference to a working Neovim " + NeovimVersion.MINIMUM + " or newer. The "
                + "plug-in runs that program to check for Neovim, so be sure it is Neovim.";
    }

    /** The fix when the dedicated Neovim matches the release and still did not start. */
    static final String REINSTALL_FIX = "Get Neovim reinstalls Neovim " + NeovimVersion.MINIMUM + ". The Eclipse "
            + "Error Log (Window > Show View > Error Log) has the full error.";

    /**
     * Whether the notice offers <i>Get Neovim</i>. With a blank preference always: to install the
     * dedicated Neovim, replace it, or reinstall one that checks out and still fails. With a program
     * in the preference only while the dedicated Neovim is missing or not the release: once it checks
     * out, the fix is to clear the preference, and installing the same files again changes nothing.
     *
     * @param resolved          what the preference names ({@link NvimExecutable#find(String)})
     * @param dedicatedVerified whether a dedicated Neovim is installed and matches the release
     */
    public static boolean offersGetNeovim(NvimExecutable.Resolved resolved, boolean dedicatedVerified) {
        return isDedicated(resolved) || !dedicatedVerified;
    }

    /**
     * The reason to open <i>Get Neovim</i> instead of the notice, or empty for the notice: with a
     * blank preference and no verified dedicated Neovim, installing it is the one fix, so its dialog
     * opens at once, with this as its <i>Why</i> line. Cancelling it shows nothing more: the editor keeps its Eclipse keys, and Get Neovim stays in the Neovim menu and on
     * the preference page. Example: {@code The dedicated Neovim is not installed.}
     */
    public static Optional<String> getNeovimInstead(NvimExecutable.Resolved resolved,
            Optional<PluginNeovim.Unverified> unverified) {
        return resolved.kind() == NvimExecutable.Kind.NO_DEDICATED
                ? Optional.of(dedicatedReason(resolved, unverified).text())
                : Optional.empty();
    }

    /** Whether the preference is blank, so the dedicated Neovim was to start. */
    private static boolean isDedicated(NvimExecutable.Resolved resolved) {
        return resolved.kind() == NvimExecutable.Kind.DEDICATED || resolved.kind() == NvimExecutable.Kind.NO_DEDICATED;
    }

    /**
     * Why the start failed, as the notice says it: one plain sentence, and whether the innermost error
     * follows on an <i>Error</i> row of its own (only where the sentence cannot say more).
     */
    record Reason(String text, boolean showError) {
    }

    /**
     * The notice's parts, which {@code dialogs/HeadedMessageDialog} lays out as the welcome and Get
     * Neovim's dialogs are ({@link HeadedMessage}): for a program in the preference a <i>Program</i>
     * row with its path, then a <i>Reason</i>, an <i>Error</i> where only the innermost error can say
     * more, a <i>Fix</i>, and, if the plug-in's folder holds entries it no longer uses, an
     * <i>Unused</i> point, one row per entry, each its full path with Copy ({@code [..]} below is a
     * field with Copy). Each sentence states one fact. What the preference names
     * ({@link NvimExecutable.Kind}) decides what the notice can say:
     *
     * <p><b>Blank: the dedicated Neovim.</b> <i>Get Neovim</i> installed it and the plug-in checks it,
     * so the reason is one the plug-in knows: it is not installed, its files differ from the release
     * (an older one left by an update of the plug-in, or a damaged one), or it matches the release and
     * still did not start, the one case with an <i>Error</i> row; Get Neovim can reinstall it, and the
     * Error Log has the full error. Example:
     *
     * <pre>
     *   Neovim for Eclipse could not start Neovim. Editors keep their normal Eclipse keys.
     *
     *   Reason   The dedicated Neovim is not installed.
     *   Fix      Get Neovim installs Neovim 0.12.6 into the plug-in's folder. It downloads the
     *            release from github.com, or takes a release archive file.
     *            [/home/you/.local/share/neovim4eclipse-plugin/neovim]
     * </pre>
     *
     * <p><b>A program: unsupported.</b> The plug-in runs the program the preference names unchecked,
     * so all it can say is what it sees: the path is not absolute, names a folder or nothing, or the
     * program is there and did not start. Then {@code nvim --version} says whether it is a Neovim, and
     * which version. Example:
     *
     * <pre>
     *   Program  [/opt/nvim/bin/nvim]
     *   Reason   It is Neovim 0.11.2. Neovim for Eclipse needs 0.12.6 or newer.
     *   Fix      Clear the Neovim program preference to use the dedicated Neovim, which can be
     *            installed with Get Neovim. Otherwise, point the preference to a working Neovim
     *            0.12.6 or newer. The plug-in runs that program to check for Neovim, so be sure it
     *            is Neovim.
     * </pre>
     *
     * Before, the notice was one block of text, in which the folder and the error ran into the advice,
     * and it showed the program and the error even where they told the user nothing (seen 2026-11-03 on
     * Windows).
     *
     * @param resolved   what the preference names ({@link NvimExecutable#find(String)}); for a value
     *                   other than blank, its {@link NvimExecutable.Resolved#command() command} is the
     *                   path the <i>Program</i> row shows
     * @param error      why the start failed
     * @param unused     entries of the plug-in's Neovim folder it no longer uses
     *                   ({@link PluginNeovim#unusedEntries}); may be empty
     * @param unverified the dedicated Neovim if its files are not the release Get Neovim installs
     *                   ({@link PluginNeovim#unverified}); may be empty. Its version is not shown: an
     *                   install that is not the release is not run, not even for {@code --version}
     * @param dedicatedVerified whether a dedicated Neovim is installed and matches the release
     *                   ({@link PluginNeovim#verified}; see also {@link #offersGetNeovim})
     * @param versionOf  a program's version, or {@code null} if it does not run as Neovim
     *                   ({@link InstalledVersions#of}); asked only about the program a preference names
     */
    public static HeadedMessage message(NvimExecutable.Resolved resolved, Throwable error, List<String> unused,
            Optional<PluginNeovim.Unverified> unverified, boolean dedicatedVerified,
            Function<Path, NeovimVersion> versionOf) {
        Path dedicated = NvimExecutable.dedicatedFolder();
        List<Point> points = new ArrayList<>();
        boolean blank = isDedicated(resolved);
        Reason reason = blank ? dedicatedReason(resolved, unverified) : preferenceReason(resolved, versionOf);
        if (!blank) {
            points.add(new Point("Program", "").withCopy(resolved.command()));
        }
        points.add(new Point("Reason", reason.text()));
        if (reason.showError()) {
            points.add(new Point("Error", rootMessage(error)));
        }
        if (!blank) {
            points.add(new Point("Fix", programFix(dedicatedVerified)));
        } else if (resolved.kind() == NvimExecutable.Kind.DEDICATED) {
            points.add(new Point("Fix", REINSTALL_FIX).withCopy(dedicated.toString()));
        } else {
            points.add(new Point("Fix", dedicatedFix(unverified)).withCopy(dedicated.toString()));
        }
        // One row per entry, so each path has its own Copy; the heading and the text only on the first.
        for (int i = 0; i < unused.size(); i++) {
            boolean first = i == 0;
            points.add(new Point(first ? "Unused" : "", first ? NvimProbe.UNUSED_HEADING : "")
                    .withCopy(dedicated.resolve(unused.get(i)).toString()));
        }
        return new HeadedMessage(INTRO, points, "");
    }

    /** Why the dedicated Neovim did not start: not the release, not installed, or it matches and failed. */
    static Reason dedicatedReason(NvimExecutable.Resolved resolved, Optional<PluginNeovim.Unverified> unverified) {
        if (resolved.kind() == NvimExecutable.Kind.DEDICATED) {
            return new Reason("The dedicated Neovim matches Neovim " + NeovimVersion.MINIMUM + "'s release, and it "
                    + "did not start.", true);
        }
        if (unverified.isPresent()) {
            return new Reason("The dedicated Neovim's files differ from Neovim " + NeovimVersion.MINIMUM
                    + "'s release. The install may be damaged.", false);
        }
        return new Reason("The dedicated Neovim is not installed.", false);
    }

    /** What Get Neovim does for a dedicated Neovim that is missing or not the release. */
    private static String dedicatedFix(Optional<PluginNeovim.Unverified> unverified) {
        if (unverified.isPresent()) {
            return "Get Neovim replaces it with Neovim " + NeovimVersion.MINIMUM + ".";
        }
        return "Get Neovim installs Neovim " + NeovimVersion.MINIMUM + " into the plug-in's folder. It downloads the "
                + "release from github.com, or takes a release archive file.";
    }

    /**
     * What the plug-in can see about the program the preference names, which it runs unchecked. The
     * <i>Program</i> row above it shows the path, so the sentence starts from there. Examples:
     * {@code The file is missing.}, or {@code It is Neovim 0.12.4. Neovim for Eclipse needs 0.12.6 or newer.}
     */
    static Reason preferenceReason(NvimExecutable.Resolved resolved, Function<Path, NeovimVersion> versionOf) {
        return switch (resolved.kind()) {
            case NOT_ABSOLUTE -> new Reason("The path is not absolute.", false);
            case FOLDER -> new Reason("It is a folder, not a program.", false);
            case MISSING -> new Reason("The file is missing.", false);
            case PROGRAM -> programReason(versionOf.apply(Path.of(resolved.command())));
            case DEDICATED, NO_DEDICATED -> throw new IllegalArgumentException("not a program: " + resolved);
        };
    }

    /** What {@code nvim --version} of the program says: no Neovim, too old, or current and it failed. */
    private static Reason programReason(NeovimVersion version) {
        if (version == null) {
            return new Reason("It reports no Neovim version.", true);
        }
        if (!version.isSupported()) {
            return new Reason("It is Neovim " + version + ". Neovim for Eclipse needs " + NeovimVersion.MINIMUM
                    + " or newer.", false);
        }
        return new Reason("It is Neovim " + version + ", and it did not start.", true);
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
