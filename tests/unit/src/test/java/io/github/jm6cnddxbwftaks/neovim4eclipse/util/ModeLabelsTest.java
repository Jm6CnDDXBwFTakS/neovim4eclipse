// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ModeLabels.Style;

/**
 * {@link ModeLabels}: every Neovim mode string the status bar names, in the three styles. The mode
 * strings are those Neovim 0.12.6 reported for each state (probe of 2026-10-29); the letters are the
 * maintainer's choices (docs/design/STATUS_BAR.md).
 */
class ModeLabelsTest {

    private static final String CTRL_V = "\u0016";
    private static final String CTRL_S = "\u0013";

    /** One row: the mode strings Neovim reports, then Full, Short and Letters. */
    private record Row(List<String> modes, String full, String shortText, String letters) {
    }

    private static Row row(List<String> modes, String full, String shortText, String letters) {
        return new Row(modes, full, shortText, letters);
    }

    private static final List<Row> TABLE = List.of(
            row(List.of("n", "nt", "ntT", ""), "-- NORMAL --", "NORMAL", "-N-"),
            row(List.of("no", "nov", "noV", "no" + CTRL_V), "-- OPERATOR --", "OPERATOR", "-O-"),
            row(List.of("niI"), "-- (insert) --", "(INSERT)", "-(i)-"),
            row(List.of("niR"), "-- (replace) --", "(REPLACE)", "-(r)-"),
            row(List.of("niV"), "-- (vreplace) --", "(VREPLACE)", "-(vr)-"),
            row(List.of("i", "ic", "ix"), "-- INSERT --", "INSERT", "-I-"),
            row(List.of("R", "Rc", "Rx"), "-- REPLACE --", "REPLACE", "-R-"),
            row(List.of("Rv", "Rvc", "Rvx"), "-- VREPLACE --", "VREPLACE", "-VR-"),
            row(List.of("v"), "-- VISUAL --", "VISUAL", "-V-"),
            row(List.of("V"), "-- VISUAL LINE --", "VISUAL LINE", "-VL-"),
            row(List.of(CTRL_V), "-- VISUAL BLOCK --", "VISUAL BLOCK", "-VB-"),
            row(List.of("vs"), "-- (select) VISUAL --", "(SELECT) VISUAL", "-(s)V-"),
            row(List.of("Vs"), "-- (select) VISUAL LINE --", "(SELECT) VISUAL LINE", "-(s)VL-"),
            row(List.of(CTRL_V + "s"), "-- (select) VISUAL BLOCK --", "(SELECT) VISUAL BLOCK", "-(s)VB-"),
            row(List.of("s"), "-- SELECT --", "SELECT", "-S-"),
            row(List.of("S"), "-- SELECT LINE --", "SELECT LINE", "-SL-"),
            row(List.of(CTRL_S), "-- SELECT BLOCK --", "SELECT BLOCK", "-SB-"),
            row(List.of("c", "cr"), "-- COMMAND --", "COMMAND", "-C-"),
            row(List.of("cv", "cvr"), "-- EX --", "EX", "-EX-"),
            row(List.of("rm"), "-- MORE --", "MORE", "-RM-"),
            row(List.of("r"), "-- HIT-ENTER --", "HIT-ENTER", "-R-"),
            row(List.of("r?"), "-- CONFIRM --", "CONFIRM", "-R?-"),
            row(List.of("!"), "-- SHELL --", "SHELL", "-!sh-"),
            row(List.of("t"), "-- TERMINAL --", "TERMINAL", "-T-"));

    @Test
    void everyModeInEveryStyle() {
        for (Row row : TABLE) {
            for (String mode : row.modes()) {
                ModeLabels.Label label = ModeLabels.of(mode);
                assertEquals(row.full(), label.text(Style.FULL), "Full of " + mode);
                assertEquals(row.shortText(), label.text(Style.SHORT), "Short of " + mode);
                assertEquals(row.letters(), label.text(Style.LETTERS), "Letters of " + mode);
            }
        }
        assertEquals("-- NORMAL --", ModeLabels.of(null).text(Style.FULL), "before Neovim said");
    }

