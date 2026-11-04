// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.MarkedTextInput.Key;

/**
 * The macOS input-method rules of the {@code :} box. Each sequence is what SWT's macOS port sent
 * with Japanese Romaji input (logged on macOS, plug-in 0.2.1): the edits (verify events) and key
 * presses in their order, with their key codes, characters, text and offsets. The box's line starts
 * as {@code :}, so marked text starts at offset 1.
 */
class MarkedTextInputTest {

    // SWT's key codes, as numbers: util/ has no SWT.
    private static final int ENTER = 13;
    private static final int BACKSPACE = 8;
    private static final int ESC = 27;
    private static final int SPACE = 32;
    private static final int ARROW_UP = (1 << 24) + 1;
    private static final int ARROW_DOWN = (1 << 24) + 2;
    private static final int COMMAND = 1 << 22;

    private final MarkedTextInput input = new MarkedTextInput();
    /** What reached Neovim: pastes as they are, keys as {@code key:x}. */
    private final List<String> sent = new ArrayList<>();

    /** An edit the input method or the platform makes. */
    private MarkedTextInput.Edit edit(int keyCode, String text, int start, int end) {
        return edit(keyCode, false, text, start, end, false);
    }

    private MarkedTextInput.Edit edit(int keyCode, boolean commandOrCtrl, String text, int start, int end,
            boolean programmatic) {
        MarkedTextInput.Edit edit = input.edit(keyCode, commandOrCtrl, text, start, end, programmatic);
        sent.addAll(edit.pastes());
        return edit;
    }

    private Key key(int keyCode, char character) {
        Key key = input.key(keyCode, character, false);
        if (key == Key.SEND) {
            sent.add("key:" + character);
        }
        return key;
    }

    /** One Romaji key that changes the marked text: its edit, then its own press. */
    private void marked(char letter, String markedText, int start, int end) {
        assertTrue(edit(letter, markedText, start, end).allow(), "marked text is shown in the box");
        assertEquals(Key.PLATFORM, key(letter, letter), "its key press is the input method's");
    }

    @Test
    void kaThenEnterSendsOnlyTheCommittedKana() {
        marked('k', "k", 1, 1);
        assertTrue(input.composing());
        marked('a', "か", 1, 2);
        assertEquals(List.of(), sent, "nothing marked reaches Neovim");

        assertEquals(Key.PLATFORM, key(ENTER, 'か'), "the commit's press is left to the platform");
        MarkedTextInput.Edit commit = edit(ENTER, "か", 1, 2);
        assertFalse(commit.allow());
        assertEquals(List.of("か"), sent);
        assertFalse(input.composing());
    }

    /** Logged: konnichiwa, Enter. The commit is one press per kana, then one edit with them all. */
    @Test
    void konnichiwaIsCommittedWholeAndOnce() {
        marked('k', "k", 1, 1);
        marked('o', "こ", 1, 2);
        marked('n', "こn", 1, 2);
        marked('n', "こん", 1, 3);
        marked('i', "こんい", 1, 3);
        marked('c', "こんいc", 1, 4);
        marked('h', "こんいch", 1, 5);
        marked('i', "こんいち", 1, 6);
        marked('w', "こんいちw", 1, 5);
        marked('a', "こんにちわ", 1, 6);

        assertEquals(Key.PLATFORM, key(ENTER, 'こ'));
        for (char kana : "んにちわ".toCharArray()) {
            assertEquals(Key.PLATFORM, key(0, kana));
        }
        edit(ENTER, "こんにちわ", 1, 6);
        assertEquals(List.of("こんにちわ"), sent);
    }

    /**
     * Logged: konnichiwa, Space and the arrows through the candidates, an Enter that only closed the
     * candidate list, then the Enter that kept 今日わ. Neither the candidates nor that first Enter
     * reach Neovim; the line is not submitted.
     */
    @Test
    void conversionCandidatesAndTheListsEnterStayWithTheInputMethod() {
        marked('k', "k", 1, 1);
        marked('o', "こ", 1, 2);
        marked('n', "こん", 1, 2);
        marked('n', "こんにちわ", 1, 3); // shortened: the rest as in the test above
        assertTrue(edit(SPACE, "こんにちわ", 1, 6).allow());
        assertEquals(Key.PLATFORM, key(SPACE, ' '));
        for (String candidate : List.of("こんいちわ", "コンイチワ", "ｺﾝｲﾁﾜ", "ｋｏｎｎｉｃｈｉｗａ", "konnichiwa")) {
            assertTrue(edit(ARROW_UP, candidate, 1, 6).allow());
            assertEquals(Key.PLATFORM, key(ARROW_UP, '\0'));
        }
        assertTrue(edit(ARROW_DOWN, "今日わ", 1, 11).allow());
        assertEquals(Key.PLATFORM, key(ARROW_DOWN, '\0'));

        assertEquals(Key.PLATFORM, key(ENTER, '\r'), "closes the candidate list: not Neovim's Enter");
        assertTrue(input.composing(), "and not a commit");

        assertEquals(Key.PLATFORM, key(ENTER, '今'));
        assertEquals(Key.PLATFORM, key(0, '日'));
        assertEquals(Key.PLATFORM, key(0, 'わ'));
        edit(ENTER, "今日わ", 1, 4);
        assertEquals(List.of("今日わ"), sent);
    }

