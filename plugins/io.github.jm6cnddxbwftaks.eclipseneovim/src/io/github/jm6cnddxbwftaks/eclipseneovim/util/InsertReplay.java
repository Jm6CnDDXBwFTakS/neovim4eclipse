// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

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

    /** Source characters per {@code nvim_input} call; small, so Neovim's input buffer can never overflow. */
    static final int CHUNK_CHARS = 80;

    /**
     * What to replay.
     *
     * @param keyChunks        the typed text as Neovim key notation, in pieces to send one by one
     * @param baseLine1Based   where the insertion started in Neovim's buffer: 1-based line
     * @param baseCol0Based    ... and 0-based UTF-8 byte column
     * @param finalLine1Based  where Vim leaves the cursor after Esc (one character left of the caret)
     * @param finalCol0Based   ... 0-based UTF-8 byte column
     */
    public record Plan(List<String> keyChunks, int baseLine1Based, int baseCol0Based,
            int finalLine1Based, int finalCol0Based) {
    }

    private InsertReplay() {
    }

    /**
     * @param before        document text when the first change of the insert happened (equals Neovim's buffer)
     * @param after         document text now, at Esc
     * @param baseOffset    offset of that first change in {@code before}
     * @param caretAfter    caret offset in {@code after}
     * @return the plan, or {@code null} if the edit is not a replayable plain insertion
     */
    public static Plan plan(String before, String after, int baseOffset, int caretAfter) {
        String typed = insertedText(before, after, baseOffset);
        if (typed == null || typed.length() > MAX_REPLAY_CHARS) {
            return null;
        }
        List<String> chunks = toKeyChunks(typed);
        if (chunks.isEmpty()) {
            return null;
        }
        int[] base = lineAndByteCol(before, baseOffset);
        int[] last = lineAndByteCol(after, cursorAfterEsc(after, caretAfter));
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
     * Converts typed text to Neovim key notation, in chunks. {@code <} becomes {@code <lt>} (or
     * {@code nvim_input} would read {@code <Esc>} inside the text as a key press), line breaks become
     * {@code <CR>}, tabs {@code <Tab>}; other control characters are dropped.
     */
    static List<String> toKeyChunks(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        int sourceChars = 0;
        int i = 0;
        while (i < normalized.length()) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '<') {
                chunk.append("<lt>");
            } else if (cp == '\n') {
                chunk.append("<CR>");
            } else if (cp == '\t') {
                chunk.append("<Tab>");
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
