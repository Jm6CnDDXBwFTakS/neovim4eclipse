// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FilePaths;

/**
 * harpoon in a real editor, at the commit pinned and vetted in {@code releng/test-plugins.properties},
 * with the keys of {@link PinnedPlugins#loadHarpoon}. {@code \a} adds the editor's file to
 * harpoon's list, by its path from the project's root; a jump to a file of the list ({@code \1})
 * opens that file's editor, or brings it to the front when it is open already, and the editor
 * jumped from keeps its file and text; the list's menu ({@code \e}) is drawn over the editor as a
 * float with the keyboard, Enter on a line opens that file, {@code q} closes it, and a line deleted
 * and written ({@code dd}, {@code :w}) leaves the list.
 *
 * <p>harpoon keeps its lists in a file under Neovim's data folder, by the working folder, and the
 * project is made again under the same name for each test: so each test starts and ends without
 * that folder ({@link #deleteHarpoonsData}), or the last test's list would be read again. Its
 * autocommands are the whole Neovim's, so each test ends with <em>Restart Neovim</em>
 * ({@code restartNeovimAfterThisTest}), after closing the editors it opened.
 */
class HarpoonEndToEndTest extends EndToEndTestBase {

    /** a.txt's text: three lines, so that a jump has a line to land on. */
    private static final String A = "a one\na two\na three\n";

    /** b.txt's text. */
    private static final String B = "b one\n";

    /** Lua that answers the list's files, by the paths harpoon keeps, one a line. */
    private static final String LISTED = """
            local values = {}
            for _, item in ipairs(require('harpoon'):list().items) do
              table.insert(values, item.value)
            end
            return table.concat(values, '\\n')""";

    /** Whether this test loaded the plug-in, so that its Neovim is replaced afterwards. */
    private boolean loaded;

    @BeforeEach
    void loadHarpoon() throws Exception {
        Optional<Path> plenary = PinnedPlugins.find("plenary");
        Optional<Path> harpoon = PinnedPlugins.find("harpoon");
        assumeTrue(plenary.isPresent() && harpoon.isPresent(),
                "harpoon or plenary is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        createFile("a.txt", A);
        createFile("b.txt", B);
        deleteHarpoonsData(session);
        loaded = true;
        SessionProbe.lua(session, PinnedPlugins.loadHarpoon(plenary.get(), harpoon.get()) + " return 0");
    }

    @AfterEach
    void restartNeovim() {
        if (!loaded) {
            return; // skipped, or the editor never opened: nothing was loaded
        }
        closeEditorsButTestTxt();
        NeovimSession fresh = restartNeovimAfterThisTest();
        assertEquals(true, SessionProbe.lua(fresh, "return package.loaded['harpoon'] == nil"),
                "the next test's Neovim never loaded harpoon");
        deleteHarpoonsData(fresh);
    }

    /** {@code \a} in an editor adds its file to the list, by its path from the project's root. */
    @Test
    void addTakesTheEditorsFile() {
        type(widget, "\\a");
        waitUntil("test.txt in harpoon's list", TIMEOUT_MS, () -> "test.txt".equals(listed()), () -> "; listed " + listed());
        assertEquals("", SessionProbe.lua(session, PinnedPlugins.PROGRAMS_REFUSED), "harpoon asked to start no program");
    }

    /**
     * A jump to a file of the list that no editor shows opens that file's editor, in front, with
     * the caret on the line harpoon kept for it (here the second); the editor jumped from keeps its
     * text.
     */
    @Test
    void aJumpOpensTheFilesEditorAtItsLine() {
        String text = document.get();
        add("a.txt", 2);
        type(widget, "\\1");
        ITextEditor opened = waitForEditorInFront("a.txt");
        assertEquals(A, documentOf(opened).get(), "a.txt's text");
        StyledText openedText = widgetOf(opened);
        waitUntil("a.txt's caret on its second line", TIMEOUT_MS,
                () -> openedText.getLineAtOffset(openedText.getCaretOffset()) == 1,
                () -> "; caret on line " + (openedText.getLineAtOffset(openedText.getCaretOffset()) + 1));
        assertEquals(text, document.get(), "the editor jumped from kept its text");
        assertEquals(1, count("a.txt"), "one editor on a.txt");
    }

    /** A jump to a file whose editor is open brings that editor to the front; no second one opens. */
    @Test
    void aJumpBringsAnOpenEditorToTheFront() throws Exception {
        ITextEditor open = openEditor("a.txt");
        page().activate(editor);
        waitUntil("test.txt's editor in front", TIMEOUT_MS, () -> page().getActiveEditor() == editor);
        settle();
        add("a.txt");
        type(widget, "\\1");
        assertEquals(open, waitForEditorInFront("a.txt"), "the editor that was open");
        assertEquals(1, count("a.txt"), "one editor on a.txt");
    }

    /**
     * {@code \e} draws the list's menu over the editor, with the keyboard; Enter on its second line
     * opens that file's editor, and the menu goes.
     */
    @Test
    void enterInTheMenuOpensTheFile() {
        add("a.txt");
        add("b.txt");
        openMenu();
        type(widget, "j\r");
        waitForEditorInFront("b.txt");
        waitUntil("the menu gone", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats(widget).isEmpty();
        });
    }

