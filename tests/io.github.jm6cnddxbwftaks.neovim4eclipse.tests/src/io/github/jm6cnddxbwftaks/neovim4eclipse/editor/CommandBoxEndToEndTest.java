// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.StatusInfo;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * The {@code :} box ({@link CommandLineBox}) the whole way: {@code :} typed in the editor opens it,
 * the command is typed into it, Enter runs it in Eclipse ({@link HostCommands}) or in Neovim.
 * Covers {@code :e!}, where {@code :e} looks for files, and command names and abbreviations.
 *
 * <p>Errors from Eclipse's side ("Can't find file") go to the editor's message line, read back by
 * {@link #messageText}; errors from Neovim's side arrive as the session's status message.
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
            // Settled first: SessionProbe waits on the UI thread, and Neovim's line events for the
            // test's last command also need the UI thread; asked in between, each waits for the other.
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

    /** Waits for the box to open (from Neovim's command line) reading {@code text}, and returns it. */
    private Text boxReading(String text) {
        waitUntil("the box to open with " + text, TIMEOUT_MS, () -> {
            Text box = textChildOf(widget);
            return box != null && text.equals(box.getText());
        }, () -> {
            Text box = textChildOf(widget);
            return "; the box " + (box == null ? "is closed" : "reads " + box.getText());
        });
        Text box = textChildOf(widget);
        UiTestSupport.requireKeyboardFocus(box, TIMEOUT_MS);
        return box;
    }

    private static void pressEnter(Text box) {
        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        enter.doit = true;
        box.notifyListeners(SWT.KeyDown, enter);
    }

    /** {@code nnoremap ; :}: the key goes to Neovim, which opens its command line, and the box takes it over. */
    @Test
    void aMappingToColonOpensTheBox() throws Exception {
        withMapping(";", ":", () -> {
            type(widget, ';');
            Text box = boxReading(":");
            box.insert("s/alpha/omega/");
            pressEnter(box);
            expectText("omega\nbeta\ngamma\n");
        });
    }

    /** {@code nnoremap <Space>s :%s/}: the box opens with Neovim's text so far. */
    @Test
    void aMappingWithTextPrefillsTheBox() throws Exception {
        withMapping("<Space>s", ":%s/", () -> {
            type(widget, " s");
            Text box = boxReading(":%s/");
            box.insert("a/A/g");
            pressEnter(box);
            expectText("AlphA\nbetA\ngAmmA\n");
        });
    }

    /**
     * {@code nnoremap <Space>d d/}: Neovim's search line belongs to the {@code d} operator, so the box
     * answers that line instead of starting a new search, and the operator deletes up to the match.
     */
    @Test
    void aSearchInsideAMappedOperatorIsAnswered() throws Exception {
        withMapping("<Space>d", "d/", () -> {
            type(widget, " d");
            Text box = boxReading("/");
            box.insert("gamma");
            pressEnter(box);
            expectText("gamma\n");
        });
    }

    /** {@code d/} typed at full speed: Neovim's search line belongs to {@code d}, and the box answers it. */
    @Test
    void aSearchAfterAnOperatorTypedFastIsAnswered() throws Exception {
        type(widget, "ggd/");
        Text box = boxReading("/");
        box.insert("gamma");
        pressEnter(box);
        expectText("gamma\n");
    }

    /** Esc in a box that took over Neovim's search line closes that line too: the next key is a command again. */
    @Test
    void escapeLeavesNeovimsSearchLine() throws Exception {
        withMapping("<Space>d", "d/", () -> {
            type(widget, " d");
            Text box = boxReading("/");
            Event esc = new Event();
            esc.keyCode = SWT.ESC;
            esc.character = SWT.ESC;
            esc.doit = true;
            box.notifyListeners(SWT.KeyDown, esc);
            waitUntil("normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
            type(widget, "x");
            expectText("lpha\nbeta\ngamma\n");
        });
    }

    /**
     * Tab in the {@code :} box, the whole way: Neovim completes {@code :e} names in the window's
     * folder, which is the project's root ({@code :lcd}, set when the editor is attached), and Enter
     * opens the completed file.
     */
    @Test
    void tabCompletesAFileNameForEditAndEnterOpensIt() throws Exception {
        createFile("other.txt", "other\n");
        Text box = openBox(widget, "e ot");

        Event tab = new Event();
        tab.detail = SWT.TRAVERSE_TAB_NEXT;
        tab.keyCode = SWT.TAB;
        tab.character = SWT.TAB;
        tab.doit = true;
        box.notifyListeners(SWT.Traverse, tab);
        waitUntil("Tab to complete the name", TIMEOUT_MS, () -> ":e other.txt".equals(box.getText()),
                () -> "; the box reads " + box.getText());

        Event enter = new Event();
        enter.keyCode = SWT.CR;
        enter.character = SWT.CR;
        enter.doit = true;
        box.notifyListeners(SWT.KeyDown, enter);
        waitUntil("other.txt to open", TIMEOUT_MS, () -> activeEditorIs("other.txt"));
        page().closeEditor(page().getActiveEditor(), false);
    }

    // ---- Output above a prompt of Neovim's ----

    /** The output popup inside {@code parent}: a {@link StyledText} below the editor's own widget. */
    private static StyledText outputPopupIn(org.eclipse.swt.widgets.Composite parent) {
        for (org.eclipse.swt.widgets.Control child : parent.getChildren()) {
            if (child instanceof StyledText text && !text.isDisposed()) {
                return text;
            }
            if (child instanceof org.eclipse.swt.widgets.Composite composite) {
                StyledText found = outputPopupIn(composite);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * {@code :w !sort} shows the text in the output popup, and Neovim then asks for a key itself
     * ({@code :!cmd} does not), which the {@code :} box shows. "Press any key to continue" appears
     * once, in the box: the popup leaves out its own prompt line while the box takes the keys. One
     * key closes both and changes nothing.
     *
     * <p>{@code sort} rather than {@code cat}: Windows has a {@code sort} command but no {@code cat}
     * (a shell started from Git Bash finds Git's {@code cat}, one started from PowerShell does not).
     * The lines are already in order, so {@code sort} gives them back unchanged on every system.
     */
    @Test
    void wBangCmdShowsItsOutputAboveOnePrompt() {
        runInBox(widget, "w !sort");

        waitUntil("the output and Neovim's prompt", TIMEOUT_MS, () -> {
            StyledText popup = outputPopupIn(widget);
            Text box = textChildOf(widget);
            return popup != null && popup.getText().contains("gamma")
                    && box != null && box.getText().equals("Press any key to continue");
        }, () -> "; popup " + (outputPopupIn(widget) == null ? "none" : "\"" + outputPopupIn(widget).getText() + "\"")
                + ", box " + (textChildOf(widget) == null ? "none" : "\"" + textChildOf(widget).getText() + "\""));
        assertEquals(":w !sort\nalpha\nbeta\ngamma", outputPopupIn(widget).getText(), "no prompt line of its own");

        Event key = new Event();
        key.character = 'x';
        key.keyCode = 'x';
        key.doit = true;
        textChildOf(widget).notifyListeners(SWT.KeyDown, key);

        waitUntil("both to close", TIMEOUT_MS, () -> outputPopupIn(widget) == null && textChildOf(widget) == null);
        settle();
        assertEquals("alpha\nbeta\ngamma\n", document.get(), "the key answered the prompt, nothing else");
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

        runInBox(widgetOf(nestedEditor), "e root.txt");

        waitUntil("root.txt to open", TIMEOUT_MS, () -> activeEditorIs("root.txt"));
        ITextEditor opened = (ITextEditor) page().getActiveEditor();
        assertEquals("root\n", documentOf(opened).get(), "the root's root.txt, not src/root.txt");
        page().closeAllEditors(false);
        editor = null;
    }

    /**
     * An absolute path outside the workspace opens as an outside file (no project). (A temporary
     * folder made by hand: JUnit's {@code @TempDir} is in a package the fragment does not import.)
     */
    @Test
    void editOpensAnAbsolutePathOutsideTheWorkspace() throws Exception {
        Path dir = Files.createTempDirectory("neovim4eclipse-e2e").toRealPath();
        Path outside = dir.resolve("outside.txt");
        try {
            Files.writeString(outside, "outside\n", StandardCharsets.UTF_8);

            runInBox(widget, "e " + outside);

            waitUntil("outside.txt to open", TIMEOUT_MS, () -> activeEditorIs("outside.txt"));
            IEditorPart opened = page().getActiveEditor();
            assertTrue(opened.getEditorInput() instanceof IURIEditorInput, "opened from its location");
            assertNull(opened.getEditorInput().getAdapter(IFile.class), "not a workspace file");
            page().closeEditor(opened, false);
        } finally {
            leaveAndDelete(dir, outside);
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

        runInBox(untitledWidget, "e root.txt");
        waitUntil("root.txt to open", TIMEOUT_MS, () -> activeEditorIs("root.txt"),
                () -> "; the message line shows " + messageText(untitled));
        assertEquals("root\n", documentOf((ITextEditor) page().getActiveEditor()).get(), "the project's root.txt");
        page().closeEditor(page().getActiveEditor(), false);

        page().activate(untitled);
        waitForAttach(untitled, "the untitled file");
        runInBox(untitledWidget, "e " + project.getFile("root.txt").getLocation().toOSString());
        waitUntil("root.txt to open by its absolute path", TIMEOUT_MS, () -> activeEditorIs("root.txt"));
        page().closeAllEditors(false);
        editor = null;
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

        runInBox(widget, "wal");

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
        runInBox(widget, "w");
        waitUntil("test.txt saved", TIMEOUT_MS, () -> !editor.isDirty());
        assertEquals("1alpha\nbeta\ngamma\n", fileText("test.txt"));
        String expected = "\"test.txt\" 3L, 18B written";
        waitUntil("the message " + expected, TIMEOUT_MS, () -> expected.equals(messageText(editor)),
                () -> "; the message line shows " + messageText(editor));
    }

    /**
     * A mapping's {@code :w<CR>} saves through Eclipse, as the typed one does: both are Neovim's write,
     * whose hook asks Eclipse to save ({@code LuaScripts.DOCUMENT_BUFFER}).
     */
    @Test
    void aMappingsColonWSavesTheEditor() throws Exception {
        document.replace(0, 0, "1");
        settle();
        withMapping("<Space>w", ":w<CR>", () -> {
            type(widget, " w");
            waitUntil("test.txt saved", TIMEOUT_MS, () -> !editor.isDirty());
            assertEquals("1alpha\nbeta\ngamma\n", fileText("test.txt"));
        });
    }

    /**
     * {@code :q} takes no argument, so {@code :q foo} goes to Neovim, which reports E488; nothing
     * closes. The error reaches the plug-in's status bar as the session's status message (Neovim
     * sends it as a message event of kind {@code emsg}).
     */
    @Test
    void quitWithAnArgumentIsAnErrorAndKeepsTheEditorOpen() {
        List<String> messages = new CopyOnWriteArrayList<>();
        Consumer<StatusInfo> listener = status -> messages.add(status.message());
        session.addStatusListener(listener);
        try {
            runInBox(widget, "q foo");

            waitUntil("Neovim's error", TIMEOUT_MS, () -> messages.stream().anyMatch(m -> m.startsWith("E488")),
                    () -> "; the messages were " + messages);
            assertSame(editor, page().getActiveEditor());
            assertFalse(widget.isDisposed());
        } finally {
            session.removeStatusListener(listener);
        }
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

        runInBox(widget, "e!");
        expectText("alpha\nbeta\ngamma\n");
        assertFalse(editor.isDirty());

        type(widget, "Gdd");
        expectText("alpha\nbeta\n");
    }

    // ---- Eclipse's shortcuts in the box ----

    /**
     * Eclipse's Paste (Ctrl+V, Cmd+V, Shift+Insert) with the box open pastes into Neovim's line, not
     * into the file behind it.
     */
    @Test
    void pasteWithTheBoxOpenPastesIntoTheLine() throws Exception {
        type(widget, ':');
        boxReading(":");
        Clipboard clipboard = new Clipboard(widget.getDisplay());
        try {
            // Not a :s, whose 'inccommand' preview would change the buffer before Enter.
            clipboard.setContents(new Object[] { "2delete" }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }

        String beforePaste = clipboardText();
        runCommand("org.eclipse.ui.edit.paste");

        // The system clipboard is shared: in a virtual machine with clipboard sharing, the host's
        // clipboard can replace "2delete" before the paste (CONTRIBUTING.md, "Tests on a virtual
        // machine"). The message says what the clipboard held, so that case is recognized at once.
        waitUntil("the box to read :2delete", TIMEOUT_MS, () -> {
            Text open = textChildOf(widget);
            return open != null && ":2delete".equals(open.getText());
        }, () -> {
            Text open = textChildOf(widget);
            return "; the box " + (open == null ? "is closed" : "reads " + open.getText())
                    + "; the clipboard held \"" + beforePaste + "\" before the paste and holds \""
                    + clipboardText() + "\" now";
        });
        Text box = textChildOf(widget);
        expectText("alpha\nbeta\ngamma\n"); // the file is untouched
        pressEnter(box);
        expectText("alpha\ngamma\n");
    }

    /** While the box has the focus, the Vim Ctrl keys are bound to Neovim: Ctrl+U clears Neovim's line. */
    @Test
    void aVimCtrlKeyInTheBoxReachesNeovimsLine() throws Exception {
        type(widget, ':');
        Text box = boxReading(":");
        box.insert("echo 1");
        boxReading(":echo 1");
        waitUntil("Ctrl+U bound to Neovim", TIMEOUT_MS,
                () -> VimCtrlKeys.commandId('u').equals(commandFor("CTRL+U")),
                () -> "; Ctrl+U runs " + commandFor("CTRL+U"));

        runCommand(VimCtrlKeys.commandId('u'));

        Text cleared = boxReading(":");
        pressEnter(cleared); // an empty line closes without running anything
        waitUntil("the box to close", TIMEOUT_MS, () -> textChildOf(widget) == null);
    }

    private static String commandFor(String keys) {
        try {
            return KeyBindings.commandIdFor(keys);
        } catch (Exception e) {
            return e.toString();
        }
    }
}
