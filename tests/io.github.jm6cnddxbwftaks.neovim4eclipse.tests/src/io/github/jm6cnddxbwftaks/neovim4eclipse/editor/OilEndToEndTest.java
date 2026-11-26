// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;

/**
 * oil.nvim in a real editor, at the release pinned and vetted in
 * {@code releng/test-plugins.properties}: its float ({@code require('oil').open_float(folder)})
 * lists the project's folder over the editor, with Neovim's cursor and the editor's caret hidden;
 * Enter on a file opens that file's editor; a line renamed there and written ({@code :w}, then
 * {@code y} in oil's confirmation) moves the file on disk. An editor open on the file moved ends on
 * the new name, its text Neovim's; one with unsaved changes keeps them, under the old name, and
 * the file moved opens as saved; once Eclipse checks that editor's file, its own dialogs save the
 * changes under the old name again, as they do after a delete. A saved file deleted there loses
 * its editor once Eclipse checks the file (the editor coming to the front, a refresh), as for any
 * file deleted outside Eclipse. The same through the session alone is
 * {@code OilIntegrationTest}. Skipped without the plug-in ({@link PinnedPlugins#HOW}).
 *
 * <p>oil's setup acts on the whole Neovim, which the UI tests share (the reader of
 * {@code oil://} buffers, {@code :Oil}), so each test ends with <em>Restart Neovim</em>
 * ({@code restartNeovimAfterThisTest}), after closing the editors it opened. The project and its
 * files go after each test anyway ({@code EndToEndTestBase}).
 */
class OilEndToEndTest extends EndToEndTestBase {

    /** The one action the rename makes, as oil's confirmation lists it. */
    private static final String MOVE = "MOVE open-me.txt -> moved.txt";

    /** The one action deleting open-me.txt's line makes, as oil's confirmation lists it. */
    private static final String DELETE = "DELETE open-me.txt";

    /** {@link Dialogs}' answers that save a change again under a name oil took away ({@link #saveAgain}). */
    private static final String[] SAVE_AGAIN = { "File Not Accessible", "Save", "Save As", "OK", "Question", "Yes" };

    /** Whether this test loaded the plug-in, so that its Neovim is replaced afterwards. */
    private boolean loaded;

    @BeforeEach
    void loadOil() throws CoreException {
        Optional<Path> oil = PinnedPlugins.find("oil.nvim");
        assumeTrue(oil.isPresent(), "oil.nvim is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        project.getFile("open-me.txt").create(new ByteArrayInputStream("one\ntwo\n".getBytes(StandardCharsets.UTF_8)),
                true, null);
        loaded = true;
        SessionProbe.lua(session, PinnedPlugins.loadOil(oil.get()) + " return 0");
    }

    @AfterEach
    void restartNeovim() {
        if (!loaded) {
            return; // skipped, or the editor never opened: nothing was loaded
        }
        // test.txt's editor to the front first, then the others closed at once, unsaved: closing
        // the editor in front would bring another forward, and one whose file oil took away, with
        // unsaved changes (a test that failed before Eclipse asked), would ask now (File Not
        // Accessible, a question no watch answers here) and hold the run. Closing editors that
        // are not in front brings none forward.
        IWorkbenchPage page = page();
        page.activate(editor);
        List<IEditorReference> others = new ArrayList<>();
        for (IEditorReference reference : page.getEditorReferences()) {
            if (reference.getEditor(false) != editor) {
                others.add(reference);
            }
        }
        page.closeEditors(others.toArray(IEditorReference[]::new), false);
        settle();
        NeovimSession fresh = restartNeovimAfterThisTest();
        assertEquals(true, SessionProbe.lua(fresh, "return package.loaded['oil'] == nil"),
                "the next test's Neovim never loaded oil");
    }

