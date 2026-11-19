// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.gallery.GalleryCamera;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageKind;

/**
 * The search highlighting pictures of the manual checks' gallery (card {@code sh-colors},
 * docs/design/SEARCH_HIGHLIGHTING.md): whether the match colors read well in a light and a dark
 * theme, behind plain text and behind the Java editor's syntax colors, is for a person to judge;
 * so is how Neovim's own highlights (a yank flash, a {@code :match}) look beside them, in Neovim's
 * colors. The painting itself is tested ({@code SearchHighlighterTest},
 * {@code SearchHighlightingEndToEndTest}, {@code NeovimHighlighterTest}, {@code NeovimHighlightsEndToEndTest}).
 *
 * <p>Not a test, though named like one: it asserts only what it needs to reach each state. The
 * {@code gallery} profile runs it twice, in two test Eclipses: once as the others (the light
 * theme), and once started with Eclipse's dark theme ({@code -cssTheme
 * org.eclipse.e4.ui.css.theme.e4_dark}, the {@code gallery-dark} execution, system property
 * {@code gallery.theme=dark}). Starting dark gives what a user sees after choosing Dark and
 * restarting: the theme's CSS and its preference values (editor colors, syntax colors), and the
 * dark value of the plug-in's own search match color, from its stylesheet for that theme. Each run writes {@code search-<theme>-<picture>.png} into the gallery folder and
 * {@code search.html}, which shows each picture in both themes side by side, as far as they are
 * there. The dark run refuses a light editor: a theme that did not apply must not pass for one.
 */
@Tag("gallery")
@Tag("galleryDark")
class SearchGalleryTest extends EndToEndTestBase {

    private static final String JAVA_EDITOR = "org.eclipse.jdt.ui.CompilationUnitEditor";

    /** A picture: its name in the file names, its title, and what the person checks in it. */
    record Picture(String name, String title, String lookFor) {
    }

    /** The start of the names of the pictures that need the which-key plug-in ({@link #whichKey}). */
    private static final String WHICH_KEY = "whichkey-";

    /** The picture that needs flash, one of the pinned plug-ins ({@link PinnedPlugins}). */
    private static final String LABELS = "labels";