    /** {@code q} closes the menu: no editor opens, the list stays. */
    @Test
    void qClosesTheMenu() {
        add("a.txt");
        openMenu();
        type(widget, "q");
        waitUntil("the menu gone", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats(widget).isEmpty();
        });
        assertEquals(editor, page().getActiveEditor(), "test.txt's editor still in front");
        assertEquals("a.txt", listed(), "the list as it was");
    }

    /** A line deleted in the menu and written ({@code dd}, {@code :w}) leaves the list. */
    @Test
    void aLineDeletedInTheMenuAndWrittenLeavesTheList() {
        add("a.txt");
        add("b.txt");
        openMenu();
        type(widget, "dd");
        waitUntil("a.txt's line gone from the menu", TIMEOUT_MS, () -> {
            settle();
            return !drawn().contains("a.txt");
        }, () -> "; drawn " + drawn());
        type(widget, ":w\r");
        waitUntil("only b.txt in harpoon's list", TIMEOUT_MS, () -> "b.txt".equals(listed()), () -> "; listed " + listed());
    }

    /**
     * A jump in a {@code :vsplit} ({@code \v}) opens the file as an Eclipse split or editor, as a
     * plug-in's split on a file does; test.txt's editor keeps its text.
     */
    @Test
    void aJumpInAVsplitOpensTheFilesEditor() {
        String text = document.get();
        add("a.txt");
        add("b.txt");
        type(widget, "\\v");
        ITextEditor opened = waitForEditorInFront("b.txt");
        assertEquals(B, documentOf(opened).get(), "b.txt's text");
        assertEquals(text, document.get(), "test.txt's editor kept its text");
    }

    /** Adds {@code name}, a file of the project, to harpoon's list at its first line. */
    private void add(String name) {
        add(name, 1);
    }

    /**
     * Adds {@code name}, a file of the project, to harpoon's list as its own API does, with the
     * line ({@code row}, from 1) a jump to it lands on: harpoon keeps an item as its path from the
     * working folder and the cursor's place ({@code { value = 'a.txt', context = { row = 2, col = 0 } }}).
     */
    private void add(String name, int row) {
        SessionProbe.lua(session, "require('harpoon'):list():add({ value = '" + name + "', context = { row = " + row
                + ", col = 0 } }) return 0");
    }

    /** Opens the list's menu with {@code \e} and waits until it is drawn with Neovim's cursor. */
    private void openMenu() {
        type(widget, "\\e");
        waitUntil("harpoon's menu over the editor, with Neovim's cursor", TIMEOUT_MS, () -> {
            settle();
            return pluginFloats(widget).stream().anyMatch(c -> rowsOf(c.grid()).contains(".txt") && c.grid().cursorRow() >= 0);
        }, () -> "; drawn " + drawn());
    }

    /** Waits until {@code name}'s editor is in front and Neovim is attached to it. */
    private ITextEditor waitForEditorInFront(String name) {
        waitUntil(name + "'s editor in front", TIMEOUT_MS, () -> {
            settle();
            return page().getActiveEditor() instanceof ITextEditor active && name.equals(active.getEditorInput().getName());
        }, () -> "; editors " + editors() + ", drawn " + drawn()
                + ", Neovim's buffer " + SessionProbe.lua(session, "return vim.api.nvim_buf_get_name(0)"));
        ITextEditor opened = (ITextEditor) page().getActiveEditor();
        waitForAttach(opened, name);
        return opened;
    }

    private ITextEditor openEditor(String name) throws Exception {
        ITextEditor opened = (ITextEditor) IDE.openEditor(page(), project.getFile(name), TEXT_EDITOR, true);
        waitForAttach(opened, name);
        return opened;
    }

    private String listed() {
        return String.valueOf(SessionProbe.lua(session, LISTED));
    }

    private static int count(String name) {
        int count = 0;
        for (IEditorReference reference : page().getEditorReferences()) {
            if (name.equals(reference.getName())) {
                count++;
            }
        }
        return count;
    }

    private static List<String> editors() {
        IWorkbenchPage page = page();
        List<String> names = new ArrayList<>();
        for (IEditorReference reference : page.getEditorReferences()) {
            IEditorPart part = reference.getEditor(false);
            names.add(reference.getName() + (part != null && part == page.getActiveEditor() ? "*" : ""));
        }
        return names;
    }

    private String drawn() {
        StringBuilder all = new StringBuilder();
        pluginFloats(widget).forEach(c -> all.append(rowsOf(c.grid())).append("---\n"));
        return all.toString();
    }

    /**
     * Deletes harpoon's folder in Neovim's data folder ({@code stdpath('data')/harpoon}), after
     * checking that the data folder is inside the one folder the tests may change
     * ({@code -Dtest.isolated.root}, this module's {@code target/}, as {@code TestIsolationUiTest}
     * checks), never the developer's: a run without that property (from the IDE, with the
     * developer's own {@code XDG_DATA_HOME}) fails here instead of deleting their lists.
     */
    private static void deleteHarpoonsData(NeovimSession neovim) {
        String data = String.valueOf(SessionProbe.lua(neovim, "return vim.fn.stdpath('data')"));
        String root = System.getProperty("test.isolated.root");
        assertTrue(root != null && !root.isBlank() && !root.startsWith("${")
                && FilePaths.inside(data, root, FilePaths.Platform.current()),
                "Neovim's data folder " + data + " is inside the tests' own folder " + root);
        Path folder = Path.of(data, "harpoon");
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new AssertionError("harpoon's data in " + folder, e);
        }
    }
}