    /** Enter on a line of oil's float opens that file in an editor of its own; the float goes. */
    @Test
    void enterOnAFileInTheFloatOpensItsEditor() {
        String text = document.get();
        openFloat(widget);
        moveToLine("open-me");
        type(widget, "\r");
        waitUntil("open-me.txt's editor in front, oil's float gone", TIMEOUT_MS, () -> {
            settle();
            return page().getActiveEditor() instanceof ITextEditor active && active != editor
                    && "open-me.txt".equals(active.getEditorInput().getName()) && pluginFloats().isEmpty();
        }, () -> "; editors " + editors());
        ITextEditor opened = (ITextEditor) page().getActiveEditor();
        waitForAttach(opened, "open-me.txt");
        assertEquals("one\ntwo\n", documentOf(opened).get(), "the file's text");
        assertEquals(text, document.get(), "the editor the float was opened over kept its text");
        assertEquals("", SessionProbe.lua(session, PinnedPlugins.PROGRAMS_REFUSED), "oil asked to start no program");
    }

    /**
     * open-me.txt open in an editor, saved, renamed in oil's float to moved.txt: once written and
     * confirmed, the file is moved on disk and its editor is moved.txt's, with the same text, as
     * Neovim's buffer now is (the text check at the end compares them).
     */
    @Test
    void aSavedFileRenamedInTheFloatMovesAndItsEditorFollows() throws Exception {
        ITextEditor open = openEditor("open-me.txt");
        renameInTheFloat(widgetOf(open));
        waitUntil("moved.txt's editor in front, no editor left on open-me.txt", TIMEOUT_MS, () -> {
            settle();
            return page().getActiveEditor() instanceof ITextEditor active
                    && "moved.txt".equals(active.getEditorInput().getName()) && !editors().contains("open-me.txt");
        }, () -> "; editors " + editors());
        ITextEditor moved = (ITextEditor) page().getActiveEditor();
        waitForAttach(moved, "moved.txt");
        assertEquals("one\ntwo\n", documentOf(moved).get(), "moved.txt's editor, the file's text");
        assertEquals("", SessionProbe.lua(session, PinnedPlugins.PROGRAMS_REFUSED), "oil asked to start no program");
    }

    /**
     * open-me.txt with an unsaved change ({@code x} took its first letter) renamed in oil's float:
     * the file on disk moves as saved, and its editor keeps the change under the old name, still
     * unsaved, with Neovim's buffer of that name holding it too; moved.txt opens in an editor of
     * its own with the file's saved text. Nothing typed is lost.
     */
    @Test
    void anUnsavedChangeStaysInItsEditorUnderTheOldName() throws Exception {
        ITextEditor open = openEditor("open-me.txt");
        StyledText openText = widgetOf(open);
        type(openText, "x");
        waitUntil("the change, unsaved", TIMEOUT_MS, () -> {
            settle();
            return "ne\ntwo\n".equals(documentOf(open).get()) && open.isDirty();
        });
        // Answering from before the write: Eclipse may ask as soon as the float closes and the
        // editor whose file oil moved away has the focus again.
        Dialogs dialogs = new Dialogs(SAVE_AGAIN);
        try {
            renameInTheFloat(openText);
            waitUntil("moved.txt's editor open, open-me.txt's still there", TIMEOUT_MS, () -> {
                settle();
                return editorNamed("moved.txt") != null && editors().contains("open-me.txt");
            }, () -> "; editors " + editors() + ", dialogs " + dialogs.seen());
            assertEquals("one\ntwo\n", documentOf(editorNamed("moved.txt")).get(), "moved.txt's editor, the text saved");
            if (dialogs.seen().isEmpty()) { // Eclipse has not checked open-me.txt's file yet
                assertEquals("ne\ntwo\n", documentOf(open).get(), "open-me.txt's editor kept the change");
                assertTrue(open.isDirty(), "and it is still unsaved");
                assertEquals("ne\ntwo", SessionProbe.lua(session, """
                        for _, b in ipairs(vim.api.nvim_list_bufs()) do
                          if vim.fn.fnamemodify(vim.api.nvim_buf_get_name(b), ':t') == 'open-me.txt' then
                            return table.concat(vim.api.nvim_buf_get_lines(b, 0, -1, false), '\\n')
                          end
                        end
                        return 'no buffer'"""), "Neovim's buffer of open-me.txt holds the change too");
            }
            saveAgain(open, dialogs);
        } finally {
            dialogs.stop();
        }
        assertEquals("one\ntwo\n", Files.readString(file("moved.txt")), "moved.txt as it was");
        assertEquals("", SessionProbe.lua(session, PinnedPlugins.PROGRAMS_REFUSED), "oil asked to start no program");
    }

