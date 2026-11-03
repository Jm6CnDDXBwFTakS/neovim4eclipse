// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs.HeadedMessageDialog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FilePaths;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GetNeovimText;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InstallProgress;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InstalledVersions;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimDownload;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimInstallException;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimInstaller;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimPlatform;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimRelease;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginFolders;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginNeovim;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ProcessorProbe;

/**
 * <i>Get Neovim</i>: the one entry, on the preference page, in the "Neovim not started" notice and
 * in the Neovim menu, that installs the plug-in's own Neovim (docs/design/DOWNLOAD_NEOVIM.md).
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>{@link #open} shows the confirmation: where it goes, what is downloaded from where, the
 *       pinned SHA-256 with <em>Copy</em>, links to the GitHub release and VirusTotal. Buttons:
 *       <em>Download</em> (default), <em>Install from File…</em>, Cancel.</li>
 *   <li>A background {@link Job} (status bar, Progress view, cancellable) downloads the archive
 *       ({@code util/NeovimDownload}, through Eclipse's proxy settings, {@link EclipseProxies}) or
 *       takes the chosen file, and installs it ({@code util/NeovimInstaller}). Nothing of this runs
 *       on the UI thread.</li>
 *   <li>The done note says where Neovim went and offers what is still needed: <em>Use Dedicated
 *       Neovim</em> when the preference names another Neovim, <em>Restart Neovim</em> when one runs.
 *       Without a running Neovim the active editor attaches at once. The failure dialog offers
 *       <em>Retry</em>, <em>Install from File…</em> and the release page.</li>
 * </ol>
 * Every dialog is a {@link HeadedMessageDialog}, the layout the welcome and the Restart Neovim
 * question have; the words, links and values are {@code util/GetNeovimText}'s, where the unit tests
 * read them. Links open in Eclipse's external browser ({@link ExternalBrowser}). The platform is the
 * real processor's ({@code util/ProcessorProbe}), as known when the dialog opens; the dialog names it,
 * and the Job installs exactly what the dialog showed.
 */
public final class GetNeovim {

    private static final Logger LOG = Logger.getLogger(GetNeovim.class.getName());

    /** Told after every successful install, on the UI thread: the preference page's note, the menu. */
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /** The Restart Neovim command, run through Eclipse so it asks its own question first. */
    private static final String RESTART_COMMAND = "io.github.jm6cnddxbwftaks.neovim4eclipse.commands.restart";

    private GetNeovim() {
    }

    /** Runs {@code listener} on the UI thread after each successful install. */
    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** {@code Get Neovim 0.12.6…}, the label of every entry. */
    public static String label() {
        return GetNeovimText.label(NeovimRelease.bundled()) + "...";
    }

    /** What the user chose. */
    private enum Source {
        DOWNLOAD, FILE
    }

    /** Shows the confirmation and, if confirmed, starts the install. UI thread. */
    public static void open(Shell shell) {
        open(shell, null);
    }

    /**
     * {@link #open(Shell)}, opened by the plug-in rather than by the user: {@code why} is the
     * confirmation's first line ({@code The dedicated Neovim is not installed.}), or {@code null}.
     */
    public static void open(Shell shell, String why) {
        NeovimRelease release = NeovimRelease.bundled();
        Optional<NeovimPlatform> platform = ProcessorProbe.known();
        Optional<NeovimRelease.Archive> archive = platform.flatMap(release::forPlatform);
        if (archive.isEmpty()) {
            show(shell, MessageDialog.INFORMATION, GetNeovimText.noArchive(release.version(),
                    System.getProperty("os.name"), System.getProperty("os.arch")), List.of("Close"));
            return;
        }
        NeovimRelease.Archive a = archive.get();
        Path home = PluginNeovim.home(PluginFolders.neovimFolder(), a.platform());
        switch (show(shell, MessageDialog.QUESTION, GetNeovimText.confirm(release, a, home, why),
                GetNeovimText.CONFIRM_BUTTONS)) {
            case 0 -> start(shell, release, a, Source.DOWNLOAD, null);
            case 1 -> chooseFile(shell, release, a);
            default -> {
                // cancelled
            }
        }
    }

