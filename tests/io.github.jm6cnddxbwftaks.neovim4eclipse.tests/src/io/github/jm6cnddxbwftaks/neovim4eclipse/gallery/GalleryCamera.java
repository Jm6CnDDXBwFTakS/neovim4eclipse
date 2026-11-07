// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.gallery;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * Takes the pictures of the manual checks' galleries ({@code prefs/ChecksGalleryTest},
 * {@code editor/SearchGalleryTest}): a part of a window, as PNG.
 *
 * <h2>How a picture is taken</h2>
 * On Linux, {@link Control#print(GC)} asks the window to draw itself into an image, which
 * {@link ImageLoader} saves as PNG. Unlike reading the screen (ScreenshotCaptureTest), it works on
 * Wayland and does not need the window in front; it shows the control's inside, without the title
 * bar.
 *
 * <p>On macOS and Windows {@code print} draws nothing: the pictures came out white. On macOS
 * Cocoa's views are layer-backed, and SWT's {@code displayRectIgnoringOpacity} into an image's
 * context leaves it white (SWT 3.128); on Windows SWT's {@code PrintWindow} call lacks
 * {@code PW_RENDERFULLCONTENT}, which today's themed windows need. There the control's inside is
 * read from the screen instead ({@link GC#copyArea(Image, int, int)} on a {@code GC} of the
 * display), which needs the window in front, and on macOS the Screen Recording permission for the
 * test's Java (System Settings, Privacy &amp; Security). The window is then active, so a link may
 * show a focus rectangle.
 *
 * <p>Reading the screen pictures whatever is on top there, so the window must really be in front:
 * SWT's active shell is not enough. On Windows a newly started program may not take the foreground
 * once the user has worked in another one (the foreground lock): SWT then reports the test's shell
 * active while another program's window still covers it, and a run took a chat window into the
 * gallery that way. {@link #inFront} asks the system itself: on Windows the foreground window, on
 * macOS whether this program is the active application. A window that never comes to the front
 * fails the run instead of picturing another program, which could show anything of the user's.
 *
 * <p>Either way a picture of one color is refused ({@link #blank}): a window that drew nothing must
 * fail the run, not give a gallery of empty pictures. The screen is read again, at most
 * {@link TestWait#millis()}, until the window is in front and two reads {@link #READ_INTERVAL_MS}
 * apart are the same: the last paint may not have reached the screen yet, and Windows fades a new
 * window in over the workbench.
 */
public final class GalleryCamera {

    /**
     * Whether a picture is read from the screen rather than drawn by the window: on macOS and
     * Windows, where {@link Control#print(GC)} draws nothing (see the class comment).
     */
    private static final boolean FROM_SCREEN = !System.getProperty("os.name").startsWith("Linux");

    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private static final boolean MAC = System.getProperty("os.name").startsWith("Mac");

    /**
     * How long apart two reads of the screen are, at least: long enough for a fade-in to change the
     * picture between them, so two equal reads mean the window has settled.
     */
    private static final long READ_INTERVAL_MS = 200;

    /** How long a window that is not in front waits before it asks for the front again. */
    private static final long ASK_AGAIN_MS = 1000;

    private GalleryCamera() {
    }

    /**
     * Saves {@code area} of {@code control} (in the control's own coordinates) as a PNG file.
     *
     * @param title what the picture shows, for the error when it cannot be taken
     * @throws AssertionError when the picture comes out one color
     */
    public static void save(Control control, Rectangle area, Path file, String title) {
        drain(control.getDisplay());
        ImageData data = FROM_SCREEN ? fromScreen(control, area, title) : printed(control, area);
        if (blank(data)) {
            throw new AssertionError("the picture of " + title + " is one color: nothing was drawn");
        }
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] { data };
        loader.save(file.toString(), SWT.IMAGE_PNG);
    }

    /** {@code area} of {@code control}, drawn by the window itself (Linux). */
    private static ImageData printed(Control control, Rectangle area) {
        Display display = control.getDisplay();
        org.eclipse.swt.graphics.Point size = control.getSize();
        Image whole = new Image(display, Math.max(1, size.x), Math.max(1, size.y));
        Image part = new Image(display, area.width, area.height);
        try {
            GC gc = new GC(whole);
            try {
                control.print(gc);
            } finally {
                gc.dispose();
            }
            GC crop = new GC(part);
            try {
                crop.drawImage(whole, area.x, area.y, area.width, area.height, 0, 0, area.width, area.height);
            } finally {
                crop.dispose();
            }
            return part.getImageData();
        } finally {
            part.dispose();
            whole.dispose();
        }
    }

    /** {@code area} of {@code control}, read from the screen once its window is in front and settled. */
    private static ImageData fromScreen(Control control, Rectangle area, String title) {
        Display display = control.getDisplay();
        if (display.getActiveShell() != control.getShell()) {
            control.getShell().forceActive(); // not again when active: it would move the focus to the first link
        }
        Rectangle onScreen = display.map(control, null, area);
        Image image = new Image(display, area.width, area.height);
        ImageData[] previous = new ImageData[1];
        long[] lastRead = { 0 };
        long[] lastAsked = { System.currentTimeMillis() };
        try {
            waitUntil("a settled picture of " + title + " on the screen, its window in front of every other program's"
                    + " (keep other windows away during the run; on macOS: Screen Recording permitted for Java?)",
                    () -> {
                        // Read only once the window is in front, and at most every READ_INTERVAL_MS: Windows
                        // fades a new window in, and until then the screen holds the workbench behind it,
                        // which is not blank either.
                        long now = System.currentTimeMillis();
                        boolean front = display.getActiveShell() == control.getShell() && inFront(control.getShell());
                        if (!front && now - lastAsked[0] >= ASK_AGAIN_MS) {
                            // SWT may call the shell active while the system keeps another program in front
                            // (the Windows foreground lock), so the call above was skipped: ask again, now
                            // and then, at the cost of the focus moving to the first link.
                            control.getShell().forceActive();
                            lastAsked[0] = now;
                        }
                        if (!front || now - lastRead[0] < READ_INTERVAL_MS) {
                            return false;
                        }
                        lastRead[0] = now;
                        GC gc = new GC(display);
                        try {
                            gc.copyArea(image, onScreen.x, onScreen.y);
                        } finally {
                            gc.dispose();
                        }
                        if (!inFront(control.getShell())) {
                            // Covered while it was read (another program's window came up): this read may
                            // hold that window, so it counts neither as a picture nor as half of one.
                            previous[0] = null;
                            return false;
                        }
                        ImageData read = image.getImageData();
                        // Settled: two reads in a row the same, and not blank (a fade changes every pixel).
                        boolean settled = previous[0] != null && !blank(read) && Arrays.equals(read.data, previous[0].data);
                        previous[0] = read;
                        return settled;
                    });
        } finally {
            image.dispose();
        }
        return previous[0];
    }

    /**
     * Whether {@code shell} is in front of every other program's window, as the system sees it (see
     * the class comment). Windows: it is the foreground window ({@code GetForegroundWindow}). macOS:
     * this program is the active application ({@code NSApplication.isActive}); then no other
     * program's ordinary window is above its key window. Elsewhere (Linux prints instead of reading
     * the screen) always true.
     *
     * <p>Through SWT's internal classes, by reflection: the fragment is compiled on every system,
     * and each class exists only in its own system's SWT. Example: on Windows,
     * {@code OS.GetForegroundWindow() == shell.handle}.
     *
     * @throws AssertionError when SWT has no such call (an SWT that changed them)
     */
    static boolean inFront(Shell shell) {
        try {
            if (WINDOWS) {
                return (long) SystemCalls.get().foreground().invoke(null) == SystemCalls.get().handle().getLong(shell);
            }
            if (MAC) {
                Object shared = SystemCalls.get().sharedApplication().invoke(null);
                return (boolean) SystemCalls.get().isActive().invoke(shared);
            }
            return true;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot ask the system which window is in front: " + e, e);
        }
    }

    /**
     * The SWT internals {@link #inFront} calls, looked up once (it runs every few milliseconds while
     * a picture waits). On Windows {@code foreground} and {@code handle}, on macOS
     * {@code sharedApplication} and {@code isActive}; the others are null.
     */
    private record SystemCalls(Method foreground, Field handle, Method sharedApplication, Method isActive) {

        private static SystemCalls calls;

        static synchronized SystemCalls get() throws ReflectiveOperationException {
            if (calls == null) {
                if (WINDOWS) {
                    calls = new SystemCalls(Class.forName("org.eclipse.swt.internal.win32.OS").getMethod("GetForegroundWindow"),
                            Control.class.getField("handle"), null, null);
                } else {
                    Class<?> application = Class.forName("org.eclipse.swt.internal.cocoa.NSApplication");
                    calls = new SystemCalls(null, null, application.getMethod("sharedApplication"),
                            application.getMethod("isActive"));
                }
            }
            return calls;
        }
    }

    /**
     * The picture in {@code png} as a {@code data:} URI, for an {@code <img>} that needs no file
     * beside the page: viewers that show a local page as a snapshot (an app's file pane, a page
     * sent on) load no picture by a relative path. Example: {@code data:image/png;base64,iVBORw0K...}.
     * The PNG files stay in the folder too.
     */
    public static String dataUri(Path png) throws IOException {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(png));
    }

    /** Whether every pixel of {@code data} is the same: a picture of nothing. */
    public static boolean blank(ImageData data) {
        int first = data.getPixel(0, 0);
        for (int y = 0; y < data.height; y++) {
            for (int x = 0; x < data.width; x++) {
                if (data.getPixel(x, y) != first) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Runs the display's pending work (layout, paint), so the picture shows the window as laid out. */
    public static void drain(Display display) {
        while (display.readAndDispatch()) {
            // keep going until nothing is left
        }
    }

    /** Waits for {@code condition} while the display runs, at most {@link TestWait#millis()}. */
    public static void waitUntil(String what, BooleanSupplier condition) {
        Display display = Display.getCurrent();
        long deadline = System.currentTimeMillis() + TestWait.millis();
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            if (!display.readAndDispatch()) {
                display.timerExec(20, () -> { }); // wake up again soon
                display.sleep();
            }
        }
    }
}
