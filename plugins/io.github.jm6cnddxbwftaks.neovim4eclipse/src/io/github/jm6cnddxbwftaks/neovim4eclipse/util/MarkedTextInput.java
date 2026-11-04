// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;

/**
 * How the {@code :} box on macOS tells an input method's text in the making from text to send to
 * Neovim. The box ({@code editor/CommandLineBox}) feeds it every edit and every key press of its text
 * field, and does what it answers. Only SWT's macOS port uses it; on Linux and Windows the box
 * works as before (below, "Why only macOS").
 *
 * <h2>Marked text</h2>
 * An input method (IME) builds text from several keys: with Japanese Romaji input, {@code k} then
 * {@code a} make {@code か}, Space offers conversions ({@code 蚊}, {@code 可}), Enter keeps one. Until
 * then the text is <em>marked</em>: macOS shows it underlined in the field, and it is not yet text.
 * Only the kept (committed) text may reach Neovim; marked text sent along would make Neovim's line
 * {@code :kkkおoお} for {@code :ko}, and with {@code incsearch} jump to half-typed kana.
 *
 * <h2>What macOS sends</h2>
 * SWT's macOS port sends no {@code SWT.ImeComposition} events for a {@code Text}. What it does send,
 * logged with Japanese Romaji input on macOS (plug-in 0.2.1):
 * <ul>
 *   <li><b>Marked text</b> is an edit (a verify event) that carries the key that changed it, and
 *       comes <em>before</em> that key's own key press: {@code k} gives the edit "k" with key code
 *       {@code k}, then the press of {@code k}; {@code a} gives "か" over the marked "k", then the
 *       press of {@code a}. Space, the arrows, Backspace and Esc that change the marked text work the
 *       same way. An edit that leaves no marked text (Backspace on the last kana, the second Esc)
 *       ends the composition. A typed key never reaches the field as an edit, because the box
 *       sends it to Neovim and cancels it, so a keyed edit is always the input method's.</li>
 *   <li><b>The commit</b> (Enter) is one key press per committed character, the first with Enter's
 *       key code, the others with none, each carrying its character ({@code こ}, {@code ん}, ...),
 *       followed by <em>one</em> edit with the whole text over the marked range. The box leaves the
 *       presses to the platform and sends that edit's text: taking the first press's character
 *       lost the rest, and cancelling it left the marked text in the field, which macOS committed
 *       again on the box's next text change ({@code :ここ}).</li>
 *   <li>An Enter that only closes the input method's candidate list is a press with its own
 *       character ({@code \r}) and no edit: it is the input method's, not a commit, and not
 *       Neovim's (the line is not submitted).</li>
 *   <li><b>A paste</b> while composing (Cmd+V) is an edit without a key code that inserts the
 *       clipboard after the marked text. As in macOS's own fields, the marked text is committed
 *       first: {@code かか}, Cmd+V with "src" on the clipboard, makes {@code :かかsrc}.</li>
 *   <li><b>Losing the focus</b> while composing (a click elsewhere) is an edit that removes the
 *       marked text, then one that inserts it as committed text. That commit is sent like any
 *       paste, deliberately: every macOS field keeps such text, and in Ex mode ({@code gQ}), where
 *       the box stays open on a click, the typed text is kept rather than lost. Outside Ex mode the
 *       click abandons the line anyway.</li>
 *   <li>Cmd+Tab to another application and back leaves the marked text as it is.</li>
 * </ul>
 *
 * <h2>Neovim's views</h2>
 * Setting the box's text ends a composition (macOS commits the marked text), so while composing the
 * box keeps Neovim's newest view and shows it when the composition ends ({@link #composing}).
 * Key presses that arrive while the box itself sets the text are macOS committing again; they are
 * cancelled ({@link Key#CANCEL}).
 *
 * <h2>Why only macOS</h2>
 * On Linux (GTK) a key the input method uses gives no key press, and the committed text arrives as
 * one edit, which the box sends as a paste; on Windows the result string arrives the same way. So
 * there the box sees committed text only, once, and needs none of this. GTK's edits may carry key
 * codes too, so the rules here must not run there.
 *
 * <p>Example, {@code :} then {@code ka} and Enter:
 * <pre>
 *   edit "k" (key k)              marked: shown in the box, not sent
 *   press k                       the input method's: not sent
 *   edit "か" over "k" (key a)    marked
 *   press a                       not sent
 *   press Enter, character か     the commit begins: left to the platform
 *   edit "か" over "か" (Enter)   committed: sent to Neovim as a paste
 * </pre>
 *
 * <p>Not thread-safe: the box calls it on the UI thread.
 */
