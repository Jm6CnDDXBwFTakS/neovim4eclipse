// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * What the screen and the threads looked like when a UI test's wait ran out, written next to the
 * test reports, so that a failure that happens only now and then can be looked at afterwards. A
 * timeout says only what did not happen; this says what was there instead. Example: a wait for the
 * Neovim menu that timed out on CI left {@code 01-the-neovim-menu-is-shown.png} (the windows as
 * the screen showed them), and {@code 01-the-neovim-menu-is-shown.txt}: every window with its
 * title, which one was active and which control had the focus, every thread's whole stack (a UI
 * thread stuck behind a lock shows there), and the end of the test Eclipse's own log (where
 * Eclipse puts an error it caught itself, such as one in a menu's {@code visibleWhen}).
 *
 * <p>Written into {@code failure.evidence.dir} (the test pom: {@code target/failure-evidence}),
 * which the CI workflow keeps as an artifact when a job fails (CONTRIBUTING.md, "When CI fails").
 * Without the property nothing is written. At most {@value #MAX_REPORTS} per run: one stuck
 * Eclipse can time out every later test, and the first reports say the most.
 *
 * <p>The pictures show only the windows of the test Eclipse, one picture per visible window
 * ({@code -1.png}, {@code -2.png}), not the screen around or between them: on a developer's own
 * desktop that would take in whatever else is open. A window of another program lying over one of
 * them is still in its picture, as the screen shows it, so look at a picture before sharing it.
 * How the screen is read, and where it cannot be (Wayland; macOS without the screen recording
 * permission gives the desktop only): {@link WindowPicture}. Every part is written on its own,
 * and a part that fails says so in the text; nothing here may replace the timeout's own message.
 */
public final class FailureEvidence {

    /** The system property naming the folder; the test pom sets it. */
    static final String DIR_PROPERTY = "failure.evidence.dir";

    /** The most reports one run writes. */
    static final int MAX_REPORTS = 20;

    /** How many lines of the end of Eclipse's log go into the text. */
    private static final int LOG_LINES = 60;

    /** How much of the end of Eclipse's log is read for those lines. */
    private static final int LOG_TAIL_BYTES = 64 * 1024;

    private static final AtomicInteger REPORTS = new AtomicInteger();

    private FailureEvidence() {
    }

    /** For FailureEvidenceTest: puts the count of reports back, so its own report takes no slot. */
    static void setReportCount(int count) {
        REPORTS.set(count);
    }

    /** For FailureEvidenceTest: how many reports this run has counted. */
    static int reportCount() {
        return REPORTS.get();
    }

    /**
     * Writes the evidence for a wait for {@code what} that ran out, and answers the words to add to
     * the failure message: where the files are, or "" when nothing was written. UI thread.
     */
    public static String record(String what) {
        try {
            return write(what);
        } catch (IOException | RuntimeException | Error e) {
            // An SWTError (no more handles) is an Error; the timeout's message must survive it.
            return "\n  (failure evidence not written: " + e + ")";
        }
    }

    private static String write(String what) throws IOException {
        String folder = System.getProperty(DIR_PROPERTY, "");
        if (folder.isBlank()) {
            return "";
        }
        int number = REPORTS.incrementAndGet();
        if (number > MAX_REPORTS) {
            return number == MAX_REPORTS + 1 ? "\n  (no more failure evidence in this run: " + MAX_REPORTS + " written)" : "";
        }
        File base = new File(folder, String.format(Locale.ROOT, "%02d-%s", number, slug(what)));
        Files.createDirectories(base.toPath().getParent());
        StringBuilder text = new StringBuilder();
        text.append("Timed out waiting for: ").append(what).append('\n');
        text.append("At: ").append(LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME)).append("\n\n");
        Display display = Display.getCurrent();
        int pictures = 0;
        if (display == null) {
            text.append("(not on the UI thread: no windows, no pictures)\n\n");
        } else {
            text.append(guarded("Windows", () -> windows(display))).append('\n');
            List<String> lines = new ArrayList<>();
            try {
                pictures = pictures(display, base, lines);
            } catch (RuntimeException | Error e) {
                lines.add("stopped (" + e + ")"); // the text is still written
            }
            text.append("Pictures:\n");
            lines.forEach(l -> text.append("  ").append(l).append('\n'));
            text.append('\n');
        }
        text.append(guarded("Threads", FailureEvidence::threads)).append('\n');
        text.append(guarded("End of Eclipse's log", FailureEvidence::logTail));
        Files.writeString(new File(base.getPath() + ".txt").toPath(), text, StandardCharsets.UTF_8);
        return "\n  evidence: " + base.getPath() + ".txt" + (pictures > 0 ? " (and " + pictures + " picture(s), .png)" : "");
    }

    /** {@code part}, or a line saying why it is missing: one part that fails leaves the others. */
    private static String guarded(String name, java.util.function.Supplier<String> part) {
        try {
            return part.get();
        } catch (RuntimeException | Error e) {
            return name + ": not written (" + e + ")\n";
        }
    }

    /** {@code what} as a file name: lower case, letters and digits, dashes between words. Example: "the : box to open" gives "the-box-to-open". */
    static String slug(String what) {
        String slug = what.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.length() > 60) {
            slug = slug.substring(0, 60).replaceAll("-$", "");
        }
        return slug.isEmpty() ? "wait" : slug;
    }

    /** Every shell: title, visible or not, bounds, kind; which is active and which control has the focus. */
    private static String windows(Display display) {
        StringBuilder out = new StringBuilder("Windows (shells), top-level and dialogs:\n");
        Shell active = display.getActiveShell();
        for (Shell shell : display.getShells()) {
            if (shell.isDisposed()) {
                continue;
            }
            int modal = shell.getStyle() & (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL);
            Object data = shell.getData();
            out.append(shell == active ? "  * " : "    ").append('\'').append(shell.getText()).append('\'')
                    .append(shell.isVisible() ? " visible" : " hidden")
                    .append(shell.getMinimized() ? " minimized" : "")
                    .append(modal != 0 ? " modal" : "")
                    .append(" at ").append(shell.getBounds())
                    .append(data == null ? "" : " (" + data.getClass().getName() + ")").append('\n');
        }
        out.append("  (* the active one");
        out.append(active == null ? "; none is active" : "").append(")\n");
        Control focus = display.getFocusControl();
        out.append("Focus: ").append(focus == null ? "none"
                : focus.getClass().getName() + " in '" + focus.getShell().getText() + "'").append('\n');
        return out.toString();
    }

    /**
     * Saves each visible window as the screen shows it, {@code <base>-1.png} and on, adding a line
     * per window to {@code lines}; answers how many were saved.
     */
    private static int pictures(Display display, File base, List<String> lines) {
        int saved = 0;
        Rectangle screen = display.getBounds();
        for (Shell shell : display.getShells()) {
            if (shell.isDisposed() || !shell.isVisible() || shell.getMinimized()) {
                continue;
            }
            Rectangle area = shell.getBounds().intersection(screen);
            String title = "'" + shell.getText() + "'";
            if (area.isEmpty()) {
                lines.add(title + ": none (off the screen)");
                continue;
            }
            File file = new File(base.getPath() + "-" + (saved + 1) + ".png");
            Image image = null;
            GC gc = null;
            try {
                image = new Image(display, area.width, area.height);
                gc = new GC(display);
                gc.copyArea(image, area.x, area.y);
                WindowPicture.savePng(image.getImageData(), file);
                saved++;
                lines.add(title + ": " + file.getName() + ", the screen at " + area);
            } catch (RuntimeException | Error e) {
                // SWTError (no more handles), or an OutOfMemoryError for the image: this window has none.
                lines.add(title + ": none (" + e + ")");
            } finally {
                if (gc != null) {
                    gc.dispose();
                }
                if (image != null) {
                    image.dispose();
                }
            }
        }
        if (lines.isEmpty()) {
            lines.add("none (no window is visible)");
        }
        return saved;
    }

    /**
     * Every thread with its whole stack and its locks. Not {@link ThreadInfo#toString}, which stops
     * after eight frames: the frame that matters in a stuck UI thread is often deeper.
     */
    private static String threads() {
        StringBuilder out = new StringBuilder("Threads:\n");
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            long[] deadlocked = bean.findDeadlockedThreads();
            if (deadlocked != null) {
                out.append("  DEADLOCK among threads ").append(Arrays.toString(deadlocked)).append('\n');
            }
            for (ThreadInfo info : bean.dumpAllThreads(true, true)) {
                out.append('\n').append('"').append(info.getThreadName()).append("\" id=").append(info.getThreadId())
                        .append(' ').append(info.getThreadState());
                LockInfo lock = info.getLockInfo();
                if (lock != null) {
                    out.append(" waiting for ").append(lock);
                    if (info.getLockOwnerName() != null) {
                        out.append(" held by \"").append(info.getLockOwnerName()).append('"');
                    }
                }
                out.append('\n');
                StackTraceElement[] stack = info.getStackTrace();
                MonitorInfo[] monitors = info.getLockedMonitors();
                for (int i = 0; i < stack.length; i++) {
                    out.append("    at ").append(stack[i]).append('\n');
                    for (MonitorInfo monitor : monitors) {
                        if (monitor.getLockedStackDepth() == i) {
                            out.append("      - locked ").append(monitor).append('\n');
                        }
                    }
                }
                for (LockInfo synchronizer : info.getLockedSynchronizers()) {
                    out.append("    - holds ").append(synchronizer).append('\n');
                }
            }
        } catch (RuntimeException e) {
            out.append("  (no thread dump: ").append(e).append(")\n");
        }
        return out.toString();
    }

    /**
     * The last {@value #LOG_LINES} lines of the test Eclipse's log ({@code .metadata/.log}), read
     * from its last {@value #LOG_TAIL_BYTES} bytes only (the log of a long run can be large), with
     * any byte that is not UTF-8 shown as a replacement character rather than failing the read.
     */
    private static String logTail() {
        StringBuilder out = new StringBuilder("End of Eclipse's log");
        try {
            IPath location = Platform.getLogFileLocation();
            Path log = location == null ? null : location.toFile().toPath();
            if (log == null || !Files.isRegularFile(log)) {
                return out.append(": no log yet\n").toString();
            }
            byte[] tail;
            try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
                long start = Math.max(0, file.length() - LOG_TAIL_BYTES);
                tail = new byte[(int) (file.length() - start)];
                file.seek(start);
                file.readFully(tail);
            }
            String[] lines = new String(tail, StandardCharsets.UTF_8).split("\\R", -1);
            // Read from the middle of the file, the first line is a piece of one; the log ends with
            // a line break, which leaves an empty last element.
            int first = tail.length < Files.size(log) ? 1 : 0;
            int end = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
            out.append(" (").append(log).append("):\n");
            for (int i = Math.max(first, end - LOG_LINES); i < end; i++) {
                out.append("  ").append(lines[i]).append('\n');
            }
        } catch (IOException | RuntimeException e) {
            out.append(": not read (").append(e).append(")\n");
        }
        return out.toString();
    }
}
