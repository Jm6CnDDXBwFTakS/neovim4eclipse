// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Records the README's demo: a Java file edited through Neovim (ciw, a macro, {@code :%s},
 * {@code gd}, content assist in insert mode, {@code :e!}), one picture after every key, so the
 * demo can be made again after the UI changes instead of by hand. Not a test, though named like
 * one: it checks only what it needs to reach each state.
 *
 * <p>Runs with the {@code screenshots} profile, beside {@link ScreenshotCaptureTest}. It writes
 * {@code target/screenshots/demo/frame-0001.png} and on, and {@code frames.tsv}: one line per
 * picture with its file and how long it stays on screen, in milliseconds. The Marketplace tool
 * ({@code java releng/tools/marketplace/Marketplace.java pictures}) joins them into
 * {@code demo.gif}, writing only the part of each picture that changed.
 *
 * <p>Every picture carries a caption: the keys typed so far in the current step, drawn in a band
 * over the editor, the way a key-caster shows them in a screen recording. Enter shows as ⏎. The
 * demo ends where it began ({@code :e!} reverts the file, {@code gg0} puts the cursor back), so the
 * GIF loops without a jump.
 */
@Tag("screenshots")
class DemoRecordingTest extends EndToEndTestBase {

    /** The window size: the README shows the GIF about this wide, so nothing is scaled. */
    private static final int WIDTH = 1000;
    private static final int HEIGHT = 680;

    /** How long a picture stays after a key, in milliseconds: a brisk but readable typing speed. */
    private static final int KEY_MS = 130;
    /** How long the result of a step stays before the next one starts. */
    private static final int STEP_MS = 1400;

    private static final String JAVA_EDITOR = "org.eclipse.jdt.ui.CompilationUnitEditor";
    /**
     * JDT's preference for opening content assist by itself after a {@code .}: off while the demo
     * runs, so the list opens only for Ctrl+Space, at a known moment ({@code PreferenceConstants.
     * CODEASSIST_AUTOACTIVATION} in {@code org.eclipse.jdt.ui}, named here by its key, as the test
     * fragment has no JDT bundle at compile time).
     */
    private static final String JDT_UI = "org.eclipse.jdt.ui";
    private static final String AUTO_ACTIVATION = "content_assist_autoactivation";

    private static final String SOURCE = """
            package demo;

            import java.util.List;

            public class Greeter {

                String alpha;
                String beta;
                String gamma;

                private final String greeting = "Hello";

                public String greet(String name) {
                    return greeting + ", " + name + "!";
                }

                public void greetAll(List<String> names) {
                    for (String name : names) {
                        System.out.println(greet(name));
                    }
                }
            }
            """;

    private Shell window;
    private StyledText text;
    private org.eclipse.jface.text.IDocument doc;
    private final List<String> frames = new ArrayList<>();
    /** The current step's keys as the caption shows them. */
    private final StringBuilder caption = new StringBuilder();

    @Test
    void recordTheReadmeDemo() throws Exception {
        IEclipsePreferences jdt = InstanceScope.INSTANCE.getNode(JDT_UI);
        String autoActivation = jdt.get(AUTO_ACTIVATION, null);
        jdt.putBoolean(AUTO_ACTIVATION, false);
        try {
            record();
        } finally {
            if (autoActivation == null) {
                jdt.remove(AUTO_ACTIVATION);
            } else {
                jdt.put(AUTO_ACTIVATION, autoActivation);
            }
        }
    }

    private void record() throws Exception {
        window = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        window.setBounds(0, 0, WIDTH, HEIGHT);
        IProject demo = javaProject("demo");
        IWorkbenchPage page = page();
        IEditorPart opened = null;
        try {
            IFile file = demo.getFile("src/demo/Greeter.java");
            file.create(new ByteArrayInputStream(SOURCE.getBytes(StandardCharsets.UTF_8)), true, null);
            opened = IDE.openEditor(page, file, JAVA_EDITOR, true);
            if (!(opened instanceof ITextEditor java)) {
                throw new AssertionError("not a text editor (is JDT installed? use -Pscreenshots): " + opened);
            }
            waitForAttach(java, "Greeter.java");
            // Only the editor, as large as the window allows: the demo is about the text.
            page.setPartState(page.getReference(java), IWorkbenchPage.STATE_MAXIMIZED);
            text = widgetOf(java);
            doc = documentOf(java);
            settle();
            play(new File(WindowPicture.folder(), "demo"));
        } finally {
            // Also after a failed step: ScreenshotCaptureTest runs next in this Eclipse, and its
            // pictures need the workbench as it was.
            if (opened != null) {
                page.setPartState(page.getReference(opened), IWorkbenchPage.STATE_RESTORED);
                page.closeEditor(opened, false);
            }
            demo.delete(true, true, null);
        }
    }

