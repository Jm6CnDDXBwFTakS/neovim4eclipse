// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimModes;

/**
 * Neovim's command line the whole way: {@code :} typed in the editor opens it, ui2 draws it over
 * the editor's last row, the command goes in as keys or a paste while the editor's widget keeps
 * the keyboard, and Enter runs it, in Neovim or, through Neovim's hooks, in Eclipse
 * ({@link HostCommands}). Covers {@code :e!}, where {@code :e} looks for files, and command names
 * and abbreviations.
 *
 * <p>The line is read back from Neovim ({@link #commandLine}), and messages from ui2's windows
 * ({@link #shownText}): both Neovim's own and the plug-in's, which go through Neovim
 * ({@code NeovimSession.echoAsync}).
 */
class CommandBoxEndToEndTest extends EndToEndTestBase {

    private static boolean activeEditorIs(String title) {
        IEditorPart active = page().getActiveEditor();
        return active != null && title.equals(active.getTitle());
    }

    // ---- command lines opened in Neovim by a mapping (docs/design/KEY_ROUTING.md, phase 2) ----

    /**
     * Maps {@code lhs} to {@code rhs} in Neovim's normal mode, runs {@code test}, and removes the
     * mapping again: one Neovim serves every test.
     */
    private void withMapping(String lhs, String rhs, ThrowingRunnable test) throws Exception {
        SessionProbe.lua(session, "vim.keymap.set('n', '" + lhs + "', '" + rhs + "')");
        try {
            test.run();
        } finally {
            settle();
            SessionProbe.lua(session, "pcall(vim.keymap.del, 'n', '" + lhs + "')");
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** The text on the system clipboard, or "" when it holds none. */
    private String clipboardText() {
        Clipboard clipboard = new Clipboard(widget.getDisplay());
        try {
            Object text = clipboard.getContents(TextTransfer.getInstance());
            return text instanceof String string ? string : "";
        } finally {
            clipboard.dispose();
        }
    }

    /**
     * Puts {@code text} into Neovim's open command line as a paste, and waits until the line reads
     * {@code expected}. Example: with {@code :} open, {@code paste("echo", ":echo")}.
     */
    private void paste(String text, String expected) {
        session.pasteAsync(text);
        expectCommandLine(expected);
    }

    /**
     * Ex mode ({@code gQ}): a click in the editor keeps Neovim's line and the editor's focus. Before,
     * the click sent {@code <C-c>}, which Neovim ignores in Ex mode, and closed the line Eclipse
     * showed over the line Neovim kept: the user typed blind. Sent to Neovim, the click also moved
     * the line's cursor to its start ({@code :ho 1ec}). {@code :vi} leaves Ex mode.
     */
    @Test
    void aClickInTheEditorKeepsExModesLine() {
        type(widget, "gQ");
        try {
            expectCommandLine(":");
            paste("echo", ":echo");

            Event click = new Event();
            click.button = 1;
            click.x = 5;
            click.y = 5;
            widget.notifyListeners(SWT.MouseDown, click);
            settle();
            expectCommandLine(":echo"); // still open, its cursor still at the end

            paste(" 1", ":echo 1");
            pressEnter(widget);
            waitUntil("the command run", TIMEOUT_MS,
                    () -> "echo 1".equals(SessionProbe.lua(session, "return vim.fn.histget(':', -1)")),
                    () -> "; last command " + SessionProbe.lua(session, "return vim.fn.histget(':', -1)"));
            assertEquals("cv", session.getMode(), "still in Ex mode");
        } finally {
            if (VimModes.isExMode(session.getMode())) {
                session.inputAsync("<C-u>vi<CR>");
            }
            waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        }
    }

    /**
     * The toolbar button switched to Eclipse mode with a command line open: Neovim's line goes
     * (Neovim is in normal mode again), as the keys no longer reach it. Back in Neovim mode,
     * {@code :} opens it as usual.
     */
    @Test
    void eclipseModeClosesAnOpenLine() {
        NeovimEditorController controller = EditorAttachManager.getInstance().getActiveController();
        openCommandLine(widget, "");
        try {
            controller.setNeovimModeActive(false);
            waitUntil("Neovim's line abandoned", TIMEOUT_MS, () -> "n".equals(session.getMode()),
                    () -> "; mode " + session.getMode());
        } finally {
            controller.setNeovimModeActive(true);
        }
        settle();
        runCommandLine(widget, "s/alpha/omega/");
        expectText("omega\nbeta\ngamma\n");
    }

    /** {@code nnoremap <Space>s :%s/}: Neovim's line opens with the mapping's text, and the rest goes after it. */
    @Test
    void aMappingWithTextPrefillsTheLine() throws Exception {
        withMapping("<Space>s", ":%s/", () -> {
            type(widget, " s");
            expectCommandLine(":%s/");
            paste("a/A/g", ":%s/a/A/g");
            pressEnter(widget);
            expectText("AlphA\nbetA\ngAmmA\n");
        });
    }

    /**
     * {@code nnoremap <Space>d d/}: Neovim's search line belongs to the {@code d} operator, so the
     * pattern typed answers that line instead of starting a new search, and the operator deletes up
     * to the match.
     */
    @Test
    void aSearchInsideAMappedOperatorIsAnswered() throws Exception {
        withMapping("<Space>d", "d/", () -> {
            type(widget, " d");
            expectCommandLine("/");
            paste("gamma", "/gamma");
            pressEnter(widget);
            expectText("gamma\n");
        });
    }

    /** {@code d/} typed at full speed: Neovim's search line belongs to {@code d}, and the pattern answers it. */
    @Test
    void aSearchAfterAnOperatorTypedFastIsAnswered() throws Exception {
        type(widget, "ggd/");
        expectCommandLine("/");
        paste("gamma", "/gamma");
        pressEnter(widget);
        expectText("gamma\n");
    }

    /** Esc in Neovim's search line closes it: the next key is a command again. */
    @Test
    void escapeLeavesNeovimsSearchLine() throws Exception {
        withMapping("<Space>d", "d/", () -> {
            type(widget, " d");
            expectCommandLine("/");
            escape(widget);
            waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
            type(widget, "x");
            expectText("lpha\nbeta\ngamma\n");
        });
    }

    /**
     * Tab on Neovim's line, the whole way: Neovim completes {@code :e} names in the window's folder,
     * which is the project's root ({@code :lcd}, set when the editor is attached), and Enter opens
     * the completed file. The Tab goes to Neovim as a key; Eclipse does not move the focus with it.
     */
    @Test
    void tabCompletesAFileNameForEditAndEnterOpensIt() throws Exception {
        createFile("other.txt", "other\n");
        openCommandLine(widget, "e ot");

        type(widget, '\t');
        expectCommandLine(":e other.txt");

        pressEnter(widget);
        waitUntil("other.txt to open", TIMEOUT_MS, () -> activeEditorIs("other.txt"));
        page().closeEditor(page().getActiveEditor(), false);
    }

    // ---- Output of a command ----

    /**
     * {@code :w !sort} shows the command's output in Neovim's own windows (ui2's message window or
     * pager), and changes nothing in the file.
     *
     * <p>{@code sort} rather than {@code cat}: Windows has a {@code sort} command but no {@code cat}
     * (a shell started from Git Bash finds Git's {@code cat}, one started from PowerShell does not).
     * The lines are already in order, so {@code sort} gives them back unchanged on every system.
     */
    @Test
    void wBangCmdShowsItsOutput() {
        runCommandLine(widget, "w !sort");

        expectShown("alpha\nbeta\ngamma");
        // ui2 has no "Press ENTER" prompt of its own; Esc leaves whatever window it opened.
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        settle();
        assertEquals("alpha\nbeta\ngamma\n", document.get(), "the command changed nothing");
    }

    // ---- Where :e looks ----

    /**
     * From a file in a subfolder, {@code :e name} is the project root's {@code name}, as
     * {@code :w name} is: relative to the window's folder, as in Neovim. A file of that name beside
     * the edited one is not looked at (until 0.1.9 it was, first).
     */
    @Test
    void editFindsAFileAtTheProjectRoot() throws Exception {
        IFolder folder = project.getFolder("src");
        folder.create(true, true, null);
        IFile nested = createFile("src/Nested.txt", "nested\n");
        createFile("src/root.txt", "beside\n");
        createFile("root.txt", "root\n");
        ITextEditor nestedEditor = (ITextEditor) IDE.openEditor(page(), nested, TEXT_EDITOR, true);
        waitForAttach(nestedEditor, "src/Nested.txt");

        runCommandLine(widgetOf(nestedEditor), "e root.txt");

        waitUntil("root.txt to open", TIMEOUT_MS, () -> activeEditorIs("root.txt"));
        ITextEditor opened = (ITextEditor) page().getActiveEditor();
        assertEquals("root\n", documentOf(opened).get(), "the root's root.txt, not src/root.txt");
        page().closeAllEditors(false);
        editor = null;
    }

    /**
     * An absolute path outside the workspace opens as an outside file (no project). The folder is a
     * JUnit {@code @TempDir}, deleted after the test; its real path, since on macOS the temporary
     * folder's {@code /var} is a link to {@code /private/var}.
     */
    @Test
    void editOpensAnAbsolutePathOutsideTheWorkspace(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path outside = dir.resolve("outside.txt");
        try {
            Files.writeString(outside, "outside\n", StandardCharsets.UTF_8);

            runCommandLine(widget, "e " + outside);

            waitUntil("outside.txt to open", TIMEOUT_MS, () -> activeEditorIs("outside.txt"));
            IEditorPart opened = page().getActiveEditor();
            assertTrue(opened.getEditorInput() instanceof IURIEditorInput, "opened from its location");
            assertNull(opened.getEditorInput().getAdapter(IFile.class), "not a workspace file");
            page().closeEditor(opened, false);
        } finally {
            leave(dir);
        }
    }

    /**
     * An untitled file (File › New › Untitled Text File) has no folder of its own: its Neovim window
     * works in the folder of the editor active before it (docs/design/WINDOWS.md, "Working folder
     * per window"), here the test project's root. So {@code :e root.txt} opens that project's
     * {@code root.txt}, as Neovim's {@code :e} would in that folder, and an absolute path opens too.
     * (Until untitled documents got that folder, a relative name was not found.)
     */
    @Test
    void anUntitledFileFindsNamesInTheFolderOfTheEditorBeforeIt() throws Exception {
        createFile("root.txt", "root\n");
        ITextEditor untitled = openUntitledFile();
        StyledText untitledWidget = widgetOf(untitled);

        runCommandLine(untitledWidget, "e root.txt");
        waitUntil("root.txt to open", TIMEOUT_MS, () -> activeEditorIs("root.txt"),
                () -> "; Neovim shows " + shownText());
        assertEquals("root\n", documentOf((ITextEditor) page().getActiveEditor()).get(), "the project's root.txt");
        page().closeEditor(page().getActiveEditor(), false);

        page().activate(untitled);
        waitForAttach(untitled, "the untitled file");
        runCommandLine(untitledWidget, "e " + project.getFile("root.txt").getLocation().toOSString());
        waitUntil("root.txt to open by its absolute path", TIMEOUT_MS, () -> activeEditorIs("root.txt"));
        page().closeAllEditors(false);
        editor = null;
    }

    /**
     * {@code :vimgrep} into files without an editor or a buffer yet: its first match's editor opens,
     * and {@code :cnext} opens the next one's. {@code :vimgrep} makes those buffers without
     * {@code BufNew}, so before, no editor opened (found on macOS, any system). Its
     * {@code (1 of 2): ...} shows in Neovim's line, as on Neovim's own screen.
     */
    @Test
    void vimgrepShowsItsFirstMatchInNeovimsLine() throws Exception {
        project.getFolder("vg").create(true, true, null);
        createFile("vg/bar.txt", "class Bar\n");
        createFile("vg/foo.txt", "see Bar\n");

        try {
            runCommandLine(widget, "vimgrep /Bar/ vg/*.txt");
            waitUntil("bar.txt to open", TIMEOUT_MS, () -> activeEditorIs("bar.txt"),
                    () -> "; Neovim shows " + shownText() + "; " + whereNeovimIs());
            ITextEditor bar = (ITextEditor) page().getActiveEditor();
            expectShown("(1 of 2): class Bar");
            waitForAttach(bar, "bar.txt");

            runCommandLine(widgetOf(bar), "cnext");
            waitUntil("foo.txt to open", TIMEOUT_MS, () -> activeEditorIs("foo.txt"),
                    () -> "; Neovim shows " + shownText() + "; " + whereNeovimIs());
            expectShown("(2 of 2): see Bar");
        } finally {
            // Also after a failure: the editors the jumps opened would stay for the next tests.
            page().closeAllEditors(false);
            editor = null;
        }
    }

    /**
     * A {@code :vimgrep} with no match shows Vim's error, {@code E480: No match: nosuchword}, and
     * opens no editor. The manual test "A :vimgrep list through files with no editor", its last
     * step.
     */
    @Test
    void vimgrepWithNoMatchShowsE480() throws Exception {
        project.getFolder("vg").create(true, true, null);
        createFile("vg/bar.txt", "class Bar\n");

        runCommandLine(widget, "vimgrep /nosuchword/ vg/*.txt");

        expectShown("E480: No match: nosuchword");
        settle();
        assertSame(editor, page().getActiveEditor(), "no editor opened");
    }

    /**
     * {@code :%s} typed on Neovim's line changes the document only on Enter. While it is typed,
     * Neovim's {@code 'inccommand'} previews the substitution in its buffer and reports the preview
     * as buffer events without a {@code changedtick}; the plug-in drops those (NotificationBridge),
     * so the editor keeps its text and stays clean, also after Esc. Before, the preview reached
     * Eclipse as real edits and stayed after Esc, for a save to write. After Enter the text changes,
     * and {@code u} takes it back in one step. The manual test "A substitution changes the text only
     * on Enter".
     */
    @Test
    void aSubstitutionChangesTheDocumentOnlyOnEnter() throws Exception {
        assertEquals("nosplit", SessionProbe.lua(session, "return vim.o.inccommand"), "Neovim previews :s as it is typed");
        openCommandLine(widget, "%s/a/A/g");
        settle(); // the preview's buffer events reached the session, had they been applied
        expectText("alpha\nbeta\ngamma\n");
        assertFalse(editor.isDirty(), "no change while the command is typed");

        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        settle();
        expectText("alpha\nbeta\ngamma\n");
        assertFalse(editor.isDirty(), "no change after Esc");

        runCommandLine(widget, "%s/a/A/g");
        expectText("AlphA\nbetA\ngAmmA\n");
        assertTrue(editor.isDirty(), "changed on Enter");
        type(widget, "u");
        expectText("alpha\nbeta\ngamma\n");
    }

    /**
     * What a failed jump into a project file needs to be understood (a test that timed out on
     * Windows): the buffer Neovim is in, whether the plug-in's read hook knows its file
     * ({@code file_of}: empty means Eclipse was never told to open it), the buffers and the last
     * messages, and the buffers the session saw entered and attached.
     */
    private String whereNeovimIs() {
        try {
            Object neovim = SessionProbe.lua(session, """
                    local b = vim.api.nvim_get_current_buf()
                    return string.format('Neovim in buffer %d %q (file_of %q, loaded %s, buftype %q), quickfix %d'
                        .. ' entries; :ls! %s; :messages %s', b, vim.api.nvim_buf_get_name(b),
                        package.loaded['neovim4eclipse.project_files'].file_of(b), tostring(vim.api.nvim_buf_is_loaded(b)),
                        vim.bo[b].buftype, #vim.fn.getqflist(), vim.fn.execute('ls!'), vim.fn.execute('messages'):sub(-400))
                    """);
            return neovim + "; session: entered " + session.getEnteredBufnr() + ", attached "
                    + session.getAttachedBufnr();
        } catch (AssertionError e) {
            return "Neovim did not answer: " + e.getCause();
        }
    }

    // ---- Command names; :e! ----

    /** {@code :wal} is {@code :wall}, abbreviated: Eclipse saves every changed file. */
    @Test
    void walSavesEveryChangedFile() throws Exception {
        ITextEditor second = openFile("second.txt", "second\n");
        documentOf(second).replace(0, 0, "2");
        page().activate(editor);
        waitForAttach(editor, "test.txt");
        document.replace(0, 0, "1");
        settle();

        runCommandLine(widget, "wal");

        waitUntil("both files saved", TIMEOUT_MS, () -> !editor.isDirty() && !second.isDirty());
        assertEquals("1alpha\nbeta\ngamma\n", fileText("test.txt"));
        assertEquals("2second\n", fileText("second.txt"));
        page().closeEditor(second, false);
    }

    /**
     * A typed {@code :w} is Neovim's write: its write hook asks Eclipse to save, and Neovim shows
     * Vim's message, the bytes counted in the file's encoding.
     */
    @Test
    void colonWSavesAndShowsVimsWrittenMessage() throws Exception {
        document.replace(0, 0, "1");
        settle();
        runCommandLine(widget, "w");
        waitUntil("test.txt saved", TIMEOUT_MS, () -> !editor.isDirty());
        assertEquals("1alpha\nbeta\ngamma\n", fileText("test.txt"));
        expectShown("\"test.txt\" 3L, 18B written");
    }

    /**
     * {@code :e!} reverts the editor to the saved file; the revert must reach Neovim, or
     * {@code G dd} would delete a line of the text from before.
     */
    @Test
    void editBangRevertsAndNeovimHasTheSavedText() {
        type(widget, "Gdd");
        expectText("alpha\nbeta\n");
        type(widget, "ggdd");
        expectText("beta\n");

        runCommandLine(widget, "e!");
        expectText("alpha\nbeta\ngamma\n");
        assertFalse(editor.isDirty());

        type(widget, "Gdd");
        expectText("alpha\nbeta\n");
    }

    // ---- Eclipse's shortcuts on Neovim's line ----

    /**
     * Eclipse's Paste (Ctrl+V, Cmd+V, Shift+Insert) with Neovim's line open pastes into that line
     * ({@code CommandLineEditHandlers}), not into the file behind it.
     */
    @Test
    void pasteWithTheLineOpenPastesIntoTheLine() throws Exception {
        openCommandLine(widget, "");
        Clipboard clipboard = new Clipboard(widget.getDisplay());
        try {
            // Not a :s, whose 'inccommand' preview would change the buffer before Enter.
            clipboard.setContents(new Object[] { "2delete" }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }

        String beforePaste = clipboardText();
        // The editor hears of the open line after Neovim reports it, and only then activates the
        // Paste handler of its own (CommandLineEditHandlers): the report is waited for first.
        settle();
        runCommand("org.eclipse.ui.edit.paste");

        // The system clipboard is shared: in a virtual machine with clipboard sharing, the host's
        // clipboard can replace "2delete" before the paste (CONTRIBUTING.md, "Tests on a virtual
        // machine"). The message says what the clipboard held, so that case is recognized at once.
        String[] line = { "" };
        waitUntil("Neovim's line to read :2delete", TIMEOUT_MS, () -> ":2delete".equals(line[0] = commandLine()),
                () -> "; it reads \"" + line[0] + "\"; the clipboard held \"" + beforePaste
                        + "\" before the paste and holds \"" + clipboardText() + "\" now");
        expectText("alpha\nbeta\ngamma\n"); // the file is untouched
        pressEnter(widget);
        expectText("alpha\ngamma\n");
    }

    /** While Neovim's line is open, the Vim Ctrl keys are bound to Neovim: Ctrl+U clears the line. */
    @Test
    void aVimCtrlKeyReachesNeovimsLine() throws Exception {
        openCommandLine(widget, "echo 1");
        waitUntil("Ctrl+U bound to Neovim", TIMEOUT_MS,
                () -> VimCtrlKeys.commandId('u').equals(commandFor("CTRL+U")),
                () -> "; Ctrl+U runs " + commandFor("CTRL+U"));

        runCommand(VimCtrlKeys.commandId('u'));

        expectCommandLine(":");
        pressEnter(widget); // an empty line closes without running anything
        expectCommandLine("");
    }

    private static String commandFor(String keys) {
        try {
            return KeyBindings.commandIdFor(keys);
        } catch (Exception e) {
            return e.toString();
        }
    }
}
