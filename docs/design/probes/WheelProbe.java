// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * VIEWPORT.md, decision D9 and experiment X10: what SWT reports when you scroll a StyledText with
 * a mouse wheel and with a touchpad. Its log is the input for {@code util/WheelScroll}'s tests: a
 * new machine (a real Linux or Windows touchpad) is measured with this and becomes a test case.
 *
 * <p>Not part of the build (Maven compiles nothing under docs/). Java runs the single source file
 * directly; the SWT jar of your platform is in the Maven cache once the project has been built, for
 * example {@code ~/.m2/repository/p2/osgi/bundle/org.eclipse.swt.gtk.linux.x86_64/<version>/*.jar}
 * ({@code org.eclipse.swt.cocoa.macosx.aarch64}, {@code org.eclipse.swt.win32.win32.x86_64}, ...):
 *
 * <pre>
 *   java -cp &lt;swt.jar&gt; docs/design/probes/WheelProbe.java [log file]            (Linux, Windows)
 *   java -XstartOnFirstThread -cp &lt;swt.jar&gt; docs/design/probes/WheelProbe.java  (macOS)
 * </pre>
 *
 * In the window, press a digit key to start a phase, then scroll; close the window when done:
 * 1 mouse wheel, a few notches slowly; 2 touchpad, two fingers, slowly; 3 touchpad, a fast flick
 * and let go (momentum); 4 touchpad, slowly sideways. Every wheel and gesture event is logged with
 * its count and detail and how many pixels the StyledText moved; a summary per phase comes last.
 */
public class WheelProbe {

    record Row(long ms, int phase, String type, int count, int detail, int state, int pixelsBefore) {
    }

    public static void main(String[] args) throws IOException {
        Path log = Path.of(args.length > 0 ? args[0] : "wheel-probe-" + SWT.getPlatform() + ".log");
        Display display = new Display();
        Shell shell = new Shell(display);
        shell.setText("WheelProbe: press 1-4 to start a phase, then scroll; close when done");
        shell.setLayout(new FillLayout());
        StyledText text = new StyledText(shell, SWT.V_SCROLL | SWT.H_SCROLL | SWT.MULTI);
        Font mono = new Font(display, "Monospace", 11, SWT.NORMAL);
        text.setFont(mono);
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 2000; i++) {
            content.append(String.format("%4d  a fairly long line of text so that sideways scrolling has somewhere to go "
                    + "............................................................%n", i));
        }
        text.setText(content.toString());
        text.setEditable(false);

        long start = System.nanoTime();
        int[] phase = { 0 };
        List<Row> rows = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        // Pixels moved by each event: read after SWT has handled it (asyncExec runs after the
        // StyledText's own wheel handling).
        List<String> moved = new ArrayList<>();
        int[] lastTop = { 0, 0 }; // vertical, horizontal

        org.eclipse.swt.widgets.Listener logger = e -> {
            // SWT.MouseWheel is the same constant as SWT.MouseVerticalWheel.
            String type = switch (e.type) {
                case SWT.MouseVerticalWheel -> "VerticalWheel";
                case SWT.MouseHorizontalWheel -> "HorizontalWheel";
                case SWT.Gesture -> "Gesture";
                default -> "type" + e.type;
            };
            long ms = (System.nanoTime() - start) / 1_000_000;
            Row row = new Row(ms, phase[0], type, e.count, e.detail, e.stateMask, text.getTopPixel());
            rows.add(row);
            int index = rows.size() - 1;
            String extra = e.type == SWT.Gesture
                    ? String.format(" x=%d y=%d xDir=%d yDir=%d mag=%.3f rot=%.3f", e.x, e.y, e.xDirection,
                            e.yDirection, e.magnification, e.rotation)
                    : String.format(" x=%d y=%d", e.x, e.y);
            lines.add(String.format("%7d ms phase %d %-15s count=%3d detail=%d state=%s%s", ms, phase[0], type,
                    e.count, e.detail, Integer.toHexString(e.stateMask), extra));
            display.asyncExec(() -> {
                if (!text.isDisposed()) {
                    int v = text.getTopPixel();
                    int h = text.getHorizontalPixel();
                    while (moved.size() <= index) {
                        moved.add("0/0");
                    }
                    moved.set(index, (v - lastTop[0]) + "/" + (h - lastTop[1]));
                    lastTop[0] = v;
                    lastTop[1] = h;
                }
            });
        };
        for (int type : new int[] { SWT.MouseVerticalWheel, SWT.MouseHorizontalWheel, SWT.Gesture }) {
            text.addListener(type, logger);
        }
        text.addListener(SWT.KeyDown, e -> {
            if (e.character >= '1' && e.character <= '9') {
                phase[0] = e.character - '0';
                lines.add(String.format("%7d ms ===== phase %d =====", (System.nanoTime() - start) / 1_000_000, phase[0]));
                shell.setText("WheelProbe: phase " + phase[0] + " (press 1-4 for another; close when done)");
            }
        });

        shell.setSize(900, 700);
        shell.open();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        mono.dispose();
        display.dispose();

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(log, StandardCharsets.UTF_8))) {
            out.printf("WheelProbe on %s, SWT %d, %s %s, Java %s%n", SWT.getPlatform(), SWT.getVersion(),
                    System.getProperty("os.name"), System.getProperty("os.arch"), System.getProperty("java.version"));
            out.println("moved=V/H px: change of getTopPixel() / getHorizontalPixel() after each event");
            int i = 0;
            for (String line : lines) {
                out.print(line);
                if (!line.contains("=====")) {
                    out.print(" moved=" + (i < moved.size() ? moved.get(i) : "?"));
                    i++;
                }
                out.println();
            }
            out.println();
            out.println("Summary per phase and event type: events, counts seen {count=times}, pixels moved, duration");
            Map<String, List<Integer>> byKey = new TreeMap<>();
            for (int r = 0; r < rows.size(); r++) {
                byKey.computeIfAbsent("phase " + rows.get(r).phase() + " " + rows.get(r).type(), k -> new ArrayList<>()).add(r);
            }
            for (var entry : byKey.entrySet()) {
                Map<Integer, Integer> counts = new TreeMap<>();
                int v = 0, h = 0;
                for (int r : entry.getValue()) {
                    counts.merge(rows.get(r).count(), 1, Integer::sum);
                    if (r < moved.size()) {
                        String[] vh = moved.get(r).split("/");
                        v += Integer.parseInt(vh[0]);
                        h += Integer.parseInt(vh[1]);
                    }
                }
                long first = rows.get(entry.getValue().get(0)).ms();
                long last = rows.get(entry.getValue().get(entry.getValue().size() - 1)).ms();
                out.printf("%-26s %4d events, counts %s, %6d px down, %6d px right, %5d ms%n", entry.getKey(),
                        entry.getValue().size(), counts, v, h, last - first);
            }
        }
        System.out.println("Wrote " + log.toAbsolutePath());
    }
}
