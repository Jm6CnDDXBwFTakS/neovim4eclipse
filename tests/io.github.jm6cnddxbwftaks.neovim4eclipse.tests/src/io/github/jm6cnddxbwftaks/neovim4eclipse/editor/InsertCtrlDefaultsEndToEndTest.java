// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.ctrlKey;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;

/**
 * The {@code Ctrl} letters that are Vim's in insert mode by default on every system
 * ({@code VimCtrlKeys.INSERT_BY_DEFAULT}; docs/KEYBOARD.md, "Insert mode"), pressed in an insert
 * Eclipse types, with the preferences as they come: Backspace, Tab and Enter by their Ctrl names
 * ({@code Ctrl+H}, {@code Ctrl+I}, {@code Ctrl+J}, {@code Ctrl+M}), the character above and below
 * ({@code Ctrl+Y}, {@code Ctrl+E}), the undo break ({@code Ctrl+G u}) and a {@code Ctrl+X}
 * completion.
 *
 * <p>What each test shows: the key reached Neovim through the workbench's key bindings, not
 * Eclipse's own command for it (Search, Correct Indentation, Incremental Find, Maximize, Redo,
 * Quick Switch Editor, Cut); Neovim had the text Eclipse typed before it (the sync point), so
 * the key acted on that text; and Eclipse types again afterwards, with both sides holding the
 * same lines. The letters that were defaults before ({@code Ctrl+W}, {@code Ctrl+U} and the
 * others) are in {@code EditorEndToEndTest}.
 */
class InsertCtrlDefaultsEndToEndTest extends EndToEndTestBase {

    private IPreferenceStore store;

    @BeforeEach
    void theDefaults() {
        store = Activator.getDefault().getPreferenceStore();
        for (char letter : "xyeghijm".toCharArray()) {
            store.setToDefault(PreferenceConstants.ctrlKeyInsert(letter));
            assertTrue(store.getBoolean(PreferenceConstants.ctrlKeyInsert(letter)), "Ctrl+" + letter + " is Neovim's in insert mode by default");
        }
    }

    @AfterEach
    void leaveTheInsert() {
        if (session.isTypingMode()) {
            escape(widget);
            waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        }
        settle();
    }

    private List<?> neovimLines() {
        return (List<?>) SessionProbe.lua(session, "return vim.api.nvim_buf_get_lines(0, 0, -1, false)");
    }

    /** {@code text} in the document, then {@code keys} in normal mode, the last of which starts an insert Eclipse types. */
    private void insertWith(String text, String keys) {
        document.set(text);
        settle();
        typeSlowly(keys);
        waitUntil("Eclipse typing", TIMEOUT_MS, session::isEclipseTypingReady);
    }

    /**
     * After Esc: the editor and Neovim hold these lines. What Eclipse types after a Ctrl key
     * Neovim learns only at the next such key or at Esc, so the text typed last is compared here.
     */
    private void afterEscBothHave(String... lines) {
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        settle();
        expectText(String.join("\n", lines) + "\n");
        assertEquals(List.of(lines), neovimLines(), "Neovim has the same text");
    }

    /** Right after a Ctrl key: Neovim has the lines the editor shows, and Eclipse types again. */
    private void bothHave(String... lines) {
        expectText(String.join("\n", lines) + "\n");
        waitUntil("Eclipse types again", TIMEOUT_MS, session::isEclipseTypingReady);
        settle();
        expectText(String.join("\n", lines) + "\n");
        assertEquals(List.of(lines), neovimLines(), "Neovim has the same text");
        assertTrue(widget.isFocusControl(), "no dialog or other part took the focus");
    }

    /** Ctrl+H deletes the character before the cursor, one Eclipse typed; not Eclipse's Search dialog. */
    @Test
    void ctrlHIsBackspace() {
        insertWith("first\n", "o");
        type(widget, "abc");
        ctrlKey(widget, 'h');
        bothHave("first", "ab");
        type(widget, "x");
        afterEscBothHave("first", "abx");
    }

    /** Ctrl+J and Ctrl+M start a new line, as Enter; not Incremental Find, and the editor is not maximized. */
    @Test
    void ctrlJAndCtrlMAreEnter() {
        Object page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        insertWith("first\n", "o");
        type(widget, "ab");
        ctrlKey(widget, 'j');
        bothHave("first", "ab", "");
        type(widget, "cd");
        ctrlKey(widget, 'm');
        bothHave("first", "ab", "cd", "");
        type(widget, "e");
        expectText("first\nab\ncd\ne\n");
        assertSame(page, PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage());
        assertTrue(!PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
                .isPageZoomed(), "Ctrl+M did not maximize the editor");
        afterEscBothHave("first", "ab", "cd", "e");
    }

    /** Ctrl+I inserts a Tab (or its spaces, by Neovim's options) after what Eclipse typed. */
    @Test
    void ctrlIIsTab() {
        insertWith("first\n", "o");
        type(widget, "ab");
        ctrlKey(widget, 'i');
        waitUntil("white space after ab", TIMEOUT_MS, () -> document.get().matches("first\nab[\t ]+\n"), document::get);
        waitUntil("Eclipse types again", TIMEOUT_MS, session::isEclipseTypingReady);
        type(widget, "c");
        waitUntil("c after the white space", TIMEOUT_MS, () -> document.get().matches("first\nab[\t ]+c\n"), document::get);
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        settle();
        assertEquals(List.of(document.get().split("\n")), neovimLines(), "Neovim has the same text");
    }

    /** Ctrl+Y copies the character above the cursor, Ctrl+E the one below; not Redo, not Quick Switch Editor. */
    @Test
    void ctrlYAndCtrlECopyTheCharacterAboveAndBelow() {
        insertWith("hello\nworld\n", "o"); // a new line between the two
        ctrlKey(widget, 'y');
        ctrlKey(widget, 'y');
        bothHave("hello", "he", "world");
        type(widget, "-");
        ctrlKey(widget, 'e');
        bothHave("hello", "he-l", "world");
    }

    /** Ctrl+G u starts a new undo step in the middle of an insert: u then takes back only what came after it. */
    @Test
    void ctrlGUBreaksTheUndoStep() {
        insertWith("first\n", "o");
        type(widget, "one ");
        ctrlKey(widget, 'g');
        type(widget, "u");
        bothHave("first", "one ");
        type(widget, "two");
        escape(widget);
        waitUntil("normal mode", TIMEOUT_MS, () -> !session.isTypingMode());
        settle();
        expectText("first\none two\n");
        typeSlowly("u");
        expectText("first\none \n");
        assertEquals(List.of("first", "one "), neovimLines());
    }

    /** Ctrl+X Ctrl+L completes the whole line from what Eclipse typed; Ctrl+X is not Cut. */
    @Test
    void ctrlXCtrlLCompletesTheLine() {
        insertWith("alpha beta\n", "o");
        type(widget, "al");
        ctrlKey(widget, 'x');
        ctrlKey(widget, 'l');
        expectText("alpha beta\nalpha beta\n");
        ctrlKey(widget, 'y'); // keeps the match; the completion is over
        bothHave("alpha beta", "alpha beta");
    }
}