    /**
     * Shows one of Get Neovim's dialogs, its first button the default, and returns the index of the
     * button pressed ({@link HeadedMessageDialog#CLOSED} for Esc). Links open in Eclipse's external
     * browser, so they follow the user's choice in General &gt; Web Browser.
     */
    private static int show(Shell shell, int kind, HeadedMessage message, List<String> buttons) {
        return new HeadedMessageDialog(shell, GetNeovimText.TITLE, kind, message, 0, buttons.toArray(String[]::new))
                .openLinksWith(ExternalBrowser::open).open();
    }

    /** The file chooser for <i>Install from File…</i>, then the install. */
    private static void chooseFile(Shell shell, NeovimRelease release, NeovimRelease.Archive archive) {
        FileDialog files = new FileDialog(shell, SWT.OPEN);
        files.setText("Install " + archive.asset());
        String pattern = archive.kind() == NeovimRelease.Kind.ZIP ? "*.zip" : "*.tar.gz";
        files.setFilterNames(new String[] { archive.asset(), "All files" });
        files.setFilterExtensions(new String[] { pattern, "*" });
        files.setFileName(archive.asset());
        Path downloads = Path.of(System.getProperty("user.home", ""), "Downloads");
        if (Files.isDirectory(downloads)) {
            files.setFilterPath(downloads.toString());
        }
        String chosen = files.open();
        if (chosen != null) {
            start(shell, release, archive, Source.FILE, Path.of(chosen));
        }
    }

    /** Starts the Job. UI thread. */
    private static void start(Shell shell, NeovimRelease release, NeovimRelease.Archive archive, Source source,
            Path file) {
        Activator activator = Activator.getDefault();
        Job job = Job.create((source == Source.DOWNLOAD ? "Downloading Neovim " : "Installing Neovim ")
                + release.version(), monitor -> {
                    run(shell, activator, release, archive, source, file, monitor);
                    // Our own dialogs report the outcome; an error status would add Eclipse's own pop-up.
                    return Status.OK_STATUS;
                });
        job.setUser(true); // a progress dialog first, which the user can send to the background
        job.schedule();
    }

    /** The Job's work, off the UI thread. */
    private static void run(Shell shell, Activator activator, NeovimRelease release, NeovimRelease.Archive archive,
            Source source, Path file, IProgressMonitor monitor) {
        Path folder = PluginFolders.neovimFolder();
        Path home = PluginNeovim.home(folder, archive.platform());
        // Whether Neovim runs from home is asked at the swap, not now: one may start from there
        // while the archive downloads (NeovimInstaller.SwapGate).
        NeovimInstaller.SwapGate gate = swap -> activator.swapUnlessRunningFrom(home, swap);
        InstallProgress progress = new MonitorProgress(monitor);
        try {
            NeovimInstaller.Result result;
            if (source == Source.DOWNLOAD) {
                Path downloaded;
                try (EclipseProxies proxies = EclipseProxies.open()) {
                    if (proxies.socksOnly(archive.url())) {
                        throw NeovimDownload.socksOnly(archive);
                    }
                    downloaded = NeovimDownload.download(archive,
                            NeovimDownload.client(proxies.selector(), proxies.authenticator()), folder, progress);
                }
                try {
                    result = NeovimInstaller.install(downloaded, release, archive.platform(), folder, gate,
                            InstalledVersions::of, progress);
                } finally {
                    // Quietly: a temporary file left behind must not hide whether the install worked.
                    NeovimDownload.deleteQuietly(downloaded);
                }
            } else {
                result = NeovimInstaller.install(file, release, archive.platform(), folder, gate,
                        InstalledVersions::of, progress);
            }
            // The preference as it is now, after the install (the user may have changed it meanwhile).
            boolean otherInUse = namesAnotherNeovim(activator.getNvimExecutable(), home);
            asyncExec(() -> done(shell, release, result, otherInUse));
        } catch (NeovimInstallException e) {
            if (e.reason() != NeovimInstallException.Reason.CANCELLED) {
                asyncExec(() -> failed(shell, release, archive, source, file, e));
            }
        } catch (Exception e) { // a bug
            LOG.log(Level.WARNING, "Get Neovim failed", e);
            asyncExec(() -> failed(shell, release, archive, source, file, new NeovimInstallException(
                    NeovimInstallException.Reason.FILES,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e)));
        } finally {
            monitor.done();
        }
    }

