// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;

import java.io.File;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Takes the screenshots for the Eclipse Marketplace listing and the README, so they can be taken
 * again after the UI changes instead of by hand. Not a test, though named like one (Surefire runs
 * only classes named *Test): it checks only what it needs to reach each state, and writes PNG files.
 *
 * <p>Runs only with the {@code screenshots} profile, which adds the Java development tools (for a
 * syntax-highlighted Java editor) and runs only the classes tagged {@code screenshots}: this one and
 * {@code DemoRecordingTest}, in one Eclipse:
 *
 * <pre>
 * xvfb-run -a -s "-screen 0 1600x1000x24" ./mvnw verify -Pscreenshots -Dnvim.path=/usr/bin/nvim
 * </pre>
 *
 * <p>The files land in {@code tests/...tests/target/screenshots/}, to upload to the Marketplace
 * listing from there; they are not committed (see docs/design/MARKETPLACE.md, "Images"). The {@code -s} option gives
 * Xvfb a screen larger than the window ({@link #WIDTH} by {@link #HEIGHT}); its default, 640x480 at
 * 8 bits per pixel, would cut the window off and lose the colors. Without a window manager the
 * window has no title bar, which suits a listing image.
 *
 * <p>The three pictures tell one story in a Java project, {@code shop}, as a user's workspace has
 * it (the UI tests' own {@code e2e} project and {@code test.txt} are put away first, and every view
 * but the Project Explorer and the Outline is closed):
 * <ol>
 *   <li>{@code 01-visual-mode.png}: visual line mode over a loop; Eclipse shows Neovim's selection
 *       and the mode in the status bar.</li>
 *   <li>{@code 02-command-line.png}: the {@code :} box with {@code :%s/total/sum/g} typed, its
 *       matches highlighted.</li>
 *   <li>{@code 03-problems.png}: the substitution run and saved ({@code :w}); Eclipse's compiler
 *       finds the two other classes still calling {@code total()}; {@code :Eproblems} goes to the
 *       first, and {@code :clist} lists both in the output popup, Neovim's quickfix list of
 *       Eclipse's errors: what no Vim emulation shows.</li>
 * </ol>
 *
 * <p>How a screenshot is taken: {@link WindowPicture}.
 */
@Tag("screenshots")
class ScreenshotCaptureTest extends EndToEndTestBase {

    /**
     * The window size: wide enough for the code, the status bar and {@code :clist}'s whole lines
     * beside the two views, small enough to stay legible when scaled; 16:10, as the Marketplace
     * shows pictures. It must fit the Xvfb screen the tool starts (1600x1000).
     */
    private static final int WIDTH = 1360;
    private static final int HEIGHT = 850;

    private static final String JAVA_EDITOR = "org.eclipse.jdt.ui.CompilationUnitEditor";

    /** The views the pictures keep: the workspace and the class's members, so that it reads as Eclipse. */
    private static final Set<String> VIEWS_KEPT = Set.of("org.eclipse.ui.navigator.ProjectExplorer",
            "org.eclipse.ui.views.ContentOutline");

    private static final String PROJECT = "shop";

    /** The code shown: short, realistic, with a word ({@code total}) that {@code :%s} can replace. */
    private static final String INVOICE = """
            package shop;

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

    /** The other class, which calls {@code total()}: the substitution in Invoice breaks it. */
    private static final String ORDER = """
            package shop;

            /** An order, and the line its receipt ends with. */
            public class Order {

                private final Invoice invoice;

                public Order(Invoice invoice) {
                    this.invoice = invoice;
                }

                public String receiptLine() {
                    return "Total: " + invoice.total();
                }
            }
            """;

    /** A second caller of {@code total()}, in another file, so that {@code :clist} lists two files. */
    private static final String RECEIPT = """
            package shop;

            /** The receipt printed for an invoice. */
            public class Receipt {

                public static String print(Invoice invoice) {
                    return invoice.isEmpty() ? "No items" : String.format("%.2f", invoice.total());
                }
            }
            """;

    @Test
    void captureTheListingScreenshots() throws Exception {
        Shell window = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        window.setBounds(0, 0, WIDTH, HEIGHT);
        IWorkbenchPage page = page();
        IProject shop = null;
        Map<String, FontData[]> fontsBefore = Map.of();
        try {
            // A user's workspace, not the UI tests': their test.txt closed and their project gone
            // (the base class's cleanup copes with both), and only the views the pictures keep.
            page.closeEditor(editor, false);
            project.delete(true, true, null);
            for (IViewReference view : page.getViewReferences()) {
                if (!VIEWS_KEPT.contains(view.getId())) {
                    page.hideView(view);
                }
            }
            fontsBefore = PictureFont.useInEditors(); // as in the demo, legible when scaled down
            shop = JavaProjects.create(PROJECT, PROJECT);
            JavaProjects.addClass(shop, PROJECT, "Order.java", ORDER);
            JavaProjects.addClass(shop, PROJECT, "Receipt.java", RECEIPT);
            IFile invoice = JavaProjects.addClass(shop, PROJECT, "Invoice.java", INVOICE);
            waitUntil("the workspace build", TIMEOUT_MS, JavaProjects::buildDone);
            ITextEditor java = openJava(invoice);
            StyledText text = widgetOf(java);
            // No search left from an earlier run: the pictures' Neovim keeps its history under pictures.home.
            session.commandAsync("let @/ = '' | nohlsearch");
            settle();

            // 1. Visual line mode over the loop: Neovim's selection shown by Eclipse, the mode in the status bar.
            typeSlowly(text, "16G");
            typeSlowly(text, "V2j");
            waitUntil("visual line mode", TIMEOUT_MS, () -> "V".equals(session.getMode()));
            capture(window, "01-visual-mode.png");
            typeSlowly(text, "\u001b"); // Esc

            // 2. The : command line, with a substitution typed but not yet run.
            Text box = openBox(text, "%s/total/sum/g");
            capture(window, "02-command-line.png");

            // 3. Run it and save: Eclipse's build finds Order.java and Receipt.java still calling
            // total(); :Eproblems goes to the first error, opening its file, and :clist lists both
            // in the output popup.
            Event enter = new Event();
            enter.keyCode = SWT.CR;
            enter.character = SWT.CR;
            enter.doit = true;
            requireOpen(box, text).notifyListeners(SWT.KeyDown, enter);
            waitUntil("the substitution", TIMEOUT_MS, () -> documentOf(java).get().contains("public double sum()"));
            // The box closes when Neovim reports its command line gone, a moment after the edit:
            // the next : would otherwise find this box still there and type into it.
            waitUntil("the : box closed", TIMEOUT_MS, () -> textChildOf(text) == null);
            runInBox(text, "w");
            waitUntil("Invoice.java saved", TIMEOUT_MS, () -> !java.isDirty());
            IFile orderFile = shop.getFile("src/shop/Order.java");
            IFile receiptFile = shop.getFile("src/shop/Receipt.java");
            waitUntil("the build's errors in Order.java and Receipt.java", TIMEOUT_MS,
                    () -> JavaProjects.buildDone() && hasError(orderFile) && hasError(receiptFile));
            waitUntil("the : box closed", TIMEOUT_MS, () -> textChildOf(text) == null);
            runInBox(text, "Eproblems");
            waitUntil("the first error's editor", TIMEOUT_MS, () -> page.getActiveEditor() instanceof ITextEditor e && e != java);
            ITextEditor first = (ITextEditor) page.getActiveEditor();
            waitForAttach(first, first.getTitle());
            waitUntil("the quickfix message", TIMEOUT_MS, () -> messageText(first).startsWith("(1 of 2)"),
                    () -> "; the message line shows " + messageText(first));
            StyledText firstText = widgetOf(first);
            runInBox(firstText, "clist");
            waitUntil("the :clist output", TIMEOUT_MS, () -> {
                StyledText popup = outputPopupIn(firstText);
                return popup != null && popup.getText().contains("Order.java") && popup.getText().contains("Receipt.java");
            });
            capture(window, "03-problems.png");
        } finally {
            // Also after a failed step: the base class's cleanup and any later test in this Eclipse
            // get the workbench as it was. The views and the font first, which cannot fail; the
            // project's delete can.
            page.closeAllEditors(false);
            page.resetPerspective();
            PictureFont.restore(fontsBefore);
            if (shop != null) {
                shop.delete(true, true, null);
            }
        }
    }

    /** The output popup in {@code parent}: a {@link StyledText} below the editor's own widget. */
    private static StyledText outputPopupIn(Composite parent) {
        for (Control child : parent.getChildren()) {
            if (child instanceof StyledText popup && !popup.isDisposed()) {
                return popup;
            }
            if (child instanceof Composite composite) {
                StyledText found = outputPopupIn(composite);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Whether Eclipse has an error marker on {@code file} (the Java builder's, here). */
    private static boolean hasError(IFile file) {
        try {
            return file.findMaxProblemSeverity(IMarker.PROBLEM, true, 0) == IMarker.SEVERITY_ERROR;
        } catch (CoreException e) {
            return false; // the file is being built or deleted: ask again
        }
    }

    /** Opens {@code file} in the Java editor and waits for Neovim. */
    private ITextEditor openJava(IFile file) throws Exception {
        IEditorPart opened = IDE.openEditor(page(), file, JAVA_EDITOR, true);
        if (!(opened instanceof ITextEditor java)) {
            throw new AssertionError("not a text editor (is JDT installed? use -Pscreenshots): " + opened);
        }
        waitForAttach(java, file.getName());
        return java;
    }

    /** Saves what {@code shell} shows on screen as {@code target/screenshots/<name>} ({@link WindowPicture}). */
    private void capture(Shell shell, String name) {
        settle();
        UiTestSupport.spin(300); // let the last paint reach the screen
        Image image = WindowPicture.take(shell);
        try {
            WindowPicture.savePng(image.getImageData(), new File(WindowPicture.folder(), name));
        } finally {
            image.dispose();
        }
    }
}