    /** The pictures, in the page's order; each run takes all of them in its theme. */
    static final List<Picture> PICTURES = List.of(
            new Picture("text", "After /fox in a text file",
                    "Every fox has a background behind it, FOX in capitals too ('smartcase', a lower-case search); "
                            + "the text stays readable on it. The first fox, under the caret, has a box around it."),
            new Picture("typing", "While /fo is typed",
                    "Every fo is highlighted as it is typed ('incsearch'); the one the cursor jumped to has the box."),
            new Picture("java", "After /fox in the Java editor",
                    "The comment, the class name, the string and the variables keep their syntax colors on the "
                            + "background, and stay readable; the match under the caret, in the comment, has a box "
                            + "and its text reads as well as the others."),
            new Picture("neovim", "Neovim's own highlights in the Java editor",
                    "The flash of a yank over the class line (Neovim's IncSearch: a light yellow with its own dark text) "
                            + "and :match Error on count (a dark red with light text) read in both themes, over the "
                            + "fox matches where they meet. Their colors are Neovim's default colorscheme's, the same "
                            + "in both themes (docs/design/SEARCH_HIGHLIGHTING.md, \"The yank flash and window matches\")."),
            new Picture("cmdwin", "Neovim's command-line window (q:)",
                    "The window covers the editor's bottom rows, framed like the : box: the history, one command a "
                            + "line, in the editor's own background and text colors, with Neovim's syntax colors for "
                            + "Vim script on top, readable in both themes (Neovim's 'background' follows the theme). "
                            + "The cursor is a block on the last, empty line."),
            new Picture("float", "Neovim's floating windows without the focus",
                    "Two windows a plug-in could open without entering them, drawn over the text: a panel of key "
                            + "hints across the editor's bottom edge, and a note with a border and a title higher up. "
                            + "Both are in Neovim's colors for floating windows (NormalFloat, FloatBorder, FloatTitle), "
                            + "which follow the theme, and their text reads in both; the editor's text shows around "
                            + "them, and the caret stays in the editor."),
            new Picture("completion", "Neovim's completion list (Ctrl-N)",
                    "After f and Ctrl-N twice in the Java editor: the list of words hangs under the word being "
                            + "completed, its entries lined up with it, in Neovim's colors for the list (Pmenu), "
                            + "which follow the theme. The second entry is selected (PmenuSel) and stands out from "
                            + "the others in both themes; all entries read. The caret is after the completed word."),
            new Picture("view-messages", "A page editor after :messages",
                    "The messages page, in the editor's text font on the editor's background: three "
                            + "messages, the newest last with the cursor on it. The E486 error is in Neovim's error "
                            + "color (ErrorMsg), the other two in the default text color; all read in both themes."),
            new Picture("view-checkhealth", "A page editor after :checkhealth neovim4eclipse",
                    "The plug-in's own report as Neovim colors :checkhealth: the section headings stand out, OK "
                            + "in green, the WARNING (the gallery runs a Neovim program of its own, not the dedicated "
                            + "one) in Neovim's warning color; the lines read in both themes."),
            new Picture(LABELS, "A jump plug-in's labels (flash)",
                    "The real flash plug-in in the Java editor, after its jump key and f: each f that can be "
                            + "jumped to has its match highlighted and a label, one letter in its own cell right "
                            + "after the match, in flash's colors (FlashLabel, FlashMatch); the labels read in both "
                            + "themes and stand out from the search matches. The rest of the text is dimmed "
                            + "(FlashBackdrop) and still readable. The text itself is unchanged."),
            new Picture(WHICH_KEY + "z", "which-key's panel after z",
                    "The real which-key plug-in, in the Java editor, after z: its panel of the keys that can "
                            + "follow lies on the editor's bottom edge, from the left edge on, and reads in both "
                            + "themes. The five fold keys Eclipse does not allow say so (\"Delete all folds: not "
                            + "here, Eclipse defines the folds\"). Icons are whole, not cut at the next cell, "
                            + "where the icon font is installed; boxes where it is not."),
            new Picture(WHICH_KEY + "g", "which-key's panel after g",
                    "The same after g: the plug-in's own keys are listed by name (\"Eclipse: Open "
                            + "Declaration\", \"Eclipse: Quick Outline\") among Neovim's."),
            new Picture("color", "With another search match color",
                    "The matches take the color set in Preferences > General > Appearance > Colors and Fonts > "
                            + "Neovim for Eclipse > Search match (a green here); the box follows it."),
            new Picture("against", "An editor colored against the theme",
                    "The editor's own colors are the other way round from the theme (set on the editor itself, as "
                            + "a TextMate theme does: dark in the light run, light in the dark run). The matches take the "
                            + "amber for that editor, dark under light text and light under dark text, and read well; "
                            + "the box shows."));

    private static final String TEXT = """
            The quick brown fox jumps over the lazy dog.
            A second fox, and a third FOX in capitals.
            No match on this line.
            """;

    private static final String JAVA = """
            package p;

            /** Counts the foxes in a list. */
            public class Foxes {
                private final String name = "fox";

                public int count(java.util.List<String> animals) {
                    int foxes = 0;
                    for (String animal : animals) {
                        if (animal.equals(name)) {
                            foxes++;
                        }
                    }
                    return foxes;
                }
            }
            """;

    private final Path out = Path.of(System.getProperty("gallery.dir", "target/gallery"));
    /**
     * which-key (folke/which-key.nvim), loaded for the two pictures of a real plug-in's floating
     * windows: the pinned copy ({@link PinnedPlugins}), or empty when it is not fetched, which
     * leaves those pictures out. Its Lua runs in this test Eclipse's Neovim, which is why only
     * the pinned commit is taken (docs/MANUAL_CHECKS.md, card sh-colors).
     */
    private final java.util.Optional<Path> whichKey = PinnedPlugins.find("which-key");
    private final String theme = System.getProperty("gallery.theme", "light");

