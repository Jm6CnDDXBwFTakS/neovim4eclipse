// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Neovim's lines that Eclipse's document shows as more than one line, and so where each Neovim line
 * starts in the document until Neovim has split them the same way.
 *
 * <p><b>Why.</b> Neovim keeps a carriage return inside a line ({@code Ctrl-V Ctrl-M}, {@code :s}
 * with {@code nr2char(13)}, a put of a register that holds one), but a JFace document takes it as a
 * line break. Neovim's one line {@code "\ra"} is two lines in Eclipse ({@code ""} and {@code "a"}),
 * so every Neovim line below it is one Eclipse line further down. The synchronizer has Neovim split
 * the line too ({@link BufferSynchronizer}, {@code BufferPort.splitLineBreaks}), but that is queued
 * behind the keys already sent: in an insert Neovim types, the keys typed before it still arrive as
 * line events with Neovim's numbers, and this class turns them into the document's.
 *
 * <p><b>Example.</b> {@code qa i Ctrl-V Ctrl-M b Esc q} on line 3 of {@code "x", "y", "z", "a", "c"}
 * (the insert stays Neovim's while a macro is recorded). After {@code Ctrl-M} Neovim's line 3 is
 * {@code "\ra"}: Eclipse's lines 3 and 4. After {@code b} Neovim sends line 3 as {@code "\rba"}:
 * {@link #eclipseLine} turns Neovim's lines 3 to 4 (exclusive) into Eclipse's 3 to 5, so both
 * Eclipse lines are replaced, and {@code "c"} stays Neovim's line 4 and Eclipse's line 5. Without
 * it the event replaced only Eclipse's line 3, and Eclipse kept an extra copy of {@code "a"} after
 * each key.
 *
 * <p>Only the text is translated: until the split, Neovim's cursor reports (and folds) keep Neovim's
 * line numbers, so for those few keys the caret may show one line up per split line above it. The
 * split moves Neovim's cursor onto the right part, and Eclipse types again only after it.
 *
 * <p>Counted as Eclipse's default line tracker splits: at {@code \r\n}, {@code \r} and {@code \n}
 * ({@code \n} does not arrive in practice: see {@code LineTextUtil.holdsLineBreak}). Not
 * thread-safe: the synchronizer uses it on the UI thread only.
 */
final class SplitLines {

    /** Neovim line number to the number of line breaks Eclipse sees inside it (never 0). */
    private final TreeMap<Integer, Integer> breaks = new TreeMap<>();

    /** Whether every Neovim line is one Eclipse line (nothing to translate, nothing to push). */
    boolean isEmpty() {
        return breaks.isEmpty();
    }

    /** Forgets every split: Neovim has Eclipse's lines (a push wrote them). */
    void clear() {
        breaks.clear();
    }

    /**
     * The document line where Neovim's line {@code neovimLine} starts; for Neovim's line count, the
     * document's line count (without the empty line after a final line break).
     * Example: with Neovim's line 3 holding one break, Neovim's 4 is Eclipse's 5.
     */
    int eclipseLine(int neovimLine) {
        int line = neovimLine;
        for (int extra : breaks.headMap(neovimLine).values()) {
            line += extra;
        }
        return line;
    }

    /**
     * Neovim replaced its lines {@code first .. last - 1} with {@code newLines} (a line event). Call
     * with Neovim's numbers, after {@link #eclipseLine} has turned them into the document's.
     *
     * @param delimiter the document's line delimiter ({@link #breaksIn})
     */
    void replaced(int first, int last, List<String> newLines, String delimiter) {
        breaks.subMap(first, last).clear();
        int shift = newLines.size() - (last - first);
        if (shift != 0) {
            TreeMap<Integer, Integer> below = new TreeMap<>(breaks.tailMap(last));
            breaks.tailMap(last).clear();
            for (Map.Entry<Integer, Integer> e : below.entrySet()) {
                breaks.put(e.getKey() + shift, e.getValue());
            }
        }
        for (int i = 0; i < newLines.size(); i++) {
            int count = breaksIn(newLines.get(i), delimiter);
            if (count > 0) {
                breaks.put(first + i, count);
            }
        }
    }

    /**
     * Makes Neovim's lines {@code first .. first + count - 1} span {@code eclipseLines} document
     * lines in all, as the document counts them after the edit, by changing the last one's breaks.
     *
     * <p>Why: {@link #breaksIn} cannot always tell. A JFace document reads {@code "a\r\n"} as one
     * line when it is loaded that way, but as two when the {@code \r} was typed before an existing
     * {@code \n}: its line tracker does not join the two after an insert. So after each edit the
     * synchronizer asks the document how many lines the new text takes, and this puts the count
     * right. Exact for one line, the line event of a typed key; for several, the difference goes to
     * the last of them.
     */
    void correct(int first, int count, int eclipseLines) {
        if (count <= 0) {
            return;
        }
        int sum = 0;
        for (int extra : breaks.subMap(first, first + count).values()) {
            sum += extra;
        }
        int wanted = eclipseLines - count;
        for (int line = first + count - 1; line >= first && sum != wanted; line--) {
            int now = breaks.getOrDefault(line, 0);
            int set = Math.max(0, now + wanted - sum);
            sum += set - now;
            if (set == 0) {
                breaks.remove(line);
            } else {
                breaks.put(line, set);
            }
        }
    }

    /**
     * The split Neovim lines and their break counts, by Neovim line number (0-based), in order.
     * A copy: for the split, read on the UI thread.
     */
    Map<Integer, Integer> splits() {
        return new TreeMap<>(breaks);
    }

    /** Neovim's whole buffer is {@code lines} (a full sync). */
    void replacedAll(List<String> lines, String delimiter) {
        breaks.clear();
        replaced(0, 0, lines, delimiter);
    }

    /**
     * How many lines more than one a Neovim line is in Eclipse: {@code "\r\na\rb"} is 2.
     *
     * <p>A carriage return at the end of the line, before a {@code \n} delimiter, is no extra line:
     * Eclipse reads {@code "a\r" + "\n"} as one line {@code "a"} that ends in {@code \r\n}. Before
     * {@code \r\n} or {@code \r} it is one ({@code "a\r\r\n"} is {@code "a"} and {@code ""}).
     *
     * @param delimiter the document's line delimiter, which follows the line
     */
    static int breaksIn(String line, String delimiter) {
        if (line == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\r') {
                count++;
                if (i + 1 < line.length() && line.charAt(i + 1) == '\n') {
                    i++; // "\r\n" is one line break
                }
            } else if (c == '\n') {
                count++;
            }
        }
        if (count > 0 && line.endsWith("\r") && delimiter != null && delimiter.startsWith("\n")) {
            count--; // "a\r" + "\n": one line ending in "\r\n"
        }
        return count;
    }
}