    /** The demo, a picture after every key, into {@code folder}. */
    private void play(File folder) throws Exception {
        deleteOldFrames(folder);
        picture(folder, STEP_MS); // the start, which is also the end

        // ciw: change the word in the quotes.
        search(folder, "Hello");
        keys(folder, "ciwHowdy");
        escape(folder);
        endStep(folder);

        // A macro: put "private" before a field, then play it on the next two.
        search(folder, "alpha");
        keys(folder, "qqIprivate ");
        escape(folder);
        keys(folder, "jq2@q");
        waitUntil("the macro on all three fields", TIMEOUT_MS, () -> doc.get().split("private String", -1).length == 4);
        endStep(folder);

        // :%s renames the field everywhere.
        command(folder, ':', "%s/greeting/salutation/g");
        waitUntil("the substitution", TIMEOUT_MS, () -> doc.get().contains("return salutation"));
        endStep(folder);

        // gd: from the call to its declaration.
        search(folder, "greet(name");
        keys(folder, "gd");
        endStep(folder);

        // Content assist in insert mode: Eclipse completes what Neovim is typing.
        search(folder, "for (");
        keys(folder, "Oint count = names.si");
        contentAssist(folder);
        keys(folder, ";");
        escape(folder);
        endStep(folder);

        // :e! reverts the file, and gg0 puts the cursor where the demo began.
        keys(folder, "gg0");
        command(folder, ':', "e!");
        waitUntil("the file reverted", TIMEOUT_MS, () -> doc.get().equals(SOURCE));
        // The last search's matches stay highlighted ('hlsearch'); the first picture has none, so
        // they go quietly, through the API, for the loop to close without a jump.
        session.commandAsync("nohlsearch");
        caption.setLength(0);
        picture(folder, STEP_MS); // the same as the first picture

        Files.write(new File(folder, "frames.tsv").toPath(), frames, StandardCharsets.UTF_8);
    }

    /**
     * A Java project, as the demo needs for content assist: Java's completion asks the project's
     * build path, and in a plain project it shows a dialog instead. Made with the resources API
     * only (the Java nature by its id, the build path as the {@code .classpath} file JDT reads), so
     * the test fragment needs no JDT bundle at compile time; the {@code screenshots} profile adds
     * JDT at run time. The JRE container is the Java the test Eclipse runs on.
     */
    private static IProject javaProject(String name) throws Exception {
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        if (p.exists()) {
            p.delete(true, true, null);
        }
        p.create(null);
        p.open(null);
        IFolder src = p.getFolder("src");
        src.create(true, true, null);
        src.getFolder("demo").create(true, true, null);
        p.getFile(".classpath").create(new ByteArrayInputStream("""
                <?xml version="1.0" encoding="UTF-8"?>
                <classpath>
                    <classpathentry kind="src" path="src"/>
                    <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
                    <classpathentry kind="output" path="bin"/>
                </classpath>
                """.getBytes(StandardCharsets.UTF_8)), true, null);
        IProjectDescription description = p.getDescription();
        description.setNatureIds(new String[] {"org.eclipse.jdt.core.javanature"});
        p.setDescription(description, null);
        return p;
    }

    /** Types {@code keys} into the editor one at a time, a picture after each. */
    private void keys(File folder, String keys) {
        for (char key : keys.toCharArray()) {
            UiTestSupport.type(text, key);
            caption.append(key);
            picture(folder, KEY_MS);
        }
    }

    private void escape(File folder) {
        UiTestSupport.escape(text);
        caption.append(" Esc ");
        picture(folder, KEY_MS);
    }

    /** A search, {@code /} then the pattern then Enter, typed into the box it opens. */
    private void search(File folder, String pattern) {
        command(folder, '/', pattern);
    }

    /**
     * {@code :} or {@code /} and then {@code line}, a key at a time, into the box Neovim's command
     * line opens, then Enter. Each key goes to Neovim through the box ({@code CommandLineBox});
     * the picture waits until Neovim's report shows it.
     */
    private void command(File folder, char first, String line) {
        UiTestSupport.type(text, first);
        waitUntil("the " + first + " box to open", TIMEOUT_MS, () -> textChildOf(text) != null);
        Text box = textChildOf(text);
        caption.append(first);
        picture(folder, KEY_MS);
        StringBuilder typed = new StringBuilder().append(first);
        for (char key : line.toCharArray()) {
            requireOpen(box, text).insert(String.valueOf(key));
            typed.append(key);
            String expected = typed.toString();
            waitUntil("the box to show " + expected, TIMEOUT_MS, () -> requireOpen(box, text).getText().equals(expected));
            caption.append(key);
            picture(folder, KEY_MS);
        }
        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        enter.doit = true;
        requireOpen(box, text).notifyListeners(SWT.KeyDown, enter);
        waitUntil("the box to close", TIMEOUT_MS, () -> box.isDisposed() || !box.isVisible());
        caption.append('⏎');
        picture(folder, KEY_MS);
    }

