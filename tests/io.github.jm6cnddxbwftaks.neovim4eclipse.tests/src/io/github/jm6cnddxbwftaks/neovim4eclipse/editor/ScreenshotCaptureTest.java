// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;

import java.io.File;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;

/**
 * Takes the screenshots for the Eclipse Marketplace listing and the README, so they can be taken
 * again after the UI changes instead of by hand. Not a test, though named like one (Surefire runs only classes named *Test): it checks only what it needs to reach
 * each state, and writes PNG files.
 *
 * <p>Runs only with the {@code screenshots} profile, which adds the Java development tools (for a
 * syntax-highlighted Java editor) and runs only this class (tagged {@code screenshots}):
 *
 * <pre>
 * xvfb-run -a -s "-screen 0 1600x1000x24" ./mvnw verify -Pscreenshots -Dnvim.path=/usr/bin/nvim
 * </pre>
 *
 * <p>The files land in {@code tests/...tests/target/screenshots/}; copy the ones to keep to
 * {@code docs/images/} (see docs/design/MARKETPLACE.md, "Images"). The {@code -s} option gives
 * Xvfb a screen larger than the window ({@link #WIDTH} by {@link #HEIGHT}); its default, 640x480 at
 * 8 bits per pixel, would cut the window off and lose the colors. Without a window manager the
 * window has no title bar, which suits a listing image.
 *
 * <p>How a screenshot is taken: SWT's {@link GC} on the {@link Display} draws the screen, and
 * {@link GC#copyArea(Image, int, int)} copies the window's rectangle into an image, which
 * {@link ImageLoader} saves as PNG. This reads the X server's pixels, so it works under Xvfb and
 * X11; it would not on Wayland, where applications cannot read the screen.
 */
@Tag("screenshots")
class ScreenshotCaptureTest extends EndToEndTestBase {

    /** The window size: wide enough for the code and the status bar, small enough to stay legible when scaled. */
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 800;

    private static final String JAVA_EDITOR = "org.eclipse.jdt.ui.CompilationUnitEditor";

    /** The code shown: short, realistic, with a word ({@code total}) that {@code :%s} can replace. */
    private static final String SOURCE = """
            package demo;

            import java.util.List;

            /** Adds up the prices of an order. */
            public class Invoice {

                private final List<Double> prices;

                public Invoice(List<Double> prices) {
                    this.prices = prices;
                }

                public double total() {
                    double total = 0;
                    for (double price : prices) {
                        total += price;
                    }
                    return total;
                }

                public boolean isEmpty() {
                    return prices.isEmpty();
                }
            }
            """;

    @Test
    void captureTheListingScreenshots() throws Exception {
        Shell window = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        window.setBounds(0, 0, WIDTH, HEIGHT);
        settle();

        ITextEditor java = openJava("Invoice.java");
        StyledText text = widgetOf(java);

        // 1. Visual line mode over the loop: Neovim's selection shown by Eclipse, the mode in the status bar.
        typeSlowly(text, "16G");
        typeSlowly(text, "V2j");
        waitUntil("visual line mode", TIMEOUT_MS, () -> "V".equals(session.getMode()));
        capture(window, "01-visual-mode.png");
        typeSlowly(text, "\u001b"); // Esc

        // 2. The : command line, with a substitution typed but not yet run.
        Text box = openBox(text, "%s/total/sum/g");
        capture(window, "02-command-line.png");
        Event escape = new Event();
        escape.keyCode = SWT.ESC;
        escape.character = SWT.ESC;
        escape.doit = true;
        requireOpen(box, text).notifyListeners(SWT.KeyDown, escape);
        settle();

        // 3. The preference page, as the Neovim menu opens it.
        // The welcome check box shows its default (on); the UI tests run with it off.
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        boolean welcome = store.getBoolean(PreferenceConstants.SHOW_WELCOME);
        store.setValue(PreferenceConstants.SHOW_WELCOME, true);
        PreferenceDialog preferences = OpenPreferencesHandler.create(window);
        preferences.setBlockOnOpen(false);
        preferences.getShell().setBounds(80, 60, 960, 620);
        preferences.open();
        try {
            UiTestSupport.spin(500);
            capture(preferences.getShell(), "03-preferences.png");
        } finally {
            preferences.close();
            store.setValue(PreferenceConstants.SHOW_WELCOME, welcome);
        }

        java.getSite().getPage().closeEditor(java, false);
    }

    /** Creates {@code name} in the test project with {@link #SOURCE} and opens it in the Java editor. */
    private ITextEditor openJava(String name) throws Exception {
        IFile file = createFile(name, SOURCE);
        IEditorPart opened = IDE.openEditor(page(), file, JAVA_EDITOR, true);
        if (!(opened instanceof ITextEditor java)) {
            throw new AssertionError("not a text editor (is JDT installed? use -Pscreenshots): " + opened);
        }
        waitForAttach(java, name);
        return java;
    }

    /**
     * Saves what {@code shell} shows on screen as {@code target/screenshots/<name>}. The rectangle runs
     * from the top of the shell (so the menu bar, which GTK keeps outside the client area, is in it)
     * to the bottom of its client area, mapped to screen coordinates: the shell's own bounds also
     * hold GTK's invisible frame, which came out as a black band along the bottom.
     */
    private void capture(Shell shell, String name) {
        settle();
        UiTestSupport.spin(300); // let the last paint reach the screen
        Display display = shell.getDisplay();
        Rectangle client = display.map(shell, null, shell.getClientArea());
        int top = Math.min(shell.getBounds().y, client.y);
        Rectangle bounds = new Rectangle(client.x, top, client.width, client.y + client.height - top);
        Image image = new Image(display, bounds.width, bounds.height);
        GC gc = new GC(display);
        try {
            gc.copyArea(image, bounds.x, bounds.y);
            File dir = new File(System.getProperty("screenshots.dir", "target/screenshots"));
            dir.mkdirs();
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] { image.getImageData() };
            loader.save(new File(dir, name).getPath(), SWT.IMAGE_PNG);
        } finally {
            gc.dispose();
            image.dispose();
        }
    }
}