    @Test
    void takeTheSearchPictures() throws Exception {
        // No earlier run's pictures pass for this one's. The light run (the first) clears both themes,
        // the dark run only its own, as the light ones are this run's.
        GalleryCamera.clear(out, theme.equals("light") ? "search-.*" : "search-" + theme + "-.*",
                theme.equals("light") ? "search.html" : null);
        Files.createDirectories(out);
        ITextEditor text = open("search-sample.txt", TEXT, TEXT_EDITOR);
        StyledText textWidget = widgetOf(text);
        if (theme.equals("dark")) {
            RGB bg = textWidget.getBackground().getRGB();
            assertTrue(0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue < 128,
                    "the dark run's editor is light (" + bg + "): the dark theme did not apply (-cssTheme)");
        }
        try {
            session.inputAsync("gg0/fox<CR>"); // from the start: the first fox is the current match
            settle();
            waitForMatches(textWidget, 16);
            waitForCaret(textWidget, 16); // the box is drawn around the match under the caret
            take(textWidget, "text", 3);

            session.inputAsync("gg0/fo");
            settle();
            waitForMatches(textWidget, 16);
            // fo's matches, not the fox ones the search before left (shown until something is typed):
            // the x of the first fox, at 18, is no longer painted.
            waitUntil("fo's matches", TIMEOUT_MS, () -> textWidgetBackground(textWidget, 18) == null);
            waitForCaret(textWidget, 18); // while a search is typed, Neovim's cursor is after the match it jumped to
            take(textWidget, "typing", 3);
            session.inputAsync("<Esc>");
            settle();

            ITextEditor java = open("src/p/Foxes.java", JAVA, JAVA_EDITOR);
            StyledText javaWidget = widgetOf(java);
            session.inputAsync("gg0/fox<CR>");
            settle();
            waitForMatches(javaWidget, JAVA.indexOf("foxes"));
            waitForCaret(javaWidget, JAVA.indexOf("foxes")); // the search's first match, in the comment
            take(javaWidget, "java", 16);

            // Neovim's own highlights over the same view: a flash kept until the picture is taken (a
            // real one is gone after 150 ms), and a :match, both in Neovim's default colors.
            int classLine = JAVA.indexOf("public class");
            int count = JAVA.indexOf("count(");
            session.inputAsync(":lua vim.api.nvim_create_autocmd('TextYankPost', { group = vim.api.nvim_create_augroup('gallery_flash', {}),"
                    + " callback = function() vim.hl.on_yank({ timeout = 600000 }) end })<CR>");
            session.inputAsync(":match Error /count/<CR>4Gyy");
            settle();
            waitUntil("the flash on the class line", TIMEOUT_MS, () -> textWidgetBackground(javaWidget, classLine) != null);
            waitUntil("the match on count", TIMEOUT_MS, () -> textWidgetBackground(javaWidget, count) != null);
            try {
                take(javaWidget, "neovim", 16);
            } finally {
                session.inputAsync(":match none<CR>:lua vim.api.nvim_del_augroup_by_name('gallery_flash')"
                        + " vim.api.nvim_buf_clear_namespace(0, vim.api.nvim_create_namespace('nvim.hlyank'), 0, -1)<CR>");
                settle();
                waitUntil("the flash gone", TIMEOUT_MS, () -> textWidgetBackground(javaWidget, classLine) == null);
            }

            // Neovim's command-line window over the bottom of the same editor.
            session.inputAsync(":call histadd(':', 'echo \"from the gallery\"') | call histadd(':', 'set number')<CR>");
            session.inputAsync("q:");
            settle();
            waitUntil("the command-line window drawn", TIMEOUT_MS, () -> {
                settle();
                GridCanvas canvas = canvasOf(javaWidget);
                if (canvas == null || canvas.grid() == null) {
                    return false;
                }
                for (int row = 0; row < canvas.grid().height(); row++) {
                    if (canvas.grid().row(row).contains("from the gallery")) {
                        return true;
                    }
                }
                return false;
            });
            try {
                takeWhole(javaWidget, "cmdwin");
            } finally {
                session.closeCommandWindowAsync();
                waitUntil("the command-line window closed", TIMEOUT_MS, () -> !session.isCommandWindowOpen());
                settle();
            }

            // Neovim's floating windows without the focus over the same editor: a panel and a bordered note.
            SessionProbe.lua(session, """
                    local function open(lines, opts)
                      local buf = vim.api.nvim_create_buf(false, true)
                      vim.api.nvim_buf_set_lines(buf, 0, -1, false, lines)
                      return vim.api.nvim_open_win(buf, false, opts)
                    end
                    local win = vim.api.nvim_get_current_win()
                    vim.g.n4e_gallery_floats = {
                      open({ '', '   w  write          f  find file      b  buffers', '   q  quit           g  grep           h  help', '' },
                        { relative = 'win', win = win, row = vim.api.nvim_win_get_height(win) - 4, col = 0,
                          width = vim.api.nvim_win_get_width(win), height = 4, style = 'minimal', focusable = false }),
                      open({ 'A float with a border', 'and a title.' },
                        { relative = 'win', win = win, row = 1, col = 34, width = 24, height = 2, style = 'minimal',
                          border = 'rounded', title = ' Note ', focusable = false }),
                    }
                    """);
            try {
                waitUntil("both floats drawn", TIMEOUT_MS, () -> {
                    settle();
                    int drawn = 0;
                    for (org.eclipse.swt.widgets.Control child : javaWidget.getChildren()) {
                        if (child instanceof GridCanvas canvas && !canvas.isDisposed() && canvas.grid() != null) {
                            drawn++;
                        }
                    }
                    return drawn == 2;
                });
                takeWhole(javaWidget, "float");
            } finally {
                SessionProbe.lua(session, "for _, w in ipairs(vim.g.n4e_gallery_floats or {}) do pcall(vim.api.nvim_win_close, w, true) end"
                        + " vim.g.n4e_gallery_floats = nil");
                waitUntil("the floats gone", TIMEOUT_MS, () -> {
                    settle();
                    for (org.eclipse.swt.widgets.Control child : javaWidget.getChildren()) {
                        if (child instanceof GridCanvas) {
                            return false;
                        }
                    }
                    return true;
                });
            }

            // Neovim's completion list in the same editor: f typed on a new line, then Ctrl-N twice.
            // Words of this file only: a match from another file comes with that file's full path,
            // which names the folder the gallery runs in.
            SessionProbe.lua(session, "vim.bo.complete = '.'");
            session.inputAsync("8Go");
            settle();
            waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
            UiTestSupport.type(javaWidget, "f");
            UiTestSupport.ctrlKey(javaWidget, 'n');
            UiTestSupport.ctrlKey(javaWidget, 'n');
            try {
                waitUntil("the completion list with its second entry selected", TIMEOUT_MS, () -> {
                    settle();
                    for (org.eclipse.swt.widgets.Control frame : javaWidget.getChildren()) { // the list's canvas is in a frame
                        if (frame instanceof org.eclipse.swt.widgets.Composite composite && frame.getVisible()
                                && composite.getChildren().length == 1
                                && composite.getChildren()[0] instanceof GridCanvas canvas && canvas.grid() != null && canvas.grid().window() == -1 && canvas.grid().height() > 1
                                && canvas.grid().hlAt(1, 1) == io.github.jm6cnddxbwftaks.neovim4eclipse.util.CompletionMenu.PMENU_SEL) {
                            return true;
                        }
                    }
                    return false;
                });
                take(javaWidget, "completion", 16);
            } finally {
                UiTestSupport.ctrlKey(javaWidget, 'e'); // takes the completion back
                UiTestSupport.escape(javaWidget);
                waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
                settle();
                SessionProbe.lua(session, "vim.cmd('setlocal complete<')");
                session.inputAsync("u"); // the new line with its f: the later pictures show the file as it was
                waitUntil("the Java file as before", TIMEOUT_MS, () -> {
                    settle();
                    return JAVA.equals(java.getDocumentProvider().getDocument(java.getEditorInput()).get());
                });
            }

            takeTheView(java);

            takeLabels(java, javaWidget);

            // As a user sets it in Colors and Fonts: the theme's color registry takes the new value.
            ColorRegistry colors = PlatformUI.getWorkbench().getThemeManager().getCurrentTheme().getColorRegistry();
            RGB own = colors.getRGB(SearchColors.COLOR_ID);
            RGB before = textWidgetBackground(javaWidget, JAVA.indexOf("foxes"));
            try {
                colors.put(SearchColors.COLOR_ID, theme.equals("dark") ? new RGB(40, 92, 70) : new RGB(170, 230, 200));
                waitUntil("the new search match color in the editor", TIMEOUT_MS,
                        () -> !before.equals(textWidgetBackground(javaWidget, JAVA.indexOf("foxes"))));
                take(javaWidget, "color", 16);
            } finally {
                colors.put(SearchColors.COLOR_ID, own);
            }

            // An editor whose colors are the other way round from the theme, set on the widget itself.
            page().activate(text);
            settle();
            waitForMatches(textWidget, 16); // the matches are there to compare against
            RGB textBefore = textWidgetBackground(textWidget, 16);
            Color oldBackground = textWidget.getBackground();
            Color oldForeground = textWidget.getForeground();
            boolean dark = theme.equals("dark");
            // Eclipse's current-line band keeps the theme's color, which a TextMate theme would set too:
            // off here, so the picture shows the matches on the editor's own colors only.
            IPreferenceStore editors = EditorsUI.getPreferenceStore();
            String currentLine = AbstractDecoratedTextEditorPreferenceConstants.EDITOR_CURRENT_LINE;
            boolean currentLineWas = editors.getBoolean(currentLine);
            try {
                editors.setValue(currentLine, false);
                textWidget.setBackground(dark ? new Color(250, 250, 250)
                        : new Color(30, 30, 30));
                textWidget.setForeground(dark ? new Color(20, 20, 20)
                        : new Color(220, 220, 220));
                waitUntil("the other amber for the editor's own colors", TIMEOUT_MS,
                        () -> !textBefore.equals(textWidgetBackground(textWidget, 16)));
                session.inputAsync("gg0/fox<CR>"); // back on the first fox, for its box
                settle();
                waitForCaret(textWidget, 16);
                take(textWidget, "against", 3);
            } finally {
                textWidget.setBackground(oldBackground);
                textWidget.setForeground(oldForeground);
                editors.setValue(currentLine, currentLineWas);
            }

            // Last, since which-key stays loaded in this Neovim from here on: the real plug-in's panel.
            if (whichKey.isPresent()) {
                takeWhichKey(java, javaWidget);
            }
        } finally {
            session.inputAsync("<Esc>:nohlsearch<CR>");
            settle();
            writePage();
        }
    }