    /**
     * open-me.txt with an unsaved change, its line deleted in oil's float: the file is gone from
     * disk, the editor keeps the change, and once Eclipse checks the file (as the float closing
     * gives the editor the focus back, or as it comes to the front again) Eclipse's dialogs save
     * the change under the old name again ({@link #saveAgain}).
     */
    @Test
    void anUnsavedChangeLeftByADeleteIsSavedAgain() throws Exception {
        ITextEditor open = openEditor("open-me.txt");
        StyledText openText = widgetOf(open);
        type(openText, "x");
        waitUntil("the change, unsaved", TIMEOUT_MS, () -> {
            settle();
            return "ne\ntwo\n".equals(documentOf(open).get()) && open.isDirty();
        });
        // Answering from before the write: Eclipse may ask as soon as the float closes.
        Dialogs dialogs = new Dialogs(SAVE_AGAIN);
        try {
            writeInTheFloat(openText, () -> typeSlowly(openText, "dd"), "open-me.txt", false, DELETE);
            waitUntil("open-me.txt deleted on disk, or saved again already", TIMEOUT_MS,
                    () -> !Files.exists(file("open-me.txt")) || !dialogs.seen().isEmpty());
            saveAgain(open, dialogs);
        } finally {
            dialogs.stop();
        }
        assertEquals("", SessionProbe.lua(session, PinnedPlugins.PROGRAMS_REFUSED), "oil asked to start no program");
    }

    /**
     * open-me.txt open in an editor, saved, its line deleted in oil's float: once written and
     * confirmed, the file is gone from disk, and Eclipse closes its editor, asking nothing, as for
     * a saved file deleted outside Eclipse: once it checks the file, as the editor comes to the
     * front (the float closing gives it the focus back, and the test brings it to the front once
     * more), or as the workspace refreshes by itself, where that is on.
     */
    @Test
    void aSavedFileDeletedInTheFloatLosesItsEditor() throws Exception {
        ITextEditor open = openEditor("open-me.txt");
        StyledText openText = widgetOf(open);
        // Every dialog closed and named, until the editor's widget is gone with it.
        List<String> dialogs = closeModalDialogsWhile(openText);
        writeInTheFloat(openText, () -> typeSlowly(openText, "dd"), "open-me.txt", false, DELETE);
        waitUntil("open-me.txt deleted on disk", TIMEOUT_MS, () -> !Files.exists(file("open-me.txt")));
        if (editors().contains("open-me.txt")) {
            page().activate(editor);
            settle();
            page().activate(open);
        }
        waitUntil("open-me.txt's editor closed", TIMEOUT_MS, () -> {
            settle();
            return !editors().contains("open-me.txt");
        }, () -> "; editors " + editors() + ", dialogs " + dialogs);
        assertEquals(List.of(), dialogs, "Eclipse asked nothing");
        assertEquals("", SessionProbe.lua(session, PinnedPlugins.PROGRAMS_REFUSED), "oil asked to start no program");
    }

    /**
     * Brings {@code open} (open-me.txt's editor, its file gone from disk, the change {@code x}
     * made unsaved) to the front, unless Eclipse asked already, and waits until Eclipse's dialogs,
     * answered by {@code dialogs} ({@link #SAVE_AGAIN}), wrote the change to open-me.txt again.
     * Eclipse finds the file gone and asks (<em>File Not Accessible</em>); Save goes through Save
     * As, which proposes the old name, and, while the workspace still lists the file oil took
     * away, asks whether to replace it (<em>Question</em>; not asked once the workspace
     * refreshed).
     */
    private void saveAgain(ITextEditor open, Dialogs dialogs) {
        if (dialogs.seen().isEmpty()) {
            page().activate(editor);
            settle();
            page().activate(open);
        }
        waitUntil("open-me.txt saved again, with the change", TIMEOUT_MS, () -> {
            settle();
            return !open.isDirty() && "ne\ntwo\n".equals(onDisk("open-me.txt"));
        }, () -> "; dialogs " + dialogs.seen() + ", editors " + editors());
        List<String> seen = dialogs.seen();
        assertTrue(seen.equals(List.of("File Not Accessible", "Save As"))
                || seen.equals(List.of("File Not Accessible", "Save As", "Question")),
                "Eclipse's dialogs, each answered as expected: " + seen);
        assertEquals("ne\ntwo\n", documentOf(open).get(), "open-me.txt's editor, saved");
    }

