// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;

/**
 * The completion candidates of the {@code :} box as one line of text, around the selected one, for
 * the line drawn above the box. Neovim completes ({@code popupmenu_show} and {@code popupmenu_select},
 * see {@link CmdlineState}); this class only decides which candidates fit and where the highlight is.
 *
 * <p>Example: candidates {@code a b c d e f}, {@code d} selected, room for about three gives
 * {@code "… c  d  e …"}, with {@code d} highlighted. Pure; unit-tested in {@code CandidateLineTest}.
 */
public final class CandidateLine {

    /** Separates candidates in the line. */
    static final String GAP = "  ";
    /** Marks an end of the line that has more candidates beyond it. */
    static final String MORE = "\u2026";

    /**
     * The candidates as one line of text.
     *
     * @param text            the candidates, separated by two spaces, with {@code …} where some are cut off
     * @param highlightStart  where the selected candidate starts in {@code text}, or -1 if none is selected
     * @param highlightLength its length
     */
    public record ListLine(String text, int highlightStart, int highlightLength) {
    }

    private CandidateLine() {
    }

    /**
     * Fits the candidates into one line of {@code width} characters, around the selected one: as many
     * neighbors as fit, with {@code …} at a cut end.
     *
     * <p>Example: candidates {@code a b c d e f}, current {@code d}, room for about three gives
     * {@code "… c  d  e …"}.
     *
     * @param current the selected candidate, or -1 for none (the line then starts at the first)
     */
    public static ListLine fit(List<String> candidates, int current, int width) {
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
            if (hi + 1 < n && width(candidates, lo, hi + 1) <= width) {
                hi++;
                grew = true;
            }
            if (lo > 0 && width(candidates, lo - 1, hi) <= width) {
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
        if (hi < n - 1) {
            text.append(' ').append(MORE);
        }
        return new ListLine(text.toString(), start, start < 0 ? 0 : candidates.get(current).length());
    }

    /** The length of the line showing candidates {@code lo..hi}, with its {@code …} marks. */
    private static int width(List<String> candidates, int lo, int hi) {
        int w = 0;
        for (int i = lo; i <= hi; i++) {
            w += candidates.get(i).length();
        }
        w += GAP.length() * (hi - lo);
        if (lo > 0) {
            w += MORE.length() + 1;
        }
        if (hi < candidates.size() - 1) {
            w += MORE.length() + 1;
        }
        return w;
    }
}
