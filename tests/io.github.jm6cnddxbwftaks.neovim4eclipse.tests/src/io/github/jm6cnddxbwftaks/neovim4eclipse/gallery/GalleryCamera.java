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
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;

/**
 * Takes the pictures of the manual checks' galleries ({@code prefs/ChecksGalleryTest},
 * {@code editor/SearchGalleryTest}): a part of a window, as PNG.
 *
 * <h2>How a picture is taken: the window draws itself</h2>
 * Every system asks the window to draw itself into an image, which {@link ImageLoader} saves as
 * PNG. Nothing is read from the screen, so a picture can only ever show the test's own window:
 * the window need not be in front, and no other program
 * (a chat, a mail, a notification) can end up in a gallery that is sent on. Reading the screen did
 * picture another program once, on Windows, where a newly started program may not take the front.
 * <ul>
 * <li>Linux: {@link Control#print(GC)}. It works on Wayland too and shows the control's inside,
 * without the title bar.
 * <li>Windows: {@code print} draws white there, as SWT calls {@code PrintWindow} without
 * {@code PW_RENDERFULLCONTENT}, which today's themed windows need. The camera makes that call itself
 * ({@code PrintWindow(hwnd, hdc, 2)}) into an image the window's size, then cuts the control's area
 * out of it: {@link #printWindow}.
 * <li>macOS: {@code print} draws white there too, as Cocoa's views draw through layers, which
 * SWT's {@code displayRectIgnoringOpacity} into an image skips. The camera asks the control's own
 * view to cache its drawing in a bitmap ({@code cacheDisplayInRect:toBitmapImageRep:}), which takes
 * the layers in, and converts that bitmap: {@link #cacheDisplay}. No Screen Recording permission is
 * needed.
 * </ul>
 * The Windows and macOS calls are SWT's own internal classes ({@code org.eclipse.swt.internal.win32}
 * and {@code .cocoa}), reached by reflection: the fragment is compiled on every system, and each
 * class exists only in its own system's SWT ({@link WindowsCalls}, {@link MacCalls}).
 *
 * <p>Pictures are drawn at the window's real pixels. With display scaling (Windows at 175% or a
 * Retina Mac) they are larger than the window's SWT size, and sharp. Each PNG gets a sidecar file
 * with the size in SWT's points ({@code 01-start-offer.png.size}: {@code 640 238}), which
 * {@link #img} writes into the page's {@code <img>}, so a page shows each picture at the window's
 * own size, as a user sees it, not twice that.
 *
 * <p>On Windows and macOS the window is made active before its picture ({@link #activate}) only
 * while the test Eclipse is the active program: an inactive window draws its default button and
 * focus in a look users do not see, but taking the front from another program would take the
 * keyboard from whoever works in it. The camera never takes the front; opening a new window (each
 * dialog of the gallery) may still bring the test Eclipse forward, as the system decides. Working
 * in another program during a run is possible, but the pictures may then show the windows
 * inactive.
 *
 * <p>A picture of one color is refused ({@link #blank}): a window that drew nothing must fail the
 * run, not give a gallery of empty pictures. On Windows and macOS the window is drawn again, every
 * {@link #DRAW_INTERVAL_MS} and at most {@link TestWait#millis()}, until two drawings in a row are
 * the same and not blank: a dialog just opened (above all one that another window covers) can
 * draw blank once before its first paint.
 */
public final class GalleryCamera {

    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    private static final boolean MAC = System.getProperty("os.name").startsWith("Mac");

    /** How long apart two drawings are, at least, so that two equal ones mean the window has settled. */
    private static final long DRAW_INTERVAL_MS = 200;

    /** {@code PW_RENDERFULLCONTENT}: draw the window as the desktop shows it (Windows 8.1 and later). */
    private static final int PW_RENDERFULLCONTENT = 2;

    private GalleryCamera() {
    }