    /**
     * Closes every editor, its two included, then waits for Eclipse's jobs, before the base deletes
     * project e2e: a Java editor left open on a deleted project keeps JDT's jobs failing (see
     * JavaEditorEndToEndTest's cleanup, which this follows). A job that never ends does not fail
     * the gallery: the wait gives up at the limit.
     */
    /**
     * The pictures need no keyboard focus (the keys go to Neovim with {@code session.inputAsync},
     * and the windows draw themselves), so the run never takes the front from the developer's
     * program for it.
     */
    @Override
    protected boolean takesTheFront() {
        return false;
    }

    @AfterEach
    void closeEveryEditorAndLetTheJobsFinish() {
        page().closeAllEditors(false);
        editor = null; // closed too: the base must not close it again
        UiTestSupport.waitUntilOrGiveUp(TIMEOUT_MS, Job.getJobManager()::isIdle);
    }

    /** Creates {@code name} in the project, opens it in {@code editorId} and waits for Neovim to attach. */
    private ITextEditor open(String name, String content, String editorId) throws Exception {
        if (name.contains("/")) {
            project.getFolder(name.substring(0, name.indexOf('/'))).create(true, true, null);
            project.getFolder(name.substring(0, name.lastIndexOf('/'))).create(true, true, null);
        }
        IFile file = createFile(name, content);
        IEditorPart part = IDE.openEditor(page(), file, editorId, true);
        assertTrue(part instanceof ITextEditor, editorId + " opened " + part);
        ITextEditor opened = (ITextEditor) part;
        waitForAttach(opened, name);
        UiTestSupport.focusWithoutTakingTheFront(widgetOf(opened), TIMEOUT_MS);
        settle();
        waitForNeovimIn(file);
        return opened;
    }

