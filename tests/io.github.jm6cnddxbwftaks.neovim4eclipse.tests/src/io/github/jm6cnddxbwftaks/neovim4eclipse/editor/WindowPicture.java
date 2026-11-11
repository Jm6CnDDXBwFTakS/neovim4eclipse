// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Pictures of a window as the screen shows it, for the Marketplace screenshots
 * ({@link ScreenshotCaptureTest}) and the README demo ({@link DemoRecordingTest}).
 *
 * <p>How: SWT's {@link GC} on the {@link Display} draws the screen, and
 * {@link GC#copyArea(Image, int, int)} copies the window's rectangle into an image. This reads the
 * X server's pixels, so it works under Xvfb and X11 (popups such as content assist, which are
 * windows of their own, come along); it would not on Wayland, where applications cannot read the
 * screen. The rectangle runs from the top of the shell (so the menu bar, which GTK keeps outside
 * the client area, is in it) to the bottom of its client area, mapped to screen coordinates: the
 * shell's own bounds also hold GTK's invisible frame, which came out as a black band along the
 * bottom.
 */
final class WindowPicture {

    private WindowPicture() {
    }

    /** The screen y where a picture of {@code shell} starts: row 0 of {@link #take}'s image. */
    static int top(Shell shell) {
        Rectangle client = shell.getDisplay().map(shell, null, shell.getClientArea());
        return Math.min(shell.getBounds().y, client.y);
    }

    /** What {@code shell} shows on screen now. The caller disposes of the image. */
    static Image take(Shell shell) {
        Display display = shell.getDisplay();
        Rectangle client = display.map(shell, null, shell.getClientArea());
        int top = top(shell);
        Rectangle bounds = new Rectangle(client.x, top, client.width, client.y + client.height - top);
        Image image = new Image(display, bounds.width, bounds.height);
        GC gc = new GC(display);
        try {
            gc.copyArea(image, bounds.x, bounds.y);
        } finally {
            gc.dispose();
        }
        return image;
    }

    /** Saves {@code data} as a PNG file, making its folder. */
    static void savePng(ImageData data, File file) {
        file.getParentFile().mkdirs();
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] { data };
        loader.save(file.getPath(), SWT.IMAGE_PNG);
    }

    /** The folder the {@code screenshots} profile gives the pictures ({@code screenshots.dir}). */
    static File folder() {
        return new File(System.getProperty("screenshots.dir", "target/screenshots"));
    }
}
