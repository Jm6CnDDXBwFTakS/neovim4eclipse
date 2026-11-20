// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * What an input method's composition on Neovim's command line does, as plain decisions:
 * {@code editor/LineInputMethod} feeds it SWT's events and acts on the answers.
 *
 * <h2>The concept</h2>
 * An input method (Japanese, Chinese, Korean) lets a person compose a character from several keys:
 * {@code k}, {@code a} shows {@code か}, Space offers {@code 可}, Enter commits it. Until the commit
 * the text is the input method's, "composing"; it is shown where it will go (here: at the cursor of
 * Neovim's line, which Neovim draws itself, ui2), but goes nowhere yet. At the commit it becomes
 * text: here, typed into Neovim's line.
 *
 * <p>SWT reports a composition as {@code ImeComposition} events of the kind
 * {@code COMPOSITION_CHANGED}: the input method's whole text ({@code IME.getText()}) and how many of
 * its characters are committed ({@code IME.getCommitCount()}). After a commit, every system also
 * delivers the committed text as key presses: one per character, its {@code character} the
 * committed one (and on macOS its key code that of Enter, 13, measured with Kotoeri). The editor
 * would send those keys to Neovim too, and on macOS as Enter, running the line. So the committed
 * text is sent once, as text ({@link #changed}), and the key presses that repeat it are dropped
 * ({@link #dropKey}).
 *
 * <pre>
 *   changed("k", 0)     composing "k"        send nothing
 *   changed("か", 0)    composing "か"
 *   changed("可", 0)    composing "可"
 *   changed("可", 1)    composing ""         send "可"
 *   key '可'                                  dropped: already sent
 *   key 'x'                                   kept: an ordinary key
 * </pre>
 *
 * <p>Not thread-safe: the UI thread only.
 */
public final class LineComposition {

    private String composing = "";
    /** Committed text whose key presses have not all come yet; they are dropped as they do. */
    private String unechoed = "";

    /**
     * A change of the composition: the input method's {@code text}, of which the first
     * {@code committed} characters are committed now (0 while still composing).
     *
     * @return the text to type into Neovim's line now, {@code ""} for none
     */
    public String changed(String text, int committed) {
        String all = text == null ? "" : text;
        int count = Math.max(0, Math.min(committed, all.length()));
        String sent = all.substring(0, count);
        composing = all.substring(count);
        unechoed = sent; // a new commit replaces the echo still expected of an earlier one
        return sent;
    }

    /**
     * A key press while the line is open: whether to drop it, because it repeats a committed
     * character already sent. Any other key ends the expectation: the echo, if it was to come, is
     * past. Example: after {@code "可"} was sent, {@code '可'} is dropped, then {@code 'x'} is kept.
     */
    public boolean dropKey(char character) {
        if (!unechoed.isEmpty() && unechoed.charAt(0) == character) {
            unechoed = unechoed.substring(1);
            return true;
        }
        unechoed = "";
        return false;
    }

    /** The text being composed now, not committed: drawn at the line's cursor; {@code ""} for none. */
    public String composing() {
        return composing;
    }

    /** The composition is over without a commit (the line closed, a click, the focus left): nothing is sent. */
    public void end() {
        composing = "";
        unechoed = "";
    }
}