    /** Waits until the widget paints a background at {@code offset}: Neovim's matches have arrived. */
    private static void waitForMatches(StyledText target, int offset) {
        waitUntil("a search highlight at offset " + offset, TIMEOUT_MS, () -> textWidgetBackground(target, offset) != null);
    }

    /** Waits until the caret is at {@code offset}: Neovim's cursor reached the match. */
    private static void waitForCaret(StyledText target, int offset) {
        waitUntil("the caret at offset " + offset, TIMEOUT_MS, () -> target.getCaretOffset() == offset,
                () -> "; it is at " + target.getCaretOffset());
    }

    private static RGB textWidgetBackground(StyledText target, int offset) {
        StyleRange range = target.getStyleRangeAtOffset(offset);
        return range == null || range.background == null ? null : range.background.getRGB();
    }

    /** Pictures the widget's first {@code lines} lines, with a margin, no wider than a page column. */
    private void take(StyledText target, String name, int lines) {
        target.setTopIndex(0);
        GalleryCamera.drain(target.getDisplay());
        int height = Math.min(target.getClientArea().height, target.getLinePixel(lines) + target.getLineHeight() / 2);
        int width = Math.min(target.getClientArea().width, 720);
        GalleryCamera.save(target, new Rectangle(0, 0, width, height), out.resolve(file(theme, name)), name + " (" + theme + ")");
    }