    /** A candidate in plain letters is committed the same way: its first press is not Enter's \r. */
    @Test
    void aCommitInPlainLettersIsACommitToo() {
        marked('k', "k", 1, 1);
        edit(ARROW_UP, "konnichiwa", 1, 2);
        key(ARROW_UP, '\0');
        assertEquals(Key.PLATFORM, key(ENTER, 'k'));
        assertEquals(Key.PLATFORM, key(0, 'o'));
        edit(ENTER, "konnichiwa", 1, 11);
        assertEquals(List.of("konnichiwa"), sent);
    }

    /** Logged: ka, Backspace twice. The kana goes, its Backspace does not delete Neovim's ":". */
    @Test
    void backspaceOnTheLastMarkedKanaEndsTheCompositionAndIsNotSent() {
        marked('k', "k", 1, 1);
        marked('a', "か", 1, 2);
        assertTrue(edit(BACKSPACE, "", 1, 2).allow());
        assertFalse(input.composing());
        assertEquals(Key.PLATFORM, key(BACKSPACE, '\b'));
        assertEquals(Key.SEND, key(BACKSPACE, '\b'), "the next Backspace is Neovim's again");
        assertEquals(List.of("key:\b"), sent);
    }

    /** Logged: ka, Esc (macOS keeps か), Esc (it goes), Esc (Neovim's: the line closes). */
    @Test
    void escWhileComposingIsTheInputMethodsUntilTheCompositionIsGone() {
        marked('k', "k", 1, 1);
        marked('a', "か", 1, 2);

        assertTrue(edit(ESC, "か", 1, 2).allow());
        assertEquals(Key.PLATFORM, key(ESC, '\u001b'));
        assertTrue(input.composing());

        assertTrue(edit(ESC, " ", 1, 2).allow());
        assertTrue(edit(ESC, "", 1, 2).allow());
        assertFalse(input.composing());
        assertEquals(Key.PLATFORM, key(ESC, '\u001b'), "its Esc does not abandon the line");
        assertEquals(List.of(), sent);

        assertEquals(Key.SEND, key(ESC, '\u001b'));
    }

    /**
     * Logged: kaka, Cmd+V with "src" on the clipboard. The paste is an edit without a key code beside
     * the marked text; as in macOS's own fields, the marked text is kept first. The box's own text
     * change that follows carries Cmd+V's key code, and is the box's, not marked text.
     */
    @Test
    void aPasteWhileComposingKeepsTheMarkedTextFirst() {
        marked('k', "k", 1, 1);
        marked('a', "か", 1, 2);
        marked('k', "かk", 1, 2);
        marked('a', "かか", 1, 3);
        assertEquals(Key.PLATFORM, key(COMMAND, '\0'));

        assertFalse(edit(0, "src", 3, 3).allow());
        assertEquals(List.of("かか", "src"), sent);
        assertFalse(input.composing());

        assertTrue(edit('v', true, "", 1, 3, true).allow(), "the box setting Neovim's \":かかsrc\"");
        assertFalse(input.composing());
        assertEquals(List.of("かか", "src"), sent);
    }

    /**
     * Logged: ka, a click in the editor. macOS takes the marked text away, then inserts it as kept
     * text, which is sent: deliberately, as every macOS field keeps it, and in Ex mode (where the
     * box stays open) the user's typing is not lost.
     */
    @Test
    void focusLostWhileComposingSendsWhatMacosKeeps() {
        marked('k', "k", 4, 4); // the line was ":src"
        marked('a', "か", 4, 5);
        assertTrue(edit(0, "", 4, 5).allow(), "the marked text taken away");
        assertFalse(input.composing());
        assertEquals(List.of(), sent);

        edit(0, "か", 4, 4);
        assertEquals(List.of("か"), sent);
    }

    /** Logged: Cmd+Tab away and back. Only the Cmd presses arrive; the marked text stays. */
    @Test
    void cmdTabLeavesTheComposition() {
        marked('k', "k", 1, 1);
        marked('a', "か", 1, 2);
        assertEquals(Key.PLATFORM, key(COMMAND, '\0'));
        assertEquals(Key.PLATFORM, key(COMMAND, '\0'));
        assertTrue(input.composing());
        assertEquals(List.of(), sent);
    }

    /** Logged with the input method off (Latin input): presses only, no edits. */
    @Test
    void latinInputIsSentKeyByKey() {
        for (char c : "ka".toCharArray()) {
            assertEquals(Key.SEND, key(c, c));
        }
        assertEquals(List.of("key:k", "key:a"), sent);
    }

