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
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.gallery.GalleryCamera;

/**
 * The search highlighting pictures of the manual checks' gallery (card {@code sh-colors},
 * docs/design/SEARCH_HIGHLIGHTING.md): whether the match colors read well in a light and a dark
 * theme, behind plain text and behind the Java editor's syntax colors, is for a person to judge.
 * The painting itself is tested ({@code SearchHighlighterTest}, {@code SearchHighlightingEndToEndTest}).
 *
 * <p>Not a test, though named like one: it asserts only what it needs to reach each state. The
 * {@code gallery} profile runs it twice, in two test Eclipses: once as the others (the light
 * theme), and once started with Eclipse's dark theme ({@code -cssTheme
 * org.eclipse.e4.ui.css.theme.e4_dark}, the {@code gallery-dark} execution, system property
 * {@code gallery.theme=dark}). Starting dark gives what a user sees after choosing Dark and
 * restarting: the theme's CSS and its preference values (editor colors, syntax colors, the Search
 * Results color). Each run writes {@code search-<theme>-<picture>.png} into the gallery folder and
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
            new Picture("color", "With another Search Results color",
                    "The matches take the color set in Preferences > Editors > Text Editors > Annotations > "
                            + "Search Results (an amber here); the box follows it."));

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
    private final String theme = System.getProperty("gallery.theme", "light");

    @Test
    void takeTheSearchPictures() throws Exception {
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

            IPreferenceStore store = EditorsUI.getPreferenceStore();
            String key = SearchColors.preferenceKey();
            assertTrue(key != null, "no Search Results annotation: is org.eclipse.search in the test Eclipse?");
            RGB before = textWidgetBackground(javaWidget, JAVA.indexOf("foxes"));
            try {
                PreferenceConverter.setValue(store, key, theme.equals("dark") ? new RGB(110, 88, 24) : new RGB(255, 214, 102));
                waitUntil("the new Search Results color in the editor", TIMEOUT_MS,
                        () -> !before.equals(textWidgetBackground(javaWidget, JAVA.indexOf("foxes"))));
                take(javaWidget, "color", 16);
            } finally {
                store.setToDefault(key);
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
        UiTestSupport.requireKeyboardFocus(widgetOf(opened), TIMEOUT_MS);
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

    private static String file(String theme, String name) {
        return "search-" + theme + "-" + name + ".png";
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
                        ? "<img src=\"" + GalleryCamera.dataUri(out.resolve(file)) + "\" alt=\"" + escape(p.title()) + ", " + t + " theme\">"
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
                img { max-width: 100%; display: block; }
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
