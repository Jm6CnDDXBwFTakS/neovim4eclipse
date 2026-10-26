// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;

/**
 * Key routing without SWT or Neovim: a fake session records what the router queued, so each test
 * reads as "in this state, typing these keys sends these keys".
 */
class KeyRouterTest {

    /** A session that only holds state and records the calls made on it. */
    private static final class FakeNeovim implements KeyRouter.Neovim {
        boolean typing;
        boolean neovimDriven;
        boolean eclipseTypingReady = true;
        final List<String> sent = new ArrayList<>();
        final List<InsertReplay.Plan> leftInsert = new ArrayList<>();

        @Override
        public boolean isTypingMode() {
            return typing;
        }

        @Override
        public boolean isNeovimDrivenInsert() {
            return neovimDriven && typing;
        }

        @Override
        public boolean isEclipseTypingReady() {
            return eclipseTypingReady && typing;
        }

        @Override
        public void driveInsertFromNeovim() {
            neovimDriven = true;
        }

        @Override
        public void inputAsync(String keys) {
            sent.add(keys);
        }

        @Override
        public void leaveInsertModeAsync(InsertReplay.Plan replay) {
            leftInsert.add(replay);
        }
    }

    private final FakeNeovim neovim = new FakeNeovim();
    private final KeyRouter router = new KeyRouter(neovim);

    /** Routes each key and returns, per key, whether the router consumed it. */
    private List<Boolean> type(String... keys) {
        List<Boolean> consumed = new ArrayList<>();
        for (String key : keys) {
            consumed.add(router.route(key));
        }
        return consumed;
    }

    @Test
    void normalModeKeysGoToNeovimAndAreConsumed() {
        assertEquals(List.of(true, true, true), type("d", "w", "<C-r>"));
        assertEquals(List.of("d", "w", "<C-r>"), neovim.sent);
    }

    @Test
    void insertModeLetsEclipseTypePrintableKeys() {
        neovim.typing = true;
        assertEquals(List.of(false, false), type("a", "<CR>"));
        assertEquals(List.of(), neovim.sent);
    }

    @Test
    void escInInsertModeLeavesWithTheReplayPlan() {
        neovim.typing = true;
        InsertReplay.Plan plan = new InsertReplay.Plan(List.of("abc"), 1, 0, 1, 3);
        router.setInsertReplaySupplier(() -> plan);
        assertEquals(List.of(true), type("<Esc>"));
        assertEquals(1, neovim.leftInsert.size());
        assertSame(plan, neovim.leftInsert.get(0));
        assertEquals(List.of(), neovim.sent);
    }

    @Test
    void escWithoutAReplaySupplierLeavesWithNoPlan() {
        neovim.typing = true;
        type("<Esc>");
        assertEquals(1, neovim.leftInsert.size());
        assertEquals(null, neovim.leftInsert.get(0));
    }

    @Test
    void aKeyBeforeEclipseMayTypeHandsTheInsertToNeovim() {
        neovim.typing = true;
        neovim.eclipseTypingReady = false;
        assertEquals(List.of(true, true), type("a", "b"));
        assertTrue(neovim.neovimDriven);
        assertEquals(List.of("a", "b"), neovim.sent);
    }

    @Test
    void aNeovimDrivenInsertForwardsEveryKeyIncludingEsc() {
        neovim.typing = true;
        neovim.neovimDriven = true;
        assertEquals(List.of(true, true, true), type("x", ":", "<Esc>"));
        assertEquals(List.of("x", ":", "<Esc>"), neovim.sent);
        assertEquals(List.of(), neovim.leftInsert);
    }

    /**
     * Keys that once had rules of their own ({@code :} and {@code /} opened the box, {@code q} was
     * tracked as a recording, {@code Z} was held back for {@code ZZ}) now simply go to Neovim, in
     * order: Neovim decides what they mean, and the box opens when Neovim opens its command line.
     */
    @Test
    void promptRecordingAndZKeysGoToNeovimInOrder() {
        assertEquals(List.of(true, true, true, true, true, true, true),
                type(":", "/", "?", "q", "a", "Z", "Z"));
        assertEquals(List.of(":", "/", "?", "q", "a", "Z", "Z"), neovim.sent);
    }

    /** {@code f:} typed fast: both keys go to Neovim, which takes the {@code :} as the argument of {@code f}. */
    @Test
    void anArgumentKeyGoesToNeovimLikeAnyOther() {
        type("f", ":");
        assertEquals(List.of("f", ":"), neovim.sent);
    }

    @Test
    void ctrlLettersGivenToEclipseAreLeftAlone() {
        router.setCtrlKeyFilter(letter -> letter != 'w');
        assertEquals(List.of(false, true), type("<C-w>", "<C-o>"));
        assertEquals(List.of("<C-o>"), neovim.sent);
    }

    /** In an insert Neovim drives, every key is text for it, even a Ctrl key given to Eclipse. */
    @Test
    void aNeovimDrivenInsertForwardsCtrlLettersGivenToEclipse() {
        router.setCtrlKeyFilter(letter -> letter != 'w');
        neovim.typing = true;
        neovim.neovimDriven = true;
        assertEquals(List.of(true), type("<C-w>"));
        assertEquals(List.of("<C-w>"), neovim.sent);
    }
}
