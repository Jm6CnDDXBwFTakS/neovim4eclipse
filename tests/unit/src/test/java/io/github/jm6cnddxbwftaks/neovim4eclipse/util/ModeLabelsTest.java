// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ModeLabels.Group;

/**
 * {@link ModeLabels}: every Neovim mode string the status bar names, its label and its tooltip. The
 * mode strings are those Neovim 0.12.6 reported for each state; the labels are
 * lualine's names with Vim's parentheses (docs/design/STATUS_BAR.md).
 */
class ModeLabelsTest {

    private static final String CTRL_V = "\u0016";
    private static final String CTRL_S = "\u0013";

    /** One row: the mode strings Neovim reports, then the label and its tooltip (Vim's wording). */
    private record Row(List<String> modes, String text, String vim) {
    }

    private static Row row(List<String> modes, String text, String vim) {
        return new Row(modes, text, vim);
    }

    private static final List<Row> TABLE = List.of(
            row(List.of("n", "nt", "ntT", ""), "NORMAL", "-- NORMAL --"),
            row(List.of("no", "nov", "noV", "no" + CTRL_V), "O-PENDING", "-- OPERATOR PENDING --"),
            row(List.of("niI"), "(INSERT)", "-- (insert) --"),
            row(List.of("niR"), "(REPLACE)", "-- (replace) --"),
            row(List.of("niV"), "(V-REPLACE)", "-- (vreplace) --"),
            row(List.of("i", "ic", "ix"), "INSERT", "-- INSERT --"),
            row(List.of("R", "Rc", "Rx"), "REPLACE", "-- REPLACE --"),
            row(List.of("Rv", "Rvc", "Rvx"), "V-REPLACE", "-- VREPLACE --"),
            row(List.of("v"), "VISUAL", "-- VISUAL --"),
            row(List.of("V"), "V-LINE", "-- VISUAL LINE --"),
            row(List.of(CTRL_V), "V-BLOCK", "-- VISUAL BLOCK --"),
            row(List.of("vs"), "(S) VISUAL", "-- (select) VISUAL --"),
            row(List.of("Vs"), "(S) V-LINE", "-- (select) VISUAL LINE --"),
            row(List.of(CTRL_V + "s"), "(S) V-BLOCK", "-- (select) VISUAL BLOCK --"),
            row(List.of("s"), "SELECT", "-- SELECT --"),
            row(List.of("S"), "S-LINE", "-- SELECT LINE --"),
            row(List.of(CTRL_S), "S-BLOCK", "-- SELECT BLOCK --"),
            row(List.of("c", "cr"), "COMMAND", "-- COMMAND --"),
            row(List.of("cv", "cvr"), "EX", "-- EX --"),
            row(List.of("rm"), "MORE", "-- More --"),
            row(List.of("r"), "HIT-ENTER", "Press ENTER"),
            row(List.of("r?"), "CONFIRM", "-- CONFIRM --"),
            row(List.of("!"), "SHELL", "-- SHELL --"),
            row(List.of("t"), "TERMINAL", "-- TERMINAL --"));

    @Test
    void everyModesLabelAndTooltip() {
        for (Row row : TABLE) {
            for (String mode : row.modes()) {
                ModeLabels.Label label = ModeLabels.of(mode);
                assertEquals(row.text(), ModeLabels.text(label, ""), "label of " + mode);
                assertEquals(row.vim(), ModeLabels.tooltip(label, ""), "tooltip of " + mode);
            }
        }
        assertEquals("NORMAL", ModeLabels.of(null).text(), "before Neovim said");
    }

    @Test
    void eclipseHasItsOwnLabel() {
        assertEquals("ECLIPSE", ModeLabels.text(ModeLabels.ECLIPSE, null));
        assertEquals("Keys go to Eclipse, not Neovim", ModeLabels.tooltip(ModeLabels.ECLIPSE, null));
    }

    /** A mode a newer Neovim adds is shown as Neovim names it, not as Normal. */
    @Test
    void anUnknownModeShowsItsOwnName() {
        assertEquals("XY", ModeLabels.of("xy").text());
        assertEquals("-- XY --", ModeLabels.of("xy").vim());
    }

    /** {@code rec} in lower case after the label; Vim's {@code recording @q} in the tooltip. */
    @Test
    void recordingFollowsTheMode() {
        ModeLabels.Label insert = ModeLabels.of("i");
        assertEquals("INSERT rec @q", ModeLabels.text(insert, "q"));
        assertEquals("INSERT", ModeLabels.text(insert, ""));
        assertEquals("INSERT", ModeLabels.text(insert, null));
        assertEquals("-- INSERT --  recording @q", ModeLabels.tooltip(insert, "q"));
    }

    /**
     * The label is as wide as the widest of these, so it keeps its size; recording widens it. The
     * widest are 11 characters, 18 while recording (the point of the short names).
     */
    @Test
    void everyTextTheLabelMayShow() {
        List<String> texts = ModeLabels.texts(false);
        assertTrue(texts.containsAll(List.of("NORMAL", "(S) V-BLOCK", "ECLIPSE", "SHELL")));
        assertTrue(ModeLabels.texts(true).contains("(S) V-BLOCK rec @a"));
        assertEquals(texts.size(), ModeLabels.texts(true).size(), "one text per state");
        assertEquals(11, texts.stream().mapToInt(String::length).max().orElseThrow());
        assertEquals(18, ModeLabels.texts(true).stream().max(Comparator.comparingInt(String::length))
                .orElseThrow().length());
    }

    /** The groups the colors follow: Visual from Select is Visual, Normal for one command from Insert is Insert. */
    @Test
    void eachStateHasItsGroup() {
        assertEquals(Group.VISUAL, ModeLabels.of(CTRL_V + "s").group());
        assertEquals(Group.INSERT, ModeLabels.of("niI").group());
        assertEquals(Group.INSERT, ModeLabels.of("Rv").group());
        assertEquals(Group.PENDING, ModeLabels.of("no").group());
        assertEquals(Group.PENDING, ModeLabels.of("cv").group());
        assertEquals(Group.OTHER, ModeLabels.of("n").group());
        assertEquals(Group.OTHER, ModeLabels.of(CTRL_S).group());
        assertEquals(Group.ECLIPSE, ModeLabels.ECLIPSE.group());
    }
}
