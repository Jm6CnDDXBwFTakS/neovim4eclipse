// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

import io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs.HeadedMessageDialog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.ExternalBrowser;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.GetNeovim;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GetNeovimText;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InstalledVersions;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimRelease;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginNeovim;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StartFailureReport;

/**
 * Tells the user, once, that Neovim could not be started, and offers <i>Get Neovim</i> (the default
 * button, {@link GetNeovim}; not when a program named by {@code -Dneovim4eclipse.nvim} failed and the
 * dedicated Neovim checks out), and the preference page, which shows which Neovim runs. With the
 * option unset and no verified dedicated Neovim it opens Get Neovim's own dialog instead, saying why
 * ({@link StartFailureReport#getNeovimInstead}); nothing follows a cancel there.
 *
 * <p>Attaching is attempted every time a text editor is activated, so without a guard a missing
 * {@code nvim} would raise a dialog on every editor switch. The notice is shown once per configured
 * executable: again only after a start succeeded and a later one
 * failed. Before this class, the failure only reached the log, and the plug-in silently did nothing.
 *
 * <p>That decision and the text are in {@link StartFailureReport} (unit-tested, no Eclipse); this
 * class moves between threads and shows the dialog, a {@link HeadedMessageDialog} laid out as the
 * welcome and Get Neovim's dialogs are. A link in it would open in Eclipse's external browser.
 *
 * <h2>Threads</h2>
 * Three steps: the UI thread decides whether to report ({@link StartFailureReport#shouldReport},
 * whose state only that thread touches); a background job gathers what the message says; the UI
 * thread shows it. The middle step runs programs and reads files: the named program's
 * {@code --version} (which may hang, {@link InstalledVersions}: up to 18 s) and the dedicated
 * Neovim's check (hashing some 2,000 files), so on the UI thread it would freeze Eclipse.
 */
final class StartFailureNotice {

    /** Used on the UI thread only, so it needs no locking. */
    private static final StartFailureReport REPORT = new StartFailureReport();

    private StartFailureNotice() {
    }

    /** Neovim started: a later failure will be reported again. */
    static void clear() {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(REPORT::started);
        }
    }

    /**
     * Shows the notice unless it was already shown for this executable. Safe to call from any thread.
     *
     * @param configured the option's value (blank, or a program's path)
     * @param error      why the start failed
     */
    static void report(String configured, Throwable error) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (REPORT.shouldReport(configured)) {
                Job job = Job.create("Finding out why Neovim did not start", monitor -> {
                    Notice notice = gather(configured, error);
                    Display ui = Display.getDefault();
                    if (ui != null && !ui.isDisposed()) {
                        ui.asyncExec(() -> show(notice));
                    }
                    return Status.OK_STATUS;
                });
                job.setSystem(true); // a moment's work before a dialog: nothing to show in Progress
                job.schedule();
            }
        });
    }

    /**
     * What the notice shows, gathered off the UI thread.
     *
     * @param getNeovimInstead why Get Neovim's own dialog opens in place of the notice, if it does
     * @param message          the notice's text; {@code null} when Get Neovim opens instead
     * @param offersGetNeovim  whether the notice has a Get Neovim button
     */
    private record Notice(Optional<String> getNeovimInstead, HeadedMessage message, boolean offersGetNeovim) {
    }

    /** The middle step of "Threads": runs programs and hashes files, so never on the UI thread. */
    private static Notice gather(String configured, Throwable error) {
        // The dedicated Neovim's check and nvim --version of the program the option names.
        boolean windows = PlatformUtil.isWindows();
        List<Path> homes = PluginNeovim.searchHomes();
        NvimExecutable.Resolved resolved = NvimExecutable.find(configured);
        Optional<PluginNeovim.Unverified> unverified = PluginNeovim.unverified(homes, windows, NeovimRelease.bundled());
        Optional<String> why = StartFailureReport.getNeovimInstead(resolved, unverified);
        if (why.isPresent()) {
            return new Notice(why, null, false);
        }
        boolean dedicatedVerified = homes.stream().anyMatch(home -> PluginNeovim.verified(home, windows));
        HeadedMessage message = StartFailureReport.message(resolved, error,
                PluginNeovim.unusedEntries(NvimExecutable.dedicatedFolder()), unverified, dedicatedVerified,
                InstalledVersions::of);
        return new Notice(why, message, StartFailureReport.offersGetNeovim(resolved, dedicatedVerified));
    }

    /** The last step of "Threads", on the UI thread. */
    private static void show(Notice notice) {
        Shell shell = PlatformUI.isWorkbenchRunning() && PlatformUI.getWorkbench().getActiveWorkbenchWindow() != null
                ? PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell()
                : null;
        if (notice.getNeovimInstead().isPresent()) {
            GetNeovim.open(shell, notice.getNeovimInstead().get()); // a cancel there ends it: no notice after it
            return;
        }
        HeadedMessage message = notice.message();
        boolean getNeovim = notice.offersGetNeovim();
        // Get Neovim, the default where it is offered: for a missing or older Neovim it is the simplest fix.
        String[] buttons = getNeovim ? new String[] { GetNeovimText.label(NeovimRelease.bundled()), "Open Preferences", "Close" }
                : new String[] { "Open Preferences", "Close" };
        MessageDialog dialog = new HeadedMessageDialog(shell, StartFailureReport.TITLE, MessageDialog.ERROR, message,
                0, buttons).openLinksWith(ExternalBrowser::open);
        int index = dialog.open();
        int pressed = index < 0 || getNeovim ? index : index + 1; // as if Get Neovim were there: 0, 1 or 2
        switch (pressed) {
            case 0 -> GetNeovim.open(shell);
            case 1 -> OpenPreferencesHandler.open(shell);
            default -> {
                // closed (Close, or Esc: -1)
            }
        }
    }
}