public final class MarkedTextInput {

    /** What the box does with a key press. */
    public enum Key {
        /** Not the input method's: sent to Neovim as before. */
        SEND,
        /** The input method's or the platform's: not sent, and left to the platform. */
        PLATFORM,
        /** Not sent, and cancelled ({@code doit = false}). */
        CANCEL
    }

    /**
     * What the box does with an edit.
     *
     * @param allow  whether the field makes the edit itself (marked text is shown this way)
     * @param pastes the text to send to Neovim as pastes, in order; none for marked text
     */
    public record Edit(boolean allow, List<String> pastes) {
        static final Edit SHOW = new Edit(true, List.of());

        static Edit send(String... texts) {
            List<String> sent = new ArrayList<>();
            for (String text : texts) {
                if (text != null && !text.isEmpty()) {
                    sent.add(text);
                }
            }
            return new Edit(false, List.copyOf(sent));
        }
    }

    private boolean composing;
    /** True between a commit's first key press and the edit with the committed text. */
    private boolean committing;
    /** The key code of the last edit of marked text: its key press, which follows, is not sent. */
    private int markedKey;
    private int markedStart;
    private String marked = "";

    /** Whether marked text is in the box: Neovim's views wait, and keys are the input method's. */
    public boolean composing() {
        return composing;
    }

    /** Forgets everything: the box opened or closed. */
    public void reset() {
        composing = false;
        committing = false;
        markedKey = 0;
        markedStart = 0;
        marked = "";
    }

    /**
     * An edit the field would make.
     *
     * @param keyCode      the key code it carries (0 for none)
     * @param commandOrCtrl whether Cmd or Ctrl was held
     * @param text         the text it puts in, over {@code start} to {@code end}
     * @param programmatic whether the box itself is setting the text (Neovim's view)
     */
    public Edit edit(int keyCode, boolean commandOrCtrl, String text, int start, int end, boolean programmatic) {
        if (programmatic) {
            return Edit.SHOW; // Neovim's view, set by the box; macOS's edits during it are part of it
        }
        String put = text == null ? "" : text;
        if (committing) {
            end();
            return Edit.send(put);
        }
        if (keyCode != 0 && !commandOrCtrl) {
            markedKey = keyCode;
            markedStart = start;
            marked = put;
            composing = !put.isEmpty();
            return Edit.SHOW;
        }
        if (!composing) {
            return Edit.send(put); // a paste or a drop, as on every platform
        }
        boolean overMarked = start == markedStart && end == markedStart + marked.length();
        if (overMarked && put.isEmpty()) {
            end(); // the marked text taken away (focus lost: its commit follows as an insert)
            return Edit.SHOW;
        }
        String before = overMarked ? "" : marked; // a paste beside it: the marked text is kept first
        end();
        return Edit.send(before, put);
    }

    /**
     * A key press, before the box sends it.
     *
     * @param keyCode       its key code (letters lower case, {@code \r} for Enter, above
     *                      {@code 0xFFFF} for keys without a character, such as the arrows)
     * @param character     its character, or 0
     * @param programmatic  whether the box itself is setting the text (Neovim's view)
     */
    public Key key(int keyCode, char character, boolean programmatic) {
        if (programmatic) {
            return Key.CANCEL; // macOS committing again because the text changed
        }
        boolean ownMarkedKey = markedKey != 0 && keyCode == markedKey;
        markedKey = 0;
        if (ownMarkedKey) {
            return Key.PLATFORM; // it made the marked text, which the box already has
        }
        if (!composing) {
            return Key.SEND;
        }
        if (keyCode == 0 || character >= 0x20 && !isOwnCharacter(keyCode, character)) {
            committing = true; // Enter carrying こ, then ん, ...: the edit with the whole text follows
        }
        return Key.PLATFORM; // everything else while composing is the input method's (Enter, Esc, Tab)
    }

    private void end() {
        composing = false;
        committing = false;
        marked = "";
    }

    /** Whether {@code character} is what {@code keyCode} types by itself ({@code \r} for Enter). */
    private static boolean isOwnCharacter(int keyCode, char character) {
        if (keyCode > 0xFFFF) {
            return false; // a key with no character of its own carries one only in a commit
        }
        return Character.toLowerCase(character) == Character.toLowerCase((char) keyCode);
    }
}