    /**
     * Renames open-me.txt to moved.txt in oil's float over {@code text}, with keys typed on that
     * editor ({@code cf.}, then the new name), and waits for the move on disk.
     */
    private void renameInTheFloat(StyledText text) throws IOException {
        String before = writeInTheFloat(text, () -> {
            typeSlowly(text, "cf.");
            type(text, "moved.");
            escape(text);
        }, "moved.txt", true, MOVE);
        waitUntil("open-me.txt moved to moved.txt on disk", TIMEOUT_MS,
                () -> Files.exists(file("moved.txt")) && !Files.exists(file("open-me.txt")),
                () -> "; drawn " + drawn(text));
        assertEquals(before, Files.readString(file("moved.txt")), "the file's text, moved with it");
    }

    /**
     * Opens oil's float over {@code text}, puts Neovim's cursor on open-me.txt's line, types
     * {@code keys} on that editor, waits until the float shows {@code line} or no longer does
     * ({@code shown}) in Normal mode, then writes the listing ({@code :w}) and answers {@code y} to
     * oil's confirmation once it lists exactly {@code action}. Example: {@code dd}, then
     * {@code "open-me.txt"} no longer shown, then {@code "DELETE open-me.txt"}.
     *
     * @return open-me.txt's text on disk before the write
     */
    private String writeInTheFloat(StyledText text, Runnable keys, String line, boolean shown, String action)
            throws IOException {
        String before = Files.readString(file("open-me.txt"));
        openFloat(text);
        moveToLine("open-me");
        keys.run();
        waitUntil("the float's lines changed, Normal mode", TIMEOUT_MS, () -> {
            settle();
            boolean has = pluginFloats(text).stream().anyMatch(c -> rowsOf(c.grid()).contains(line));
            return has == shown && "n".equals(session.getMode());
        }, () -> "; drawn " + drawn(text));
        assertEquals(before, Files.readString(file("open-me.txt")), "nothing changed on disk before the write");
        runCommandLine(text, "w");
        // y carries out every action the confirmation lists: typed only to the one expected, once
        // its window is drawn with Neovim's cursor.
        waitUntil("oil's confirmation drawn with the keyboard, listing " + action, TIMEOUT_MS, () -> {
            settle();
            return pluginFloats(text).stream()
                    .anyMatch(c -> rowsOf(c.grid()).contains(action) && c.grid().cursorRow() >= 0)
                    && action.equals(SessionProbe.lua(session, PinnedPlugins.OIL_CONFIRMATION));
        }, () -> "; it lists '" + SessionProbe.lua(session, PinnedPlugins.OIL_CONFIRMATION) + "'; drawn "
                + drawn(text));
        type(text, "y");
        return before;
    }

    /** Opens oil's float on the project's folder over {@code text} and waits for it to list open-me.txt. */
    private void openFloat(StyledText text) {
        SessionProbe.lua(session, PinnedPlugins.openOilFloat(folder()) + " return 0");
        waitUntil("oil's float over the editor listing open-me.txt, with Neovim's cursor, the caret hidden",
                TIMEOUT_MS, () -> {
                    settle();
                    return pluginFloats(text).stream()
                            .anyMatch(c -> rowsOf(c.grid()).contains("open-me.txt") && c.grid().cursorRow() >= 0)
                            && text.getCaret() == null;
                }, () -> "; drawn " + drawn(text));
    }