    /** Logged: Space outside a composition in Japanese input is a press of U+3000, Neovim's. */
    @Test
    void aWideSpaceOutsideACompositionIsSent() {
        assertEquals(Key.SEND, key(SPACE, '　'));
    }

    /** A paste without a composition is sent as before, on every platform. */
    @Test
    void aPasteWithoutACompositionIsSent() {
        assertFalse(edit(0, "src", 1, 1).allow());
        assertEquals(List.of("src"), sent);
    }

    /** Logged: macOS committing again while the box set Neovim's text sent a second こ. */
    @Test
    void keysWhileTheBoxSetsTheTextAreCancelled() {
        assertEquals(Key.CANCEL, input.key(ENTER, 'こ', true));
        assertTrue(input.edit(ENTER, false, "", 1, 6, true).allow());
        assertFalse(input.composing());
    }

    /**
     * Vim's Ctrl keys come from Eclipse's key bindings, before the field: while composing they would
     * change Neovim's line behind the box (Ctrl+U clearing ":abc" under "abcか"), so none is sent.
     */
    @Test
    void boundCtrlKeysAreNotSentWhileComposing() {
        assertTrue(input.sendsBoundKey());
        marked('k', "k", 4, 4); // the line is ":abc"
        marked('a', "か", 4, 5);
        assertFalse(input.sendsBoundKey());
        key(ENTER, 'か');
        edit(ENTER, "か", 4, 5); // the commit
        assertTrue(input.sendsBoundKey(), "after the commit they are Neovim's again");
    }

    /**
     * A press taken as a commit's (a character not its key's own) with no edit of the whole text
     * after it: the next marked text is marked text, not committed text, and is not sent.
     */
    @Test
    void aCommitWithoutItsEditDoesNotSendTheNextMarkedText() {
        marked('k', "k", 1, 1);
        marked('a', "か", 1, 2);
        assertEquals(Key.PLATFORM, key(ENTER, 'か'));

        assertTrue(edit('k', "k", 2, 2).allow(), "beside the marked text: not the commit's edit");
        assertTrue(input.composing());
        assertEquals(List.of(), sent);
        assertEquals(Key.PLATFORM, key('k', 'k'));
    }

    /**
     * The key that made the last marked text keeps its claim on its press even when that edit ended
     * the composition (Backspace on the last kana, the second Esc): its press follows, and sending it
     * would delete Neovim's ":" or close the line.
     */
    @Test
    void theKeyThatEndedACompositionIsNotSentEither() {
        marked('k', "k", 1, 1);
        edit(ESC, "", 1, 2);
        assertFalse(input.composing());
        assertEquals(Key.PLATFORM, key(ESC, '\u001b'));
        assertEquals(List.of(), sent);
    }

    /**
     * Logged with Latin input: {@code :echo "}, Option+E, E. macOS shows the accent as marked text
     * (an edit with E's key code, Option held), then E commits: a press carrying é, which is not
     * E's own character, and one edit with é over the accent. é is sent once; the next keys are
     * Neovim's.
     */
    @Test
    void anAccentTypedAsTwoKeysIsCommittedOnce() {
        assertTrue(edit('e', "´", 7, 7).allow(), "the accent waits, as marked text");
        assertEquals(Key.PLATFORM, key('e', '\0'), "Option+E's own press");
        assertEquals(Key.PLATFORM, key('e', 'é'), "E commits");
        assertFalse(edit('e', "é", 7, 8).allow());
        assertEquals(List.of("é"), sent);
        assertFalse(input.composing());
        assertEquals(Key.SEND, key('\'', '"'));
        assertEquals(Key.SEND, key(ENTER, '\r'), "Enter runs the line");
    }

    /**
     * Logged with Japanese input: kon, Tab to pick the prediction こん, Enter (closes the list),
     * Enter (commits), Enter (runs the line). Tab's pick is marked text like any other, carrying
     * Tab's key code; the box keeps Tab's press and traversal from Neovim while composing.
     */
    @Test
    void tabPicksAPredictionAsMarkedText() {
        marked('k', "k", 1, 1);
        marked('o', "こ", 1, 2);
        assertTrue(edit('n', "こn", 1, 2).allow());
        assertTrue(edit('\t', "こん", 1, 3).allow(), "Tab's pick");
        assertTrue(input.composing());

        assertEquals(Key.PLATFORM, key(ENTER, '\r'), "closes the list");
        assertEquals(Key.PLATFORM, key(ENTER, 'こ'));
        assertEquals(Key.PLATFORM, key(0, 'ん'));
        edit(ENTER, "こん", 1, 3);
        assertEquals(List.of("こん"), sent);
        assertEquals(Key.SEND, key(ENTER, '\r'), "runs the line");
    }

    @Test
    void resetForgetsAComposition() {
        marked('k', "k", 1, 1);
        input.reset();
        assertFalse(input.composing());
        assertEquals(Key.SEND, key('a', 'a'));
    }
}