    /** Like {@link #take}, the editor's whole height: the command-line window is at its bottom. */
    private void takeWhole(StyledText target, String name) {
        GalleryCamera.drain(target.getDisplay());
        Rectangle area = target.getClientArea();
        GalleryCamera.save(target, new Rectangle(0, 0, Math.min(area.width, 720), area.height), out.resolve(file(theme, name)),
                name + " (" + theme + ")");
    }

    /**
     * A page editor after {@code :messages} (three messages, an error among them) and after
     * {@code :checkhealth neovim4eclipse}, as the view's canvas draws them; then the view is closed
     * and the Java editor is active again, for the pictures after these.
     */
    private void takeTheView(ITextEditor java) {
        SessionProbe.lua(session, "vim.cmd.messages('clear') vim.cmd.echomsg(\"'3 lines yanked'\")"
                + " vim.api.nvim_echo({ { 'E486: Pattern not found: wolf' } }, true, { err = true })"
                + " vim.cmd.echomsg(\"'\\\"Foxes.java\\\" 16L, 371B written'\") return 0");
        session.inputAsync(":messages<CR>");
        try {
            takeView(viewShowing("E486: Pattern not found: wolf"), "view-messages");
            page().activate(java);
            settle();
            session.inputAsync(":checkhealth neovim4eclipse<CR>");
            takeView(viewShowing("Neovim for Eclipse "), "view-checkhealth");
        } finally {
            for (PageKind kind : PageKind.values()) {
                IEditorPart shown = page().findEditor(new NeovimPageInput(kind));
                if (shown != null) {
                    page().closeEditor(shown, false);
                }
            }
            page().activate(java);
            settle();
        }
    }

