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
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.gallery.PicturesFolder;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;

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
 * <p>The four pictures tell one story in a Java project, {@code shop}, as a user's workspace has
 * it (the UI tests' own {@code e2e} project and {@code test.txt} are put away first, and every view
 * but the Project Explorer and the Outline is closed):
 * <ol>
 *   <li>{@code 01-picker.png}: Telescope's {@code find_files} over {@code Order.java}, drawn by
 *       the editor as Neovim lays it out, {@code inv} typed and {@code Invoice.java} chosen, its
 *       preview beside the list. Enter then opens it in an Eclipse editor, where the story goes on;
 *       {@code Order.java}'s editor is closed, so that its problem in the third picture has no
 *       column (an open file's has one), which would make {@code :clist}'s first line wrap.
 *       Telescope and its library plenary come at the commits pinned and vetted in
 *       {@code releng/test-plugins.properties} ({@code java releng/tools/plugins/TestPlugins.java
 *       fetch}, which the pictures tool runs first); without them the run fails, since the listing
 *       needs this picture.</li>
 *   <li>{@code 02-command-line.png}: Neovim's command line with {@code :%s/total/sum/g} typed, its
 *       matches highlighted.</li>
 *   <li>{@code 03-problems.png}: the substitution run and saved ({@code :w}); Eclipse's compiler
 *       finds the two other classes still calling {@code total()}; {@code :Eproblems} goes to the
 *       first, and {@code :clist} lists both in Neovim's own window, its quickfix list of
 *       Eclipse's errors: what no Vim emulation shows.</li>
 *   <li>{@code 04-content-assist.png}: at that first error, {@code ciw} on {@code total} and
 *       Ctrl+Space: Eclipse's content assist lists {@code Invoice}'s methods, {@code sum()} among
 *       them, with {@code INSERT} in the status bar. Eclipse's own features keep working in an
 *       insert Neovim began.</li>
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

    /** The keys that open the picker: a user's {@code <Leader>ff}, with Neovim's default leader. */
    private static final String FIND_FILES = "\\ff";

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
            // Its folder under the pictures' folder in /tmp, as the demo's: the status lines show the
            // files' paths, which must not lead into the home folder (gallery/PicturesFolder).
            shop = JavaProjects.create(PROJECT, PROJECT, PicturesFolder.root().resolve(PROJECT));
            JavaProjects.addClass(shop, PROJECT, "Order.java", ORDER);
            JavaProjects.addClass(shop, PROJECT, "Receipt.java", RECEIPT);
            JavaProjects.addClass(shop, PROJECT, "Invoice.java", INVOICE);
            waitUntil("the workspace build", TIMEOUT_MS, JavaProjects::buildDone);
            ITextEditor order = openJava(shop.getFile("src/shop/Order.java"));
            StyledText orderText = widgetOf(order);
            // No search left from an earlier run: the pictures' Neovim keeps its history under pictures.home.
            // Through the API, not typed: a typed line would stay on Neovim's ':' row in the picture.
            SessionProbe.lua(session, "vim.fn.setreg('/', '') vim.cmd.nohlsearch() return 0");
            mapFindFiles(shop);
            settle();

            // 1. A picker: Telescope's file list over Order.java, Invoice.java found by three letters
            // and shown in the preview. Enter opens it in an Eclipse editor, the Java editor.
            typeSlowly(orderText, FIND_FILES);
            waitUntil("Telescope's list, with Invoice.java", TIMEOUT_MS,
                    () -> telescopeWindows("Invoice.java") >= 1 && !pluginFloats(orderText).isEmpty());
            typeSlowly(orderText, "inv");
            waitUntil("Invoice.java chosen and in the preview", TIMEOUT_MS,
                    () -> selectedEntry().endsWith("Invoice.java") && previewShows("Adds up the prices"),
                    () -> "; selected " + selectedEntry());
            capture(window, "01-picker.png");
            UiTestSupport.type(orderText, "\r");
            waitUntil("Invoice.java in the Java editor", TIMEOUT_MS,
                    () -> page.getActiveEditor() instanceof ITextEditor e && e != order && "Invoice.java".equals(e.getTitle()));
            ITextEditor java = (ITextEditor) page.getActiveEditor();
            waitForAttach(java, "Invoice.java");
            StyledText text = widgetOf(java);
            waitUntil("the picker gone, Normal mode", TIMEOUT_MS,
                    () -> telescopeWindows(null) == 0 && "n".equals(session.getMode()));
            // Order.java's editor closed: the story goes on in Invoice.java alone. Left open, its
            // problem in picture 3 would have a column (an open file's has: util/QuickfixEntries),
            // and ":13 col 36" makes :clist's first line too long for one row.
            page.closeEditor(order, false);
            waitUntil("Order.java's editor closed", TIMEOUT_MS, () -> page.getEditorReferences().length == 1);

            // 2. The : command line, with a substitution typed but not yet run.
            openCommandLine(text, "%s/total/sum/g");
            settle(); // the line drawn
            capture(window, "02-command-line.png");

            // 3. Run it and save: Eclipse's build finds Order.java and Receipt.java still calling
            // total(); :Eproblems goes to the first error, opening its file, and :clist lists both
            // in Neovim's pager.
            pressEnter(text);
            waitUntil("the substitution", TIMEOUT_MS, () -> documentOf(java).get().contains("public double sum()"));
            expectCommandLine("");
            runCommandLine(text, "w");
            waitUntil("Invoice.java saved", TIMEOUT_MS, () -> !java.isDirty());
            IFile orderFile = shop.getFile("src/shop/Order.java");
            IFile receiptFile = shop.getFile("src/shop/Receipt.java");
            waitUntil("the build's errors in Order.java and Receipt.java", TIMEOUT_MS,
                    () -> JavaProjects.buildDone() && hasError(orderFile) && hasError(receiptFile));
            expectCommandLine("");
            runCommandLine(text, "Eproblems");
            waitUntil("the first error's editor", TIMEOUT_MS, () -> page.getActiveEditor() instanceof ITextEditor e && e != java);
            ITextEditor first = (ITextEditor) page.getActiveEditor();
            waitForAttach(first, first.getTitle());
            expectShown("(1 of 2)");
            StyledText firstText = widgetOf(first);
            runCommandLine(firstText, "clist");
            waitUntil("the :clist output", TIMEOUT_MS, () -> {
                settle();
                String shown = shownText();
                return shown.contains("Order.java") && shown.contains("Receipt.java");
            });
            capture(window, "03-problems.png");

            // 4. Content assist in insert mode, at that first error: ciw takes out the call to
            // total(), and Ctrl+Space lists what Invoice offers instead, sum() among them. Eclipse's
            // own feature, in an insert Neovim began.
            // Esc leaves :clist's output, as a user's goes (in ui2's pager, if it opened one). The
            // :%s pattern's matches ('hlsearch') go too, through the API, so that only the list
            // draws the eye.
            UiTestSupport.escape(firstText);
            waitUntil("normal mode after :clist", TIMEOUT_MS, () -> "n".equals(session.getMode()));
            // Through the API, not typed, as at the start; and the ':' row emptied, where the first
            // line of :clist's output stays after Esc, so that the picture shows the list only.
            SessionProbe.lua(session, "vim.cmd.nohlsearch() vim.fn.search('total(', 'cw') vim.cmd.echo('\"\"') return 0");
            settle();
            typeSlowly(firstText, "ciw");
            waitUntil("insert mode, Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
            ContentAssist.open(firstText);
            waitUntil("the content assist list", TIMEOUT_MS, () -> ContentAssist.proposals(window) != null);
            capture(window, "04-content-assist.png");
        } finally {
            // Also after a failed step: the base class's cleanup and any later test in this Eclipse
            // get the workbench as it was. The views and the font first, which cannot fail; the
            // project's delete and the call to Neovim can.
            page.closeAllEditors(false);
            page.resetPerspective();
            PictureFont.restore(fontsBefore);
            try {
                if (session != null) { // the mapping and any picker window go, for DemoRecordingTest after this
                    // gp: loadTelescope's own mapping (PinnedPlugins#loadTelescope).
                    SessionProbe.lua(session, PinnedPlugins.CLOSE_TELESCOPE + " pcall(vim.keymap.del, 'n', "
                            + luaText(FIND_FILES) + ") pcall(vim.keymap.del, 'n', 'gp') return 0");
                    waitUntil("no picker drawn", TIMEOUT_MS, () -> telescopeWindows(null) == 0);
                }
            } finally {
                if (shop != null) {
                    shop.delete(true, true, null);
                }
            }
        }
    }

    /**
     * Loads the pinned Telescope and plenary into the pictures' Neovim and maps {@link #FIND_FILES}
     * to its {@code find_files} in {@code shop}'s source folder, as a user's {@code <Leader>ff}
     * would: the list shows the three classes, not the project's metadata or the compiler's
     * {@code bin/}. Telescope picks the lister ({@code rg}, {@code fd} or {@code find}), from the
     * absolute folders of {@code PATH} only ({@link PinnedPlugins#FOR_PROGRAMS}). The preview is
     * kept at any width ({@code preview_cutoff}): Telescope hides it below 120 columns, and the
     * editor beside the two views has fewer; it gets a little more than half, for the code's lines.
     */
    private void mapFindFiles(IProject shop) {
        java.nio.file.Path telescope = PinnedPlugins.find("telescope").orElseThrow(() -> missing("telescope"));
        java.nio.file.Path plenary = PinnedPlugins.find("plenary").orElseThrow(() -> missing("plenary"));
        String src = PinnedPlugins.longString(shop.getFolder("src").getLocation().toFile().toPath());
        SessionProbe.lua(session, PinnedPlugins.loadTelescope(plenary, telescope) + " " + PinnedPlugins.FOR_PROGRAMS
                + " vim.keymap.set('n', " + luaText(FIND_FILES) + ", function()"
                + " require('telescope.builtin').find_files({ cwd = " + src + ","
                + " layout_config = { preview_cutoff = 1, preview_width = 0.55 } }) end) return 0");
    }

    /** Why the run stops without a plug-in: the listing needs the picker's picture, so it is not skipped. */
    private static AssertionError missing(String plugin) {
        return new AssertionError(plugin + " is not fetched at its pinned commit, and the first picture needs it: "
                + PinnedPlugins.HOW);
    }

    /** {@link PinnedPlugins#TELESCOPE_WINDOWS}' answer, with {@code word} or none ({@code null}). */
    private long telescopeWindows(String word) {
        Object n = SessionProbe.lua(session, "return (function(...)\n" + PinnedPlugins.TELESCOPE_WINDOWS + "\nend)("
                + (word == null ? "" : luaText(word)) + ")");
        return ((Number) n).longValue();
    }

    /** The open picker's selected entry (a path from the source folder, {@code shop/Invoice.java}), or {@code ""}. */
    private String selectedEntry() {
        Object value = SessionProbe.lua(session, """
                local ok, entry = pcall(function() return require('telescope.actions.state').get_selected_entry() end)
                return ok and entry and tostring(entry.value) or ''""");
        return String.valueOf(value);
    }

    /**
     * Whether a floating window shows {@code line}: Telescope's preview, since the file it previews
     * is in no editor yet. (Its buffer is the file's text with Java's highlighting, not one of
     * Telescope's own filetypes, so {@link PinnedPlugins#TELESCOPE_WINDOWS} does not count it.)
     */
    private boolean previewShows(String line) {
        return Boolean.TRUE.equals(SessionProbe.lua(session, "local text = " + luaText(line) + """

                for _, w in ipairs(vim.api.nvim_list_wins()) do
                  if vim.api.nvim_win_get_config(w).relative ~= '' then
                    for _, l in ipairs(vim.api.nvim_buf_get_lines(vim.api.nvim_win_get_buf(w), 0, -1, false)) do
                      if l:find(text, 1, true) then return true end
                    end
                  end
                end
                return false"""));
    }

    /**
     * {@code text}, a constant of this class's own, as a Lua long string: {@code [==[\ff]==]}.
     * Not in quotes, where Lua would read {@code \f} in {@link #FIND_FILES} as a form feed.
     */
    private static String luaText(String text) {
        return "[==[" + text + "]==]";
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
