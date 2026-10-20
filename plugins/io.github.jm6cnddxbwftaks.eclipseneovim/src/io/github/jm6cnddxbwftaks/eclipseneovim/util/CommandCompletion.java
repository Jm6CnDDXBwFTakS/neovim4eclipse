// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.List;

/**
 * The decisions behind Tab in the {@code :} box, kept out of the SWT code: where a cycle of Tabs
 * starts and ends, which answers from Neovim are still wanted, which candidate the line shows, and
 * how the candidate list fits into one line.
 *
 * <h2>The cycle</h2>
 * Neovim does the completing ({@link Completion}); each Tab sends it the text as it was before the
 * first Tab, plus the keys pressed so far ({@link Request#steps()}), and Neovim applies its own
 * {@code 'wildmode'} to them. For example, with {@code tabn} in the box:
 * <pre>
 *   Tab        → steps "T"    → tabNext   (the first match)
 *   Tab        → steps "TT"   → tabnew
 *   Shift+Tab  → steps "TTP"  → tabNext   (back one)
 *   typing "e" → the cycle ends; the next Tab completes "tabNexte"
 * </pre>
 * Shift+Tab at the start is {@code "TPP"}: Neovim's Shift+Tab works only for keys typed by a person,
 * and expanding then going back twice gives the same, the last match.
 *
 * <p>Each request has a generation number. An answer is shown only if no newer request was made and
 * the text was not edited meanwhile, so a slow answer cannot overwrite what was typed after it.
 *
 * <p>Not thread-safe: the box uses it on the UI thread only.
 */
public final class CommandCompletion {

    /** Separates candidates in the list line. */
    static final String GAP = "  ";
    /** Marks the end of the list line that has more candidates beyond it. */
    static final String MORE = "…";

    /**
     * What to ask Neovim.
     *
     * @param line       the text before the first Tab, without the {@code :}
     * @param steps      {@code T} next match, {@code P} previous match, one per key
     * @param generation identifies the request; pass it back to {@link #accept}
     */
    public record Request(String line, String steps, int generation) {
    }

    /**
     * The candidate list as one line of text.
     *
     * @param text            the candidates, separated by two spaces, with {@code …} where some are cut off
     * @param highlightStart  where the current candidate starts in {@code text}, or -1 if none is current
     * @param highlightLength its length
     */
    public record ListLine(String text, int highlightStart, int highlightLength) {
    }

    /** The text before the first Tab of the cycle; {@code null} when no cycle runs. */
    private String original;
    private String steps = "";
    private int generation;
    /** The text the box shows if nothing was edited since: after a Tab, or after an answer. */
    private String expected;

    /**
     * Tab or Shift+Tab was pressed.
     *
     * @param current  the box's text now, without the {@code :}
     * @param backward Shift+Tab
     */
    public Request press(String current, boolean backward) {
        if (original == null || !current.equals(expected)) {
            original = current;
            steps = "";
        }
        if (backward) {
            steps += steps.isEmpty() ? "TPP" : "P";
        } else {
            steps += "T";
        }
        expected = current;
        generation++;
        return new Request(original, steps, generation);
    }

    /**
     * An answer arrived.
     *
     * @return whether to show it: it answers the newest request and nothing was edited since
     */
    public boolean accept(int answered, Completion completion) {
        if (original == null || answered != generation) {
            return false;
        }
        expected = completion.line();
        return true;
    }

    /** The text was edited (typed, deleted, history): the next Tab starts a new cycle. */
    public void reset() {
        original = null;
        steps = "";
        expected = null;
        generation++; // answers still on their way are dropped
    }

    /**
     * Which candidate the completed line shows: the longest candidate the line ends with, where it
     * starts a word. File names come back escaped ({@code with\ space.txt} for {@code with space.txt}),
     * so the line is also compared with its backslashes before spaces removed.
     *
     * @return its index in {@code candidates}, or -1 (for example when the cycle is back at the
     *         typed text)
     */
    public static int currentIndex(String line, List<String> candidates) {
        String unescaped = line.replace("\\ ", " ");
        int best = -1;
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            if (candidate.isEmpty() || (best >= 0 && candidates.get(best).length() >= candidate.length())) {
                continue;
            }
            if (endsWithWord(line, candidate) || endsWithWord(unescaped, candidate)) {
                best = i;
            }
        }
        return best;
    }

    private static boolean endsWithWord(String line, String candidate) {
        if (!line.endsWith(candidate)) {
            return false;
        }
        int before = line.length() - candidate.length() - 1;
        return before < 0 || !isWordChar(line.charAt(before)) || !isWordChar(candidate.charAt(0));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }

    /**
     * Fits the candidates into one line of {@code width} characters, around the current one: as many
     * neighbors as fit, with {@code …} at a cut end. More candidates than listed ({@code total}) count
     * as cut off at the right.
     *
     * <p>Example: candidates {@code a b c d e f}, current {@code d}, room for about three gives
     * {@code "… c  d  e …"}.
     *
     * @param current the index from {@link #currentIndex}, or -1 to start at the first candidate
     */
    public static ListLine fit(List<String> candidates, int total, int current, int width) {
        int n = candidates.size();
        if (n == 0) {
            return new ListLine("", -1, 0);
        }
        int center = current >= 0 && current < n ? current : 0;
        int lo = center;
        int hi = center;
        boolean grew = true;
        while (grew) {
            grew = false;
            if (hi + 1 < n && width(candidates, total, lo, hi + 1) <= width) {
                hi++;
                grew = true;
            }
            if (lo > 0 && width(candidates, total, lo - 1, hi) <= width) {
                lo--;
                grew = true;
            }
        }
        StringBuilder text = new StringBuilder();
        if (lo > 0) {
            text.append(MORE).append(' ');
        }
        int start = -1;
        for (int i = lo; i <= hi; i++) {
            if (i > lo) {
                text.append(GAP);
            }
            if (i == current) {
                start = text.length();
            }
            text.append(candidates.get(i));
        }
        if (hi < n - 1 || total > n) {
            text.append(' ').append(MORE);
        }
        return new ListLine(text.toString(), start, start < 0 ? 0 : candidates.get(current).length());
    }

    /** The length of the line showing candidates {@code lo..hi}, with its {@code …} marks. */
    private static int width(List<String> candidates, int total, int lo, int hi) {
        int w = 0;
        for (int i = lo; i <= hi; i++) {
            w += candidates.get(i).length();
        }
        w += GAP.length() * (hi - lo);
        if (lo > 0) {
            w += MORE.length() + 1;
        }
        if (hi < candidates.size() - 1 || total > candidates.size()) {
            w += MORE.length() + 1;
        }
        return w;
    }
}