    /**
     * Ctrl+Space, as a user presses it in insert mode: Eclipse's content assist opens its list,
     * filtered by what is typed ("si" leaves {@code size()}), and Enter takes the first proposal.
     * The key event runs the workbench's key bindings (see {@link UiTestSupport#m1Key}).
     */
    private void contentAssist(File folder) {
        Event space = new Event();
        space.keyCode = ' ';
        space.character = ' ';
        space.stateMask = SWT.CTRL;
        space.doit = true;
        text.notifyListeners(SWT.KeyDown, space);
        caption.append(" Ctrl+Space ");
        waitUntil("the content assist list", TIMEOUT_MS, () -> proposalList() != null);
        picture(folder, STEP_MS);
        String before = doc.get();
        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        enter.doit = true;
        text.notifyListeners(SWT.KeyDown, enter);
        waitUntil("the proposal inserted", TIMEOUT_MS, () -> !doc.get().equals(before) && proposalList() == null);
        caption.append('⏎');
        picture(folder, KEY_MS);
    }

    /** The table of the open content assist list, if one is open. */
    private Table proposalList() {
        for (Shell shell : Display.getCurrent().getShells()) {
            if (shell != window && shell.isVisible()) {
                Table table = tableIn(shell);
                if (table != null && table.getItemCount() > 0) {
                    return table;
                }
            }
        }
        return null;
    }

    private static Table tableIn(org.eclipse.swt.widgets.Composite parent) {
        for (org.eclipse.swt.widgets.Control child : parent.getChildren()) {
            if (child instanceof Table table) {
                return table;
            }
            if (child instanceof org.eclipse.swt.widgets.Composite composite) {
                Table found = tableIn(composite);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Lets the step's result stay on screen, then clears the caption for the next one. */
    private void endStep(File folder) {
        picture(folder, STEP_MS);
        caption.setLength(0);
    }

    /**
     * Saves what the window shows now, with the caption drawn on it, as the next frame, and notes
     * how long it stays. Waits for Neovim and the editor first, so each picture shows the key's
     * effect.
     */
    private void picture(File folder, int millis) {
        settle();
        UiTestSupport.spin(80); // let the last paint reach the screen
        Image image = WindowPicture.take(window);
        try {
            if (caption.length() > 0) {
                drawCaption(image, caption.toString());
            }
            String name = String.format("frame-%04d.png", frames.size() + 1);
            WindowPicture.savePng(image.getImageData(), new File(folder, name));
            frames.add(name + "\t" + millis);
        } finally {
            image.dispose();
        }
    }

    /** The longest caption shown: older keys of a long step scroll off to the left. */
    private static final int CAPTION_CHARS = 30;

    /**
     * Draws {@code keys} centered in a dark band over the right part of the editor's first lines,
     * below the toolbar and the tabs, where the short lines of the demo file leave room.
     */
    private static void drawCaption(Image image, String keys) {
        String shown = keys.length() > CAPTION_CHARS ? "…" + keys.substring(keys.length() - CAPTION_CHARS + 1) : keys;
        Display display = Display.getCurrent();
        GC gc = new GC(image);
        Font font = new Font(display, new FontData("Monospace", 20, SWT.BOLD));
        Color band = new Color(display, 0x55, 0x55, 0x55);
        Color white = new Color(display, 0xff, 0xff, 0xff);
        try {
            int width = image.getBounds().width;
            int left = width * 36 / 100;
            int top = 160;
            int height = 34;
            gc.setAntialias(SWT.ON);
            gc.setTextAntialias(SWT.ON);
            gc.setBackground(band);
            gc.fillRectangle(left, top, width - left, height);
            gc.setFont(font);
            gc.setForeground(white);
            Point size = gc.textExtent(shown);
            gc.drawText(shown, left + (width - left - size.x) / 2, top + (height - size.y) / 2, true);
        } finally {
            font.dispose();
            gc.dispose();
        }
    }

    private static void deleteOldFrames(File folder) throws IOException {
        File[] old = folder.listFiles((dir, name) -> name.startsWith("frame-") || name.equals("frames.tsv"));
        if (old != null) {
            for (File f : old) {
                Files.delete(f.toPath());
            }
        }
    }
}