    /** Waits for a page editor to show a row holding {@code text}, and returns it. */
    private NeovimPageEditor viewShowing(String text) {
        NeovimPageEditor[] found = new NeovimPageEditor[1];
        waitUntil("a page editor showing " + text, TIMEOUT_MS, () -> {
            settle();
            for (PageKind kind : PageKind.values()) {
                if (page().findEditor(new NeovimPageInput(kind)) instanceof NeovimPageEditor shown
                        && shown.canvasForTests().grid() != null) {
                    GridSnapshot grid = shown.canvasForTests().grid();
                    for (int r = 0; r < grid.height(); r++) {
                        if (grid.row(r).contains(text)) {
                            found[0] = shown;
                            return true;
                        }
                    }
                }
            }
            return false;
        });
        return found[0];
    }

    /** Pictures a page editor's canvas: at most a page column wide, its first 14 rows. */
    private void takeView(NeovimPageEditor view, String name) {
        GridCanvas canvas = view.canvasForTests();
        GalleryCamera.drain(canvas.getDisplay());
        Rectangle area = canvas.getClientArea();
        int height = Math.min(area.height, 14 * canvas.cellHeight());
        GalleryCamera.save(canvas, new Rectangle(0, 0, Math.min(area.width, 720), height), out.resolve(file(theme, name)),
                name + " (" + theme + ")");
    }

