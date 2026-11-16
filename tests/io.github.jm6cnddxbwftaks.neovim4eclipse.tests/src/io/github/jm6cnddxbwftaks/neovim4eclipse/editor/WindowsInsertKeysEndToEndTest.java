// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.runUntilIdle;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.keys.IBindingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.VimCtrlKeys;

/**
 * Two keys that only Windows needs looked after, now that {@code Ctrl+Y} and {@code Ctrl+J} are
 * Neovim's in an insert by default ({@code VimCtrlKeys.INSERT_BY_DEFAULT}; docs/KEYBOARD.md,
 * "Insert mode").
 *
 * <ul>
 * <li><b>Redo on Ctrl+Shift+Z.</b> Eclipse's Redo is {@code Ctrl+Y} on Windows only (on Linux it is
 * {@code Ctrl+Shift+Z}, on macOS {@code Cmd+Shift+Z}). With {@code Ctrl+Y} taken by Neovim while
 * typing, the plug-in binds {@code M1+M2+Z} to Redo on {@code win32}, in its own context
 * (plugin.xml; {@code PluginXmlConsistencyTest} checks the XML). Here the workbench resolves the
 * key to Redo with no conflict, and pressing it redoes what {@code Ctrl+Z} undid, in an insert and
 * in normal mode.</li>
 * <li><b>Ctrl+Enter is not Ctrl+J.</b> {@code Ctrl+J}'s character is LF (10), and Windows itself
 * gives {@code Ctrl+Enter} that character too. The workbench reads a control character that is not
 * the key's own as its letter (JFace's {@code SWTKeySupport}: 10 with Ctrl is {@code Ctrl+J}), so a
 * {@code Ctrl+Enter} that arrived with LF would run the plug-in's {@code Ctrl-J} command. It does
 * not arrive so: SWT reports {@code Ctrl+Enter} with the character CR (13), the Enter key's own.
 * Here the real key is pressed through the operating system and its event checked, and the same
 * event sent to the widget runs no {@code Ctrl-J}.</li>
 * </ul>
 *
 * <p>Windows only: the binding exists only there, and the question about {@code Ctrl+Enter} too.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsInsertKeysEndToEndTest extends EndToEndTestBase {

    private static final String REDO = "org.eclipse.ui.edit.redo";
    private static final String CTRL_J = VimCtrlKeys.commandId('j');
    private static final String CTRL_M = VimCtrlKeys.commandId('m');

    @AfterEach
    void leaveTheInsert() {
        if (session.isTypingMode()) {
            escape(widget);
            waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        }
        settle();
    }

    /** Neovim's lines, to compare with the editor's after Esc. */
    private List<?> neovimLines() {
        return (List<?>) SessionProbe.lua(session, "return vim.api.nvim_buf_get_lines(0, 0, -1, false)");
    }

    /** Neovim's buffer as the document spells it: each line ended by a line feed, an empty last line too. */
    private String neovimText() {
        StringBuilder text = new StringBuilder();
        for (Object line : neovimLines()) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    /** {@code o} on the last line: an insert Eclipse types, on a new empty line. */
    private void insertOnANewLine() {
        document.set("first\n");
        settle();
        typeSlowly("o");
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
    }

    /** After Esc: the editor and Neovim hold the same lines. */
    private void afterEscBothHaveTheSameText() {
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        settle();
        assertEquals(document.get(), neovimText(), "Neovim has the editor's text");
    }

    /**
     * Ctrl+Z through the workbench's key bindings (Undo), then the UI thread until it is idle.
     * Why the wait: Eclipse turns its Redo action on in a task it queues after the undo
     * ({@code OperationHistoryActionHandler} updates through {@code asyncExec}), and a Redo asked
     * for before that task ran does nothing. A person is never that fast; a test is.
     */
    private void ctrlZ() {
        UiTestSupport.m1Key(widget, 'z');
        runUntilIdle(TIMEOUT_MS);
    }

    /**
     * Ctrl+Shift+Z as Windows reports it: key code {@code z}, character 26 (Ctrl+Z's control
     * character; Shift does not change it), Ctrl and Shift held. Sent to the widget, so the
     * workbench's key bindings see it as for {@link UiTestSupport#m1Key}.
     */
    private void ctrlShiftZ() {
        Event event = new Event();
        event.keyCode = 'z';
        event.character = (char) ('z' - 'a' + 1);
        event.stateMask = SWT.CTRL | SWT.SHIFT;
        event.doit = true; // as for a real key press; a new Event defaults to false (key ignored)
        widget.notifyListeners(SWT.KeyDown, event);
    }

    /**
     * While Neovim owns the editor's keys, the workbench resolves Ctrl+Shift+Z to Redo, in normal
     * mode and in an insert, and no other binding claims the key: a conflict would leave the key
     * running nothing, and the Keys preference page would mark it.
     */
    @Test
    void ctrlShiftZIsRedoWithoutAConflict() throws Exception {
        IBindingService bindings = PlatformUI.getWorkbench().getService(IBindingService.class);
        KeySequence key = KeySequence.getInstance("M1+M2+Z");
        Binding binding = bindings.getPerfectMatch(key);
        assertNotNull(binding, "Ctrl+Shift+Z is bound");
        assertEquals(REDO, binding.getParameterizedCommand().getId());
        Collection<?> conflicts = bindings.getConflictsFor(key);
        assertTrue(conflicts == null || conflicts.isEmpty(), "no conflict for Ctrl+Shift+Z: " + conflicts);

        insertOnANewLine();
        assertEquals(REDO, KeyBindings.commandIdFor("M1+M2+Z"), "still Redo in an insert");
        conflicts = bindings.getConflictsFor(key);
        assertTrue(conflicts == null || conflicts.isEmpty(), "no conflict in an insert: " + conflicts);
    }

    /**
     * In an insert Eclipse types: Ctrl+Z takes typed text away, Ctrl+Shift+Z brings it back through
     * Eclipse's Redo, and the insert goes on (the status bar still says INSERT). After Esc Neovim
     * has the redone text.
     */
    @Test
    void ctrlShiftZRedoesInAnInsert() {
        insertOnANewLine();
        type(widget, "one two");
        expectText("first\none two\n");
        try (CommandLog log = new CommandLog()) {
            ctrlZ();
            expectText("first\n\n");
            ctrlShiftZ();
            expectText("first\none two\n");
            assertTrue(log.run.contains(REDO), "Redo ran: " + log.run);
        }
        assertTrue(session.isTypingMode(), "still in the insert after Undo and Redo");
        afterEscBothHaveTheSameText();
        expectText("first\none two\n");
    }

    /**
     * The same in an insert with real key presses through the operating system
     * ({@code Display.post}): Ctrl+Z, then Ctrl+Shift+Z, as Windows and SWT deliver the two chords,
     * not as events the test builds. Shows that the real Ctrl+Shift+Z reaches the binding (its
     * character and key code are the ones the workbench reads as {@code M1+M2+Z}).
     */
    @Test
    void ctrlShiftZFromTheKeyboardRedoesInAnInsert() {
        insertOnANewLine();
        type(widget, "one two");
        expectText("first\none two\n");
        Display display = widget.getDisplay();
        try (CommandLog log = new CommandLog()) {
            theEditorHasTheKeyboard();
            try {
                post(display, SWT.KeyDown, SWT.CTRL);
                postLetter(display, 'z');
            } finally {
                post(display, SWT.KeyUp, SWT.CTRL); // never leave a modifier held down for the tests after this one
            }
            expectText("first\n\n");
            runUntilIdle(TIMEOUT_MS); // Eclipse turns Redo on in a queued task (see ctrlZ)
            theEditorHasTheKeyboard();
            try {
                post(display, SWT.KeyDown, SWT.CTRL);
                post(display, SWT.KeyDown, SWT.SHIFT);
                postLetter(display, 'z');
            } finally {
                post(display, SWT.KeyUp, SWT.SHIFT);
                post(display, SWT.KeyUp, SWT.CTRL);
            }
            expectText("first\none two\n");
            assertTrue(log.run.contains(REDO), "Redo ran: " + log.run);
        }
        assertTrue(session.isTypingMode(), "still in the insert after Undo and Redo");
        afterEscBothHaveTheSameText();
        expectText("first\none two\n");
    }

    /**
     * In normal mode: Ctrl+Z undoes a Neovim change through Eclipse's Undo, Ctrl+Shift+Z redoes it
     * through Eclipse's Redo (Vim's own redo is {@code Ctrl+R}), and Neovim gets the redone text.
     */
    @Test
    void ctrlShiftZRedoesInNormalMode() {
        editor.doSave(null);
        settle();
        typeSlowly("2Gdd");
        expectText("alpha\ngamma\n");
        try (CommandLog log = new CommandLog()) {
            ctrlZ();
            settle();
            expectText("alpha\nbeta\ngamma\n");
            ctrlShiftZ();
            settle();
            expectText("alpha\ngamma\n");
            assertTrue(log.run.contains(REDO), "Redo ran: " + log.run);
        }
        assertFalse(session.isTypingMode(), "still in normal mode");
        waitUntil("Neovim has the redone text", TIMEOUT_MS, () -> List.of("alpha", "gamma").equals(neovimLines()),
                () -> String.valueOf(neovimLines()));
    }

    /**
     * Ctrl+Enter in an insert, sent to the widget as SWT reports the key on Windows: character CR
     * (13) and key code Enter ({@code SWT.CR}), Ctrl held ({@link #ctrlEnterFromTheKeyboardIsNotCtrlJ}
     * checks that this is the event). Neither the plug-in's Ctrl-J command nor its Ctrl-M command
     * (13 is Ctrl+M's character) runs for it; whatever else the key does, the editor and Neovim
     * agree on the text afterwards.
     */
    @Test
    void ctrlEnterSentToTheWidgetIsNotCtrlJ() {
        insertOnANewLine();
        type(widget, "ab");
        expectText("first\nab\n");
        try (CommandLog log = new CommandLog()) {
            Event event = new Event();
            event.keyCode = SWT.CR;
            event.character = SWT.CR;
            event.stateMask = SWT.CTRL;
            event.doit = true; // as for a real key press; a new Event defaults to false (key ignored)
            widget.notifyListeners(SWT.KeyDown, event); // the key bindings run before this returns
            runUntilIdle(TIMEOUT_MS);
            settle();
            assertNoCtrlLetterRan(log);
        }
        assertTrue(session.isTypingMode(), "still in the insert");
        afterEscBothHaveTheSameText();
    }

    /**
     * Ctrl+Enter as real key presses through the operating system ({@code Display.post}), so the
     * event is the one Windows and SWT make, not one the test builds. A key filter records it; the
     * test waits until the Enter key has arrived. Its character is CR, not the LF Windows gives the
     * chord: with LF the workbench would read it as Ctrl+J (see the class comment), so a change
     * here means Ctrl+Enter has become Ctrl+J. And no Ctrl-J command runs.
     */
    @Test
    void ctrlEnterFromTheKeyboardIsNotCtrlJ() {
        insertOnANewLine();
        type(widget, "ab");
        expectText("first\nab\n");
        Display display = widget.getDisplay();
        List<Event> enters = new CopyOnWriteArrayList<>();
        Listener filter = e -> {
            if (e.keyCode == SWT.CR || e.keyCode == SWT.LF) {
                Event copy = new Event();
                copy.keyCode = e.keyCode;
                copy.character = e.character;
                copy.stateMask = e.stateMask;
                enters.add(copy);
            }
        };
        display.addFilter(SWT.KeyDown, filter);
        try (CommandLog log = new CommandLog()) {
            theEditorHasTheKeyboard();
            try {
                post(display, SWT.KeyDown, SWT.CTRL);
                post(display, SWT.KeyDown, SWT.CR);
                post(display, SWT.KeyUp, SWT.CR);
            } finally {
                post(display, SWT.KeyUp, SWT.CTRL); // never leave Ctrl held down for the tests after this one
            }
            waitUntil("the Enter key to arrive", TIMEOUT_MS, () -> !enters.isEmpty());
            runUntilIdle(TIMEOUT_MS);
            settle();
            Event enter = enters.get(0);
            assertEquals(SWT.CTRL, enter.stateMask & SWT.MODIFIER_MASK, "Ctrl was held");
            assertEquals(SWT.CR, enter.keyCode, "the Enter key");
            assertEquals(SWT.CR, enter.character, "SWT reports Ctrl+Enter with CR; with LF it would be read as Ctrl+J");
            assertNoCtrlLetterRan(log);
        } finally {
            display.removeFilter(SWT.KeyDown, filter);
        }
        assertTrue(session.isTypingMode(), "still in the insert");
        afterEscBothHaveTheSameText();
    }

    private static void assertNoCtrlLetterRan(CommandLog log) {
        assertFalse(log.run.contains(CTRL_J), "Ctrl+Enter ran the Ctrl-J command: " + log.run);
        assertFalse(log.run.contains(CTRL_M), "Ctrl+Enter ran the Ctrl-M command: " + log.run);
    }

    /**
     * Before real key presses: the test's window is the active one and the editor has the keyboard
     * ({@link UiTestSupport#requireKeyboardFocus}). Why: a key posted through the operating system
     * goes to whichever window is in front. Had the developer clicked into another program since
     * the test began, Ctrl+Z or Ctrl+Enter would land there.
     */
    private void theEditorHasTheKeyboard() {
        UiTestSupport.requireKeyboardFocus(widget, TIMEOUT_MS);
    }

    /** One key event through the operating system; {@code keyCode} an SWT key code such as {@code SWT.CTRL}. */
    private static void post(Display display, int type, int keyCode) {
        Event event = new Event();
        event.type = type;
        event.keyCode = keyCode;
        assertTrue(display.post(event), "the system took the key event");
    }

    /** A letter key pressed and let go through the operating system (a letter is posted by its character). */
    private static void postLetter(Display display, char letter) {
        for (int type : new int[] { SWT.KeyDown, SWT.KeyUp }) {
            Event event = new Event();
            event.type = type;
            event.character = letter;
            assertTrue(display.post(event), "the system took the key event");
        }
    }
}
