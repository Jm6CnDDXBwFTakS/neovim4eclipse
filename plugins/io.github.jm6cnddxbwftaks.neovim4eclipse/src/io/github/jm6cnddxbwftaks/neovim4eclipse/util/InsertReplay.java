// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out what must be replayed into Neovim when an Eclipse-driven insert ends, so that Neovim's
 * own repeat ({@code .}, {@code ".p}) knows the text that was typed.
 *
 * <p><b>Why:</b> in insert mode Eclipse types (so content assist and IME keep working) and Neovim
 * only learns the result afterward. Neovim's redo buffer would then hold just {@code A} with no
 * text, and {@code .} would repeat an empty insert. At Esc, if the typing amounts to one plain
 * insertion at the place of the first change, that text is typed into Neovim's insert mode (which is
 * still in the state it was in when the insert began) before the {@code <Esc>}.
 *
 * <p>Anything that is not such an insertion (text deleted before the insertion point, edits in two
 * places, very large pastes) yields {@code null}, and the caller falls back to simply pushing the
 * document, which keeps the text correct but leaves {@code .} without the typed text.
 *
 * <p>Pure Java so it is unit tested without Eclipse or Neovim.
 */
public final class InsertReplay {

    /** Longer insertions (big pastes) are not replayed. */
    public static final int MAX_REPLAY_CHARS = 1000;

    /**
     * Characters per {@code nvim_feedkeys} call. Small, so that each piece is processed before the
     * next is sent (the replay waits after each), as when {@code nvim_input} was used, whose input
     * buffer is limited.
     */
    static final int CHUNK_CHARS = 80;

    /**
     * What to replay.
     *
     * @param textChunks       the typed text as raw characters for {@code nvim_feedkeys}, in pieces
     *                         to send one by one
     * @param baseLine1Based   where the insertion started in Neovim's buffer: 1-based line
     * @param baseCol0Based    ... and 0-based UTF-8 byte column
     * @param finalLine1Based  where Neovim's cursor goes once the text is typed: at Esc, where Vim
     *                         leaves it (one character left of the caret); at a sync point, the caret
     * @param finalCol0Based   ... 0-based UTF-8 byte column
     */
    public record Plan(List<String> textChunks, int baseLine1Based, int baseCol0Based,
            int finalLine1Based, int finalCol0Based) {
    }

    /**
     * What a <em>sync point</em> hands to Neovim: a {@code Ctrl} key Neovim handles in the middle of
     * an insert Eclipse types (docs/design/INSERT_CTRL_KEYS.md, phase C). Unlike Esc, the insert goes
     * on, so Neovim's cursor goes to the caret itself, not one character left of it.
     *
     * <p>Example: Eclipse typed {@code foo bar} and the user presses {@code Ctrl+W}. {@code typed}
     * re-types {@code foo bar} into Neovim's still-open insert, the cursor goes after {@code bar},
     * and then Neovim's own {@code <C-w>} deletes {@code bar} exactly as Vim does.
     *
     * @param typed           what to re-type, or {@code null} when nothing was typed since the insert
     *                        began (or since the last sync point), or it is not one plain insertion:
     *                        then only the document is pushed
     * @param caretLine1Based the caret: 1-based line
     * @param caretCol0Based  ... and 0-based UTF-8 byte column
     */
    public record SyncPoint(Plan typed, int caretLine1Based, int caretCol0Based) {
    }

    private InsertReplay() {
    }

    /**
     * Whether a document change right now is the user typing in a plain Eclipse-driven insert, the
     * only kind whose text is replayed into Neovim at Esc ({@link #plan}). Excluded, and why:
     * <ul>
     *   <li>the editor is closed, or in Eclipse mode ({@code editorActive} false): no Vim insert;</li>
     *   <li>the change is Neovim's own, being applied to the document ({@code applyingNeovimChange}):
     *       Neovim already has it;</li>
     *   <li>any mode but insert ({@code "i"}, {@code "ic"}, {@code "ix"}). Replace mode
     *       ({@code "R"}) too: Eclipse inserts where Vim would overwrite, so the replay would differ;</li>
     *   <li>a Neovim-driven insert (after a block selection, while recording): Neovim already sees
     *       every key, a replay would type the text twice.</li>
     * </ul>
     *
     * @param mode Neovim's current mode; {@code null} if not known yet
     */
    public static boolean tracksChange(boolean editorActive, boolean applyingNeovimChange, String mode,
            boolean neovimDrivenInsert) {
        return editorActive && !applyingNeovimChange && mode != null && mode.startsWith("i") && !neovimDrivenInsert;
    }

    /**
     * @param before        document text when the first change of the insert happened (equals Neovim's buffer)
     * @param after         document text now, at Esc
     * @param baseOffset    offset of that first change in {@code before}
     * @param caretAfter    caret offset in {@code after}
     * @return the plan, or {@code null} if the edit is not a replayable plain insertion
     */
    public static Plan plan(String before, String after, int baseOffset, int caretAfter) {
        return planTo(before, after, baseOffset, cursorAfterEsc(after, caretAfter));
    }

    /**
     * The sync point for a {@code Ctrl} key Neovim handles while Eclipse types (see {@link SyncPoint}).
     *
     * @param before     document text when the first change since the insert began (or since the last
     *                   sync point) happened; {@code null} if nothing changed since
     * @param after      document text now
     * @param baseOffset offset of that first change in {@code before}
     * @param caretAfter caret offset in {@code after}
     */
    public static SyncPoint syncPoint(String before, String after, int baseOffset, int caretAfter) {
        Plan typed = before == null ? null : planTo(before, after, baseOffset, caretAfter);
        int[] caret = lineAndByteCol(after, caretAfter);
        return new SyncPoint(typed, caret[0], caret[1]);
    }

    /** @param finalOffset where Neovim's cursor goes once the text is typed, an offset in {@code after} */
    private static Plan planTo(String before, String after, int baseOffset, int finalOffset) {
        String typed = insertedText(before, after, baseOffset);
        if (typed == null || typed.length() > MAX_REPLAY_CHARS) {
            return null;
        }
        List<String> chunks = toTextChunks(typed);
        if (chunks.isEmpty()) {
            return null;
        }
        int[] base = lineAndByteCol(before, baseOffset);
        int[] last = lineAndByteCol(after, finalOffset);
        return new Plan(chunks, base[0], base[1], last[0], last[1]);
    }

    /**
     * The text inserted at {@code base}, if {@code after} is exactly {@code before} with one
     * non-empty insertion at that offset; otherwise {@code null}. Typing and then deleting again
     * (backspace) is fine: only the net result matters.
     */
    static String insertedText(String before, String after, int base) {
        if (before == null || after == null || base < 0 || base > before.length()) {
            return null;
        }
        int delta = after.length() - before.length();
        if (delta <= 0) {
            return null;
        }
        if (!after.startsWith(before.substring(0, base)) || !after.endsWith(before.substring(base))) {
            return null;
        }
        return after.substring(base, base + delta);
    }

    /**
     * Converts typed text to what {@code nvim_feedkeys} types, in chunks: the characters themselves,
     * since feedkeys takes raw text, not key notation ({@code <Esc>} in the text stays five
     * characters). Line breaks of any style become one {@code \r}, the Enter key, which in insert mode
     * starts a new line as typing it does (with Neovim's auto-indent, which the document push then
     * corrects to Eclipse's). Tabs stay; other control characters are dropped, since typed they would
     * be insert-mode commands ({@code \u0017} is {@code Ctrl-W}).
     */
    static List<String> toTextChunks(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        int sourceChars = 0;
        int i = 0;
        while (i < normalized.length()) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') {
                chunk.append('\r');
            } else if (cp == '\t') {
                chunk.append('\t');
            } else if (cp < 0x20 || cp == 0x7F) {
                continue; // other control characters cannot be typed meaningfully
            } else {
                chunk.appendCodePoint(cp);
            }
            if (++sourceChars >= CHUNK_CHARS) {
                chunks.add(chunk.toString());
                chunk.setLength(0);
                sourceChars = 0;
            }
        }
        if (chunk.length() > 0) {
            chunks.add(chunk.toString());
        }
        return chunks;
    }

    /** Offset where Vim's cursor ends up after {@code <Esc>}: one character left, unless at line start. */
    static int cursorAfterEsc(String text, int caret) {
        int c = Math.max(0, Math.min(caret, text.length()));
        if (c == 0 || text.charAt(c - 1) == '\n' || text.charAt(c - 1) == '\r') {
            return c; // at the start of a line: Vim does not move
        }
        int target = c - 1;
        if (target > 0 && Character.isLowSurrogate(text.charAt(target))
                && Character.isHighSurrogate(text.charAt(target - 1))) {
            target--;
        }
        return target;
    }

    /**
     * Neovim position of an offset: 1-based line and 0-based UTF-8 byte column.
     * Accepts {@code \n}, {@code \r\n} and {@code \r} line breaks.
     */
    static int[] lineAndByteCol(String text, int offset) {
        int end = Math.max(0, Math.min(offset, text.length()));
        int line = 1;
        int lineStart = 0;
        int i = 0;
        while (i < end) {
            char c = text.charAt(i);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                if (i + 1 < end) {
                    line++;
                    lineStart = i + 2;
                }
                i += 2;
            } else if (c == '\n' || c == '\r') {
                line++;
                lineStart = i + 1;
                i++;
            } else {
                i++;
            }
        }
        lineStart = Math.min(lineStart, end);
        return new int[] { line, Utf8Columns.charToByteIndex(text.substring(lineStart, end), end - lineStart) };
    }
}