    /** The command-line window's canvas below the editor's widget, or {@code null} when it is closed. */
    private static GridCanvas canvasOf(org.eclipse.swt.widgets.Composite parent) {
        for (org.eclipse.swt.widgets.Control child : parent.getChildren()) {
            if (child instanceof GridCanvas canvas && !canvas.isDisposed()) {
                return canvas;
            }
            if (child instanceof org.eclipse.swt.widgets.Composite composite) {
                GridCanvas found = canvasOf(composite);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String file(String theme, String name) {
        return "search-" + theme + "-" + name + ".png";
    }

    /**
     * Loads flash, if it is fetched ({@link PinnedPlugins}), starts its jump in the Java editor and
     * takes the labels it shows for {@code f}. Without flash the picture is left out and the page
     * says how to get it.
     */
    private void takeLabels(ITextEditor java, StyledText javaWidget) {
        java.util.Optional<Path> flash = PinnedPlugins.find("flash");
        if (flash.isEmpty()) {
            return;
        }
        page().activate(java);
        settle();
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        SessionProbe.lua(session, PinnedPlugins.loadFlash(flash.get()));
        session.inputAsync("gg0:lua vim.schedule(function() require('flash').jump() end)<CR>");
        session.inputAsync("f");
        try {
            waitUntil("flash's labels", TIMEOUT_MS, () -> {
                settle();
                return controller != null && controller.jumpLabelsForTests().size() > 2;
            });
            settle();
            take(javaWidget, LABELS, 16);
        } finally {
            session.inputAsync("<Esc>");
            waitUntil("flash's labels gone", TIMEOUT_MS, () -> {
                settle();
                return controller == null || controller.jumpLabelsForTests().isEmpty();
            });
        }
    }

    /**
     * Loads which-key from {@link #whichKey} and takes its panel after {@code z} and after
     * {@code g} in the Java editor. Its two floating windows (the panel and a one-row line) are
     * drawn by {@link FloatingWindows}; {@code Esc} closes them.
     */
    private void takeWhichKey(ITextEditor java, StyledText javaWidget) {
        Path plugin = whichKey.orElseThrow();
        page().activate(java);
        settle();
        // The path as a Lua long string, in which no escape means anything; only its closing mark must not occur.
        assertTrue(!plugin.toAbsolutePath().toString().contains("]==]"), "a path with ]==] in it cannot be passed on");
        SessionProbe.lua(session, "vim.opt.rtp:append([==[" + plugin.toAbsolutePath() + "]==]) require('which-key').setup({})");
        settle();
        for (String key : List.of("z", "g")) {
            // which-key maps its trigger keys from a timer some time after setup() (its triggers.lua).
            // A key sent before that is Neovim's own pending z or g, and no panel opens.
            waitUntil("which-key's trigger for " + key, TIMEOUT_MS, () -> Boolean.TRUE.equals(SessionProbe.lua(session,
                    "return (vim.fn.maparg('" + key + "', 'n', false, true).desc or ''):find('which-key-trigger', 1, true) ~= nil")));
            session.inputAsync(key);
            try {
                waitUntil("which-key's two windows after " + key, TIMEOUT_MS, () -> floatsDrawn(javaWidget) == 2);
                settle(); // which-key may fill its panel in a later redraw than the one that opens it
                takeWhole(javaWidget, WHICH_KEY + key);
            } finally {
                session.inputAsync("<Esc>");
                waitUntil("which-key's windows gone", TIMEOUT_MS, () -> floatsDrawn(javaWidget) == 0);
                settle();
            }
        }
    }

    /** How many floating windows are drawn over {@code widget} with their cells there. */
    private static int floatsDrawn(StyledText widget) {
        int drawn = 0;
        for (org.eclipse.swt.widgets.Control child : widget.getChildren()) {
            if (child instanceof GridCanvas canvas && !canvas.isDisposed() && canvas.grid() != null) {
                drawn++;
            }
        }
        return drawn;
    }

    /** Writes {@code search.html}: each picture in the themes taken so far, side by side. */
    private void writePage() throws IOException {
        StringBuilder body = new StringBuilder();
        for (Picture p : PICTURES) {
            body.append("<h2>").append(escape(p.title())).append("</h2>\n<p>").append(escape(p.lookFor())).append("</p>\n<div class=\"pair\">");
            for (String t : List.of("light", "dark")) {
                String file = file(t, p.name());
                body.append("<figure><figcaption>").append(t).append("</figcaption>");
                body.append(Files.exists(out.resolve(file))
                        ? GalleryCamera.img(out.resolve(file), escape(p.title()) + ", " + t + " theme")
                        : p.name().equals(LABELS) && PinnedPlugins.find("flash").isEmpty()
                                ? "<p class=\"missing\">not taken: flash is not fetched (" + PinnedPlugins.HOW + ")</p>"
                        : p.name().startsWith(WHICH_KEY) && whichKey.isEmpty()
                                ? "<p class=\"missing\">not taken: which-key is not fetched (" + PinnedPlugins.HOW + "; "
                                        + "docs/MANUAL_CHECKS.md, card sh-colors)</p>"
                                : "<p class=\"missing\">not taken (the " + t + " run did not run or failed)</p>");
                body.append("</figure>");
            }
            body.append("</div>\n");
        }
        String page = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Search Highlighting Gallery</title>
                <style>
                :root { --bg: #f7f7f4; --fg: #1d1f1c; --muted: #5d6259; --line: #d9dbd3; --card: #ffffff; }
                @media (prefers-color-scheme: dark) { :root { --bg: #171916; --fg: #e8e9e4; --muted: #a3a89d;
                  --line: #33362f; --card: #20231f; color-scheme: dark } }
                body { background: var(--bg); color: var(--fg); font: 15px/1.5 system-ui, sans-serif; margin: 0;
                  padding-block: 24px; padding-left: max(16px, env(safe-area-inset-left, 0px));
                  padding-right: max(16px, env(safe-area-inset-right, 0px)); }
                main { max-width: 1500px; margin: 0 auto; }
                h2 { font-size: 1rem; margin: 28px 0 4px; border-top: 1px solid var(--line); padding-top: 14px; }
                p { max-width: 70ch; margin: 0 0 10px; color: var(--muted); }
                .pair { display: flex; flex-wrap: wrap; gap: 16px; }
                figure { margin: 0; background: var(--card); border: 1px solid var(--line); border-radius: 6px;
                  padding: 10px; min-width: 0; }
                figcaption { font-weight: 600; margin-bottom: 6px; }
                img { max-width: 100%; height: auto; display: block; }
                .missing { font-style: italic; }
                </style></head><body><main>
                <h1>Search highlighting in a light and a dark theme</h1>
                <p>Taken by SearchGalleryTest (-Pgallery), manual check sh-colors. Back to the <a href="index.html">gallery</a>.</p>
                """ + body + "</main></body></html>\n";
        Files.writeString(out.resolve("search.html"), page, StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
