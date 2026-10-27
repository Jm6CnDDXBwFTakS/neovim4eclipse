// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions.Fold;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions.Region;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FoldRegions.StateChange;

/** {@link FoldRegions}; what Neovim does with the result is {@code folds_spec.lua}. */
class FoldRegionsTest {

    private static Region open(int first, int last) {
        return new Region(first, last, false);
    }

    private static Region collapsed(int first, int last) {
        return new Region(first, last, true);
    }

    // ---- forNeovim ----

    @Test
    void outerFoldsComeFirstAndTheLongerOneWhenTwoStartOnOneLine() {
        // A method 3-8 with its Javadoc 3-5 inside a class 1-20, given in Eclipse's (any) order.
        List<Fold> folds = FoldRegions.forNeovim(List.of(open(3, 5), open(1, 20), open(3, 8)));
        assertEquals(List.of(new Fold(1, 20, false), new Fold(3, 8, false), new Fold(3, 5, false)), folds);
    }

    @Test
    void oneLineAndBackwardsRegionsAreDropped() {
        assertEquals(List.of(new Fold(4, 5, false)),
                FoldRegions.forNeovim(List.of(open(2, 2), open(9, 7), open(0, 3), open(4, 5))));
    }

    @Test
    void twoRegionsOverTheSameLinesBecomeOneCollapsedIfEitherIs() {
        assertEquals(List.of(new Fold(2, 6, true)), FoldRegions.forNeovim(List.of(open(2, 6), collapsed(2, 6))));
    }

    @Test
    void aRegionCrossingAKeptOnesBorderIsDropped() {
        // Neovim would widen 4-9 to 2-9 (experiment X14). One inside and one after are kept.
        List<Fold> folds = FoldRegions.forNeovim(List.of(open(2, 6), open(4, 9), open(3, 4), open(7, 9)));
        assertEquals(List.of(new Fold(2, 6, false), new Fold(3, 4, false), new Fold(7, 9, false)), folds);
    }

    @Test
    void luaArgsAreTheBufferThenThreePerFold() {
        List<Fold> folds = FoldRegions.forNeovim(List.of(collapsed(2, 5), open(7, 9)));
        assertEquals(List.of("3", "2", "5", "1", "7", "9", "0"), FoldRegions.toLuaArgs(3, folds));
        assertEquals(List.of("4"), FoldRegions.toLuaArgs(4, List.of()));
    }

    // ---- stateChange ----

    /** A class 0 (1-20) with methods 1 (3-8) and 2 (10-15). */
    private static final List<Fold> CLASS = FoldRegions.forNeovim(List.of(open(1, 20), open(3, 8), open(10, 15)));

    @Test
    void reportedFoldsAreCollapsedAndTheOthersExpanded() {
        StateChange change = FoldRegions.stateChange(CLASS, List.of(false, true, false), Set.of(2));
        assertEquals(List.of(2), change.collapse());
        assertEquals(List.of(1), change.expand());
    }

    @Test
    void foldsInsideAReportedOneKeepEclipsesState() {
        // zM: Neovim reports the class alone; the methods' state is unknown, Eclipse keeps it.
        StateChange change = FoldRegions.stateChange(CLASS, List.of(false, true, false), Set.of(0));
        assertEquals(List.of(0), change.collapse());
        assertEquals(List.of(), change.expand());
    }

    @Test
    void zoOnTheClassReportsTheMethodsAndExpandsOnlyTheClass() {
        StateChange change = FoldRegions.stateChange(CLASS, List.of(true, true, true), Set.of(1, 2));
        assertEquals(List.of(), change.collapse());
        assertEquals(List.of(0), change.expand());
    }

    @Test
    void nothingReportedExpandsEverything() {
        // zn: 'nofoldenable' makes Neovim report no closed fold, and Eclipse shows all the code.
        StateChange change = FoldRegions.stateChange(CLASS, List.of(true, true, false), Set.of());
        assertEquals(List.of(0, 1), change.expand());
    }

    @Test
    void agreeingStatesChangeNothingAndUnknownIndexesAreIgnored() {
        StateChange change = FoldRegions.stateChange(CLASS, List.of(false, true, false), Set.of(1, 7, -1));
        assertTrue(change.isEmpty(), change.toString());
    }

    @Test
    void aShorterStateListComparesOnlyTheFoldsItHas() {
        StateChange change = FoldRegions.stateChange(CLASS, List.of(false), Set.of(2));
        assertTrue(change.isEmpty(), change.toString());
    }
}