    /**
     * Whether the preference names a Neovim outside {@code home}, so the dedicated one is not used
     * until it is cleared. Off the UI thread: finding the program may run {@code nvim --version}.
     */
    private static boolean namesAnotherNeovim(String configured, Path home) {
        if (configured.isBlank()) {
            return false;
        }
        Path program = NvimExecutable.locate(NvimExecutable.resolve(configured));
        return program == null || !FilePaths.inside(program.toAbsolutePath().toString(),
                home.toAbsolutePath().toString(), FilePaths.Platform.current());
    }

    private static void asyncExec(Runnable r) {
        Display display = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null;
        if (display != null && !display.isDisposed()) {
            display.asyncExec(r);
        }
    }

    /** The done note, and what follows from it. UI thread. */
    private static void done(Shell shell, NeovimRelease release, NeovimInstaller.Result result,
            boolean otherInUse) {
        boolean running = Activator.getDefault().currentSession() != null; // now, not when the Job started
        GetNeovimOffer.recheck();
        LISTENERS.forEach(Runnable::run);
        boolean waits = result.outcome() == NeovimInstaller.Outcome.WAITS_FOR_RESTART;
        List<String> buttons = new ArrayList<>();
        if (otherInUse) {
            buttons.add(GetNeovimText.USE_DEDICATED_BUTTON);
        }
        if (running) {
            buttons.add("&Restart Neovim...");
        }
        buttons.add("Close");
        int pressed = show(liveShell(shell), MessageDialog.INFORMATION,
                GetNeovimText.done(release.version(), result.program(), result.sha256(), waits, otherInUse), buttons);
        String choice = pressed >= 0 && pressed < buttons.size() ? buttons.get(pressed) : "Close";
        if (choice.equals(GetNeovimText.USE_DEDICATED_BUTTON)) {
            // The Activator restarts Neovim on this change, and attaches the active editor.
            Activator.getDefault().getPreferenceStore().setValue(PreferenceConstants.NVIM_EXECUTABLE, "");
        } else if (choice.startsWith("&Restart")) {
            restart();
        } else if (!running && !otherInUse) {
            Activator.getDefault().restartNeovim(); // nothing runs: attach the active editor now
        }
    }

    /** The failure dialog. UI thread. */
    private static void failed(Shell shell, NeovimRelease release, NeovimRelease.Archive archive, Source source,
            Path file, NeovimInstallException e) {
        switch (show(liveShell(shell), MessageDialog.ERROR,
                GetNeovimText.failed(e, release, archive), GetNeovimText.FAILED_BUTTONS)) {
            case 0 -> start(liveShell(shell), release, archive, source, file);
            case 1 -> chooseFile(liveShell(shell), release, archive);
            default -> {
                // closed
            }
        }
    }

    private static void restart() {
        try {
            IHandlerService handlers = PlatformUI.getWorkbench().getService(IHandlerService.class);
            handlers.executeCommand(RESTART_COMMAND, null);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not run Restart Neovim", e);
        }
    }

    /** {@code shell} if it is still open (the preference dialog may have closed meanwhile), else the workbench's. */
    private static Shell liveShell(Shell shell) {
        if (shell != null && !shell.isDisposed()) {
            return shell;
        }
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return window == null ? null : window.getShell();
    }

    /**
     * The Job's progress monitor as the install steps' {@link InstallProgress}. Each step (download,
     * check, unpack, test run) gets an equal share of the bar, filled by bytes where the step knows
     * its size; {@link SubMonitor} keeps the shares apart.
     */
    private static final class MonitorProgress implements InstallProgress {
        private static final int TICKS = 1000;
        private static final int STEPS = 4;
        private final SubMonitor root;
        private SubMonitor current;
        private long total = -1;
        private long done;
        private int ticks;

        MonitorProgress(IProgressMonitor monitor) {
            root = SubMonitor.convert(monitor, STEPS * TICKS);
        }

        @Override
        public void step(String name, long total) {
            root.subTask(name);
            current = root.newChild(TICKS);
            current.setWorkRemaining(TICKS);
            this.total = total;
            this.done = 0;
            this.ticks = 0;
        }

        @Override
        public void worked(long amount) {
            if (current == null || total <= 0) {
                return;
            }
            done += amount;
            int now = (int) Math.min(TICKS, done * TICKS / total);
            if (now > ticks) {
                current.worked(now - ticks);
                ticks = now;
            }
        }

        @Override
        public boolean cancelled() {
            return root.isCanceled();
        }
    }
}