    /**
     * Saves {@code area} of {@code control} (in the control's own coordinates) as a PNG file.
     *
     * @param title what the picture shows, for the error when it cannot be taken
     * @throws AssertionError when the picture comes out one color
     */
    public static void save(Control control, Rectangle area, Path file, String title) {
        if (WINDOWS || MAC) {
            // Linux's pictures were always taken as the tests left the window; activating there could
            // move the focus to a first link and draw its focus rectangle into them.
            activate(control.getShell());
        }
        drain(control.getDisplay());
        ImageData data = WINDOWS || MAC ? settled(control, area, title) : printed(control, area);
        if (blank(data)) {
            throw new AssertionError("the picture of " + title + " is one color: nothing was drawn");
        }
        try {
            // The old size goes first: a picture written without its size shows at its own pixels
            // ({@link #img}), never at an earlier run's size.
            Files.deleteIfExists(sizeFile(file));
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] { data };
            loader.save(file.toString(), SWT.IMAGE_PNG);
            Files.writeString(sizeFile(file), area.width + " " + area.height + "\n");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("cannot write the picture " + file, e);
        }
    }

    /**
     * Makes {@code shell} the active window, if it is not and the test Eclipse is the active
     * program (one of its shells is active): see the class comment. Once per shell in effect, since
     * an active shell is left alone; activating it again would move the focus to its first link.
     * Example: a dialog the test just opened over the workbench becomes active; with the user in
     * another program, nothing happens.
     */
    public static void activate(Shell shell) {
        Shell active = shell.getDisplay().getActiveShell();
        if (active != null && active != shell) {
            shell.forceActive();
        }
    }

    /**
     * Deletes the pictures in {@code dir} whose file names match {@code names} (a regular expression
     * for the name before {@code .png}), their size files, and {@code page} if not null. A run
     * starts with this, so a page written after a failure shows only this run's pictures, and
     * "not taken" for the rest, never an earlier run's as if they were new. Example:
     * {@code clear(out, "search-dark-.*", null)} before the dark run's pictures.
     */
    public static void clear(Path dir, String names, String page) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().matches("(" + names + ")\\.png(\\.size)?")) {
                    Files.delete(file);
                }
            }
        }
        if (page != null) {
            Files.deleteIfExists(dir.resolve(page));
        }
    }

    /** The sidecar file with a picture's size in points: {@code 01-start-offer.png.size}. */
    private static Path sizeFile(Path png) {
        return png.resolveSibling(png.getFileName() + ".size");
    }

    /**
     * An {@code <img>} of {@code png}, embedded ({@link #dataUri}), at its window's size in points
     * when its sidecar file has it ({@link #save}): {@code <img src="data:..." width="640" height="238"
     * alt="...">}. {@code alt} is put in as it is: the caller escapes it.
     */
    public static String img(Path png, String alt) throws IOException {
        StringBuilder tag = new StringBuilder("<img src=\"").append(dataUri(png)).append('"');
        Path size = sizeFile(png);
        if (Files.exists(size)) {
            // Two numbers or nothing: a damaged file (a run cut off while writing it) leaves the
            // picture at its own pixels rather than stop the page.
            String[] points = Files.readString(size).strip().split(" ");
            if (points.length == 2 && points[0].matches("[0-9]{1,5}") && points[1].matches("[0-9]{1,5}")) {
                tag.append(" width=\"").append(points[0]).append("\" height=\"").append(points[1]).append('"');
            }
        }
        return tag.append(" alt=\"").append(alt).append("\">").toString();
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

    /**
     * {@code area} of {@code control}, drawn by the window ({@link #printWindow} or
     * {@link #cacheDisplay}) until two drawings {@link #DRAW_INTERVAL_MS} apart are the same and not
     * blank (see the class comment).
     */
    private static ImageData settled(Control control, Rectangle area, String title) {
        ImageData[] previous = new ImageData[1];
        long[] lastDrawn = { 0 };
        waitUntil("a settled picture of " + title, () -> {
            long now = System.currentTimeMillis();
            if (now - lastDrawn[0] < DRAW_INTERVAL_MS) {
                return false;
            }
            lastDrawn[0] = now;
            ImageData drawn = WINDOWS ? printWindow(control, area) : cacheDisplay(control, area);
            boolean same = previous[0] != null && !blank(drawn) && sameImage(drawn, previous[0]);
            previous[0] = drawn;
            return same;
        });
        return previous[0];
    }

    private static boolean sameImage(ImageData a, ImageData b) {
        return a.width == b.width && a.height == b.height && a.depth == b.depth && Arrays.equals(a.data, b.data);
    }

    /**
     * Windows: the whole window through {@code PrintWindow(hwnd, hdc, PW_RENDERFULLCONTENT)}, then
     * the control's area cut out of it.
     *
     * <p>{@code PrintWindow} draws in real pixels, title bar and borders included. The control's
     * origin inside that drawing is its client origin on the screen ({@code ClientToScreen}) less the
     * window's top left ({@code GetWindowRect}), both in pixels. The area, in SWT's points, is scaled
     * by the window's own pixels per point: the window's pixel width over its SWT width. Example: at
     * 175% SWT scales by 2, a 974 pt window is 1948 px, and a 640 pt area comes out 1280 px. (The
     * monitor's zoom is not that factor: at 150% SWT keeps 1 pt = 1 px by default and only fonts
     * grow.) A minimized window draws only its title bar, so it is refused.
     */
    private static ImageData printWindow(Control control, Rectangle area) {
        Shell shell = control.getShell();
        if (shell.getMinimized() || !shell.isVisible()) {
            throw new AssertionError("the window to picture is " + (shell.getMinimized() ? "minimized" : "hidden")
                    + ": it draws only its title bar, or nothing");
        }
        try {
            WindowsCalls n = WindowsCalls.get();
            long window = n.handle().getLong(shell);
            Object rect = n.rectClass().getConstructor().newInstance();
            check((boolean) n.getWindowRect().invoke(null, window, rect), "GetWindowRect");
            int left = n.rectLeft().getInt(rect);
            int top = n.rectTop().getInt(rect);
            int pixelWidth = n.rectRight().getInt(rect) - left;
            int pixelHeight = n.rectBottom().getInt(rect) - top;
            Object origin = n.pointClass().getConstructor().newInstance();
            check((boolean) n.clientToScreen().invoke(null, n.handle().getLong(control), origin), "ClientToScreen");
            double scale = (double) pixelWidth / shell.getBounds().width;

            Display display = control.getDisplay();
            Image image = new Image(display, (int) Math.ceil(pixelWidth / scale), (int) Math.ceil(pixelHeight / scale));
            try {
                GC gc = new GC(image);
                try {
                    check((boolean) n.printWindow().invoke(null, window, n.gcHandle().getLong(gc), PW_RENDERFULLCONTENT),
                            "PrintWindow");
                } finally {
                    gc.dispose();
                }
                ImageData whole = image.getImageDataAtCurrentZoom(); // real pixels, at least pixelWidth x pixelHeight
                if (whole.width < pixelWidth - 1 || whole.height < pixelHeight - 1) {
                    // The image's zoom is below the window's: the drawing is cut off. A larger image
                    // (a fractional SWT scale rounding up) only has room to spare.
                    throw new AssertionError("the image is " + whole.width + "x" + whole.height + " px for a window of "
                            + pixelWidth + "x" + pixelHeight + " px: the display's scale and the window's differ"
                            + " (a window on a monitor scaled unlike the main one?)");
                }
                int x = n.pointX().getInt(origin) - left + (int) Math.round(area.x * scale);
                int y = n.pointY().getInt(origin) - top + (int) Math.round(area.y * scale);
                return crop(whole, x, y, (int) Math.round(area.width * scale), (int) Math.round(area.height * scale));
            } finally {
                image.dispose();
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot draw the window with PrintWindow: " + e, e);
        }
    }

    /** Fails the picture, naming the Windows call, when it returned false. */
    private static void check(boolean succeeded, String call) {
        if (!succeeded) {
            throw new AssertionError(call + " failed for the window to picture");
        }
    }

    /**
     * The {@code width} x {@code height} part of {@code whole} at ({@code x}, {@code y}), row by row.
     * One pixel past the edge, from rounding a scaled area, is let off; more means the origin or the
     * scale is wrong, and fails rather than give a cut-off picture.
     */
    private static ImageData crop(ImageData whole, int x, int y, int width, int height) {
        if (x + width == whole.width + 1) {
            width--;
        }
        if (y + height == whole.height + 1) {
            height--;
        }
        if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > whole.width || y + height > whole.height) {
            throw new AssertionError("the area " + width + "x" + height + " at " + x + "," + y
                    + " is not inside the window's " + whole.width + "x" + whole.height + " pixels");
        }
        ImageData part = new ImageData(width, height, whole.depth, whole.palette);
        int[] row = new int[width];
        for (int line = 0; line < height; line++) {
            whole.getPixels(x, y + line, width, row, 0);
            part.setPixels(0, line, width, row, 0);
        }
        return part;
    }

    /**
     * macOS: {@code area} of the control's own view, cached into a bitmap with
     * {@code bitmapImageRepForCachingDisplayInRect:} and {@code cacheDisplayInRect:toBitmapImageRep:}.
     * SWT's views are flipped, so the area's top-left coordinates serve as they are. The bitmap has
     * the real pixels (twice the area on a Retina screen), as 32-bit RGBA with the alpha last and the
     * colors premultiplied by it ({@code bitmapFormat} 0); each pixel is put over white
     * ({@code c + 255 - a}) into a 24-bit {@link ImageData}. Another format fails the picture.
     */
    private static ImageData cacheDisplay(Control control, Rectangle area) {
        try {
            MacCalls n = MacCalls.get();
            Object view = n.view().get(control);
            Object rect = n.rectClass().getConstructor().newInstance();
            n.rectX().setDouble(rect, area.x);
            n.rectY().setDouble(rect, area.y);
            n.rectWidth().setDouble(rect, area.width);
            n.rectHeight().setDouble(rect, area.height);
            Object bitmap = n.bitmapForCaching().invoke(view, rect);
            n.cacheDisplay().invoke(view, rect, bitmap);
            int width = (int) (long) n.pixelsWide().invoke(bitmap);
            int height = (int) (long) n.pixelsHigh().invoke(bitmap);
            int stride = (int) (long) n.bytesPerRow().invoke(bitmap);
            long bits = (long) n.bitsPerPixel().invoke(bitmap);
            long samples = (long) n.samplesPerPixel().invoke(bitmap);
            long format = (long) n.bitmapFormat().invoke(bitmap);
            if (bits != 32 || samples != 4 || format != 0) {
                throw new AssertionError("the cached bitmap is " + bits + " bits, " + samples + " samples, format "
                        + format + ": only 32-bit RGBA with the alpha last (format 0) is read");
            }
            long data = (long) n.bitmapData().invoke(bitmap);
            if (data == 0 || width <= 0 || height <= 0 || stride < width * 4) {
                // Checked before the copy: memmove from a bad pointer would crash the whole test
                // JVM, with no report of which picture it was.
                throw new AssertionError("the cached bitmap is empty or short: " + width + "x" + height + ", "
                        + stride + " bytes a row, data at " + data);
            }
            byte[] rgba = new byte[stride * height];
            n.memmove().invoke(null, rgba, data, (long) rgba.length);
            ImageData out = new ImageData(width, height, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
            int[] row = new int[width];
            for (int line = 0; line < height; line++) {
                for (int column = 0; column < width; column++) {
                    int i = line * stride + column * 4;
                    int alpha = rgba[i + 3] & 0xFF;
                    int red = (rgba[i] & 0xFF) + 255 - alpha;
                    int green = (rgba[i + 1] & 0xFF) + 255 - alpha;
                    int blue = (rgba[i + 2] & 0xFF) + 255 - alpha;
                    row[column] = red << 16 | green << 8 | blue;
                }
                out.setPixels(0, line, width, row, 0);
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot cache the view's drawing: " + e, e);
        }
    }

    /**
     * The SWT internals {@link #printWindow} calls on Windows, looked up once. Example:
     * {@code printWindow} is {@code OS.PrintWindow(long hwnd, long hdc, int flags)}.
     */
    private record WindowsCalls(Field handle, Field gcHandle, Method getWindowRect, Method clientToScreen,
            Method printWindow, Class<?> rectClass, Field rectLeft, Field rectTop, Field rectRight, Field rectBottom,
            Class<?> pointClass, Field pointX, Field pointY) {

        private static WindowsCalls calls;

        static synchronized WindowsCalls get() throws ReflectiveOperationException {
            if (calls == null) {
                String pkg = "org.eclipse.swt.internal.win32.";
                Class<?> os = Class.forName(pkg + "OS");
                Class<?> rect = Class.forName(pkg + "RECT");
                Class<?> point = Class.forName(pkg + "POINT");
                calls = new WindowsCalls(Control.class.getField("handle"), GC.class.getField("handle"),
                        os.getMethod("GetWindowRect", long.class, rect), os.getMethod("ClientToScreen", long.class, point),
                        os.getMethod("PrintWindow", long.class, long.class, int.class), rect, rect.getField("left"),
                        rect.getField("top"), rect.getField("right"), rect.getField("bottom"), point,
                        point.getField("x"), point.getField("y"));
            }
            return calls;
        }
    }

    /**
     * The SWT internals {@link #cacheDisplay} calls on macOS, looked up once: Cocoa's {@code NSView},
     * {@code NSRect} and {@code NSBitmapImageRep}, and {@code C.memmove} to copy the bitmap's bytes.
     */
    private record MacCalls(Field view, Class<?> rectClass, Field rectX, Field rectY, Field rectWidth,
            Field rectHeight, Method bitmapForCaching, Method cacheDisplay, Method pixelsWide, Method pixelsHigh,
            Method bytesPerRow, Method bitsPerPixel, Method samplesPerPixel, Method bitmapFormat, Method bitmapData,
            Method memmove) {

        private static MacCalls calls;

        static synchronized MacCalls get() throws ReflectiveOperationException {
            if (calls == null) {
                String pkg = "org.eclipse.swt.internal.cocoa.";
                Class<?> view = Class.forName(pkg + "NSView");
                Class<?> rect = Class.forName(pkg + "NSRect");
                Class<?> bitmap = Class.forName(pkg + "NSBitmapImageRep");
                calls = new MacCalls(Control.class.getField("view"), rect, rect.getField("x"), rect.getField("y"),
                        rect.getField("width"), rect.getField("height"),
                        view.getMethod("bitmapImageRepForCachingDisplayInRect", rect),
                        view.getMethod("cacheDisplayInRect", rect, bitmap), bitmap.getMethod("pixelsWide"),
                        bitmap.getMethod("pixelsHigh"), bitmap.getMethod("bytesPerRow"), bitmap.getMethod("bitsPerPixel"),
                        bitmap.getMethod("samplesPerPixel"), bitmap.getMethod("bitmapFormat"),
                        bitmap.getMethod("bitmapData"), Class.forName("org.eclipse.swt.internal.C")
                                .getMethod("memmove", byte[].class, long.class, long.class));
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

    /**
     * Whether every pixel of {@code data} is the same: a picture of nothing. A row at a time
     * ({@link ImageData#getPixels(int, int, int, int[], int)}): a 2x picture has over a million
     * pixels, and the camera asks after every drawing.
     */
    public static boolean blank(ImageData data) {
        int first = data.getPixel(0, 0);
        int[] row = new int[data.width];
        for (int y = 0; y < data.height; y++) {
            data.getPixels(0, y, data.width, row, 0);
            for (int pixel : row) {
                if (pixel != first) {
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