    /** Puts Neovim's cursor on the float's line that holds {@code word}, a plain word of the test's. */
    private void moveToLine(String word) {
        assertEquals(true, SessionProbe.lua(session, "return vim.bo.filetype == 'oil' and vim.fn.search('" + word + "') > 0"),
                "the float has the keyboard and a line of " + word);
        settle();
    }

    private ITextEditor openEditor(String name) throws PartInitException {
        ITextEditor opened = (ITextEditor) IDE.openEditor(page(), project.getFile(name), TEXT_EDITOR, true);
        waitForAttach(opened, name);
        return opened;
    }

    /** The open editor whose input is named {@code name}, or {@code null}. */
    private static ITextEditor editorNamed(String name) {
        for (IEditorReference reference : page().getEditorReferences()) {
            if (reference.getEditor(false) instanceof ITextEditor text && name.equals(text.getEditorInput().getName())) {
                return text;
            }
        }
        return null;
    }

    private Path folder() {
        return project.getLocation().toFile().toPath();
    }

    private Path file(String name) {
        return folder().resolve(name);
    }

    /** The text of file {@code name} on disk, or {@code null} while there is none (or it is unreadable). */
    private String onDisk(String name) {
        try {
            return Files.readString(file(name));
        } catch (IOException e) {
            return null;
        }
    }

    /** The open editors' names, the one in front marked {@code *}: for a failure's message. */
    private static List<String> editors() {
        IWorkbenchPage page = page();
        List<String> names = new ArrayList<>();
        for (IEditorReference reference : page.getEditorReferences()) {
            IEditorPart part = reference.getEditor(false);
            names.add(reference.getName() + (part != null && part == page.getActiveEditor() ? "*" : ""));
        }
        return names;
    }

    private String drawn(StyledText text) {
        StringBuilder all = new StringBuilder();
        pluginFloats(text).forEach(c -> all.append(rowsOf(c.grid())).append("---\n"));
        return all.toString();
    }

    /**
     * Answers the modal dialogs that open while it runs, in order: {@code steps} pairs a dialog's
     * title with the button to press ({@code "Save As", "OK"}). A dialog that is not the next step
     * is closed and recorded, so the test fails on {@link #seen} instead of hanging. Each is
     * handled once; the next check is set before a button is pressed, as a button may open a
     * dialog of its own inside this check (Save As's OK asks whether to replace the file, a dialog
     * whose loop runs until it is answered). Where {@code closeModalDialogsWhile} closes every
     * dialog, this answers them. It keeps the error-dialog watch out of its way
     * ({@code UiTestSupport.dialogWatchStarted}) until stopped. UI thread.
     */
    private static final class Dialogs {
        private final List<String> steps;
        private final List<String> seen = new ArrayList<>();
        private final Set<Shell> handled = new HashSet<>();
        private final Runnable ended = UiTestSupport.dialogWatchStarted();
        private boolean running = true;

        Dialogs(String... steps) {
            this.steps = List.of(steps);
            Display display = Display.getCurrent();
            Runnable check = new Runnable() {
                @Override
                public void run() {
                    if (!running) {
                        return;
                    }
                    display.timerExec(100, this);
                    for (Shell shell : display.getShells()) {
                        if (!shell.isDisposed() && shell.isVisible() && UiTestSupport.isModal(shell)
                                && handled.add(shell)) {
                            answer(shell);
                        }
                    }
                }
            };
            display.timerExec(100, check);
        }

        private void answer(Shell shell) {
            int step = 2 * seen.size();
            seen.add(shell.getText());
            Button button = step + 1 < steps.size() && steps.get(step).equals(shell.getText())
                    ? button(shell, steps.get(step + 1)) : null;
            if (button != null) {
                button.notifyListeners(SWT.Selection, new Event());
            } else {
                shell.close();
            }
        }

        private static Button button(Composite parent, String label) {
            for (Control child : parent.getChildren()) {
                if (child instanceof Button b && label.equals(b.getText().replace("&", ""))) {
                    return b;
                }
                if (child instanceof Composite c) {
                    Button found = button(c, label);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        /** The titles of the dialogs seen so far, in order. */
        List<String> seen() {
            return List.copyOf(seen);
        }

        void stop() {
            running = false;
            ended.run();
        }
    }
}