    @Test
    void eclipseHasItsOwnLabel() {
        assertEquals("-- ECLIPSE --", ModeLabels.ECLIPSE.text(Style.FULL));
        assertEquals("ECLIPSE", ModeLabels.ECLIPSE.text(Style.SHORT));
        assertEquals("-E-", ModeLabels.ECLIPSE.text(Style.LETTERS));
    }

    /** A mode a newer Neovim adds is shown as Neovim names it, not as Normal. */
    @Test
    void anUnknownModeShowsItsOwnName() {
        assertEquals("-- XY --", ModeLabels.of("xy").text(Style.FULL));
        assertEquals("-xy-", ModeLabels.of("xy").text(Style.LETTERS));
    }

    @Test
    void recordingFollowsTheMode() {
        ModeLabels.Label insert = ModeLabels.of("i");
        assertEquals("-- INSERT --  recording @q", ModeLabels.text(insert, "q", Style.FULL));
        assertEquals("INSERT  recording @q", ModeLabels.text(insert, "q", Style.SHORT));
        assertEquals("-I- rec @q", ModeLabels.text(insert, "q", Style.LETTERS));
        assertEquals("-I-", ModeLabels.text(insert, "", Style.LETTERS));
        assertEquals("-I-", ModeLabels.text(insert, null, Style.LETTERS));
    }

    /** Letters spell out the name; Short shows Vim's own wording, never the name twice ({@code NORMAL = NORMAL}). */
    @Test
    void theTooltipSaysWhatShortTextMeans() {
        assertEquals("-- VREPLACE --", ModeLabels.tooltip(ModeLabels.of("Rv"), "", Style.FULL));
        assertEquals("-VR- = VREPLACE", ModeLabels.tooltip(ModeLabels.of("Rv"), "", Style.LETTERS));
        assertEquals("-I- rec @q = INSERT, recording @q", ModeLabels.tooltip(ModeLabels.of("i"), "q", Style.LETTERS));
        assertEquals("-- (insert) --", ModeLabels.tooltip(ModeLabels.of("niI"), "", Style.SHORT));
        assertEquals("-- NORMAL --", ModeLabels.tooltip(ModeLabels.of("n"), "", Style.SHORT));
        assertEquals("-- INSERT --  recording @q", ModeLabels.tooltip(ModeLabels.of("i"), "q", Style.SHORT));
    }

    /** The label is as wide as the widest of these, so it keeps its size; recording widens it. */
    @Test
    void everyTextTheLabelMayShow() {
        assertTrue(ModeLabels.texts(Style.FULL, false).containsAll(List.of("-- NORMAL --", "-- (select) VISUAL BLOCK --",
                "-- ECLIPSE --", "-- SHELL --")));
        assertTrue(ModeLabels.texts(Style.LETTERS, false).containsAll(List.of("-N-", "-(s)VB-", "-E-", "-!sh-")));
        assertTrue(ModeLabels.texts(Style.LETTERS, true).contains("-(s)VB- rec @a"));
        assertTrue(ModeLabels.texts(Style.SHORT, true).contains("(SELECT) VISUAL BLOCK  recording @a"));
        assertEquals(ModeLabels.texts(Style.FULL, false).size(), ModeLabels.texts(Style.FULL, true).size(), "one text per state");
    }

    @Test
    void theStoredStyle() {
        assertEquals(Style.LETTERS, Style.of("letters"));
        assertEquals(Style.SHORT, Style.of("short"));
        assertEquals(Style.FULL, Style.of("full"));
        assertEquals(Style.FULL, Style.of("bogus"), "an unknown value falls back to Full");
        assertEquals(Style.FULL, Style.of(null));
    }
}
