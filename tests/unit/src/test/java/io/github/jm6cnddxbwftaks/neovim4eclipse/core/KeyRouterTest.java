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
        /** Each sync point, as {@code key} after {@code sync}. */
        final List<String> synced = new ArrayList<>();
        final List<InsertReplay.SyncPoint> syncPoints = new ArrayList<>();

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

        @Override
        public void syncInsertThenInputAsync(InsertReplay.SyncPoint sync, String key) {
            eclipseTypingReady = false; // as the session does: the next key goes to Neovim
            syncPoints.add(sync);
            synced.add(key);
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

    // ---- sync points: a Ctrl key Neovim handles while Eclipse types (INSERT_CTRL_KEYS.md, phase C) ----

    @Test
    void aCtrlLetterGivenToNeovimForInsertIsASyncPoint() {
        neovim.typing = true;
        InsertReplay.SyncPoint sync = new InsertReplay.SyncPoint(null, 1, 7);
        router.setSyncPointSupplier(() -> sync);
        router.setInsertCtrlKeyFilter(letter -> letter == 'w');
        assertEquals(List.of(true), type("<C-w>"));
        assertEquals(List.of("<C-w>"), neovim.synced);
        assertSame(sync, neovim.syncPoints.get(0));
        assertEquals(List.of(), neovim.sent, "not a plain key: the typed text goes first");
    }

    @Test
    void insertCtrlLettersLeftToEclipseAreLeftAlone() {
        neovim.typing = true;
        router.setInsertCtrlKeyFilter(letter -> letter == 'w');
        assertEquals(List.of(false), type("<C-v>"));
        assertEquals(List.of(), neovim.synced);
        assertEquals(List.of(), neovim.sent);
    }

    /** No letter goes to Neovim in insert mode until the editor chooses them. */
    @Test
    void byDefaultNoInsertCtrlLetterIsASyncPoint() {
        neovim.typing = true;
        assertEquals(List.of(false), type("<C-w>"));
        assertEquals(List.of(), neovim.synced);
    }

    /** {@code Ctrl-W} then {@code x} typed at once: the x goes to Neovim after the Ctrl-W, never before it. */
    @Test
    void aKeyRightAfterASyncPointGoesToNeovimInOrder() {
        neovim.typing = true;
        router.setInsertCtrlKeyFilter(letter -> letter == 'w');
        assertEquals(List.of(true, true), type("<C-w>", "x"));
        assertEquals(List.of("<C-w>"), neovim.synced);
        assertEquals(List.of("x"), neovim.sent);
        assertTrue(neovim.neovimDriven);
    }

    /** A key binding delivered it: Eclipse has consumed it, so it is Neovim's whatever the filter says now. */
    @Test
    void aBoundKeyInAnInsertEclipseTypesIsASyncPoint() {
        neovim.typing = true;
        router.routeBound("<C-u>");
        assertEquals(List.of("<C-u>"), neovim.synced);
    }

    @Test
    void aBoundKeyOutsideAnInsertEclipseTypesIsAPlainKey() {
        router.setCtrlKeyFilter(letter -> false);
        router.routeBound("<C-f>");
        neovim.typing = true;
        neovim.neovimDriven = true;
        router.routeBound("<C-o>");
        assertEquals(List.of("<C-f>", "<C-o>"), neovim.sent);
        assertEquals(List.of(), neovim.synced);
    }

    // ---- special keys: Eclipse's completions, Ctrl-C (INSERT_CTRL_KEYS.md, phase D) ----

    private final List<KeyRouter.Completion> completions = new ArrayList<>();

    private void eclipseTypes(String insertLetters) {
        neovim.typing = true;
        router.setInsertCtrlKeyFilter(letter -> insertLetters.indexOf(letter) >= 0);
        router.setCompletion(completions::add);
    }

    /** Neovim's popup is not drawn: Ctrl-N and Ctrl-P run Eclipse's Word Completion instead. */
    @Test
    void ctrlNAndCtrlPRunWordCompletion() {
        eclipseTypes("np");
        assertEquals(List.of(true, true), type("<C-n>", "<C-p>"));
        assertEquals(List.of(KeyRouter.Completion.WORD, KeyRouter.Completion.WORD), completions);
        assertEquals(List.of(), neovim.synced);
        assertEquals(List.of(), neovim.sent);
    }

    @Test
    void ctrlXCtrlORunsContentAssist() {
        eclipseTypes("xo");
        assertEquals(List.of(true, true), type("<C-x>", "<C-o>"));
        assertEquals(List.of(KeyRouter.Completion.CONTENT_ASSIST), completions);
        assertEquals(List.of(), neovim.synced, "not Ctrl-O's one normal command");
    }

    /** Ctrl-X then Ctrl-N, as Vim's keyword completion: Word Completion too. */
    @Test
    void ctrlXCtrlNRunsWordCompletion() {
        eclipseTypes("x");
        assertEquals(List.of(true, true), type("<C-x>", "<C-n>"));
        assertEquals(List.of(KeyRouter.Completion.WORD), completions);
    }

    /** Only the very next key: Ctrl-X then a letter is typed as usual, and a later Ctrl-O is Ctrl-O again. */
    @Test
    void ctrlXChoosesOnlyTheNextKey() {
        eclipseTypes("xo");
        assertEquals(List.of(true, false, true), type("<C-x>", "a", "<C-o>"));
        assertEquals(List.of(), completions);
        assertEquals(List.of("<C-o>"), neovim.synced);
    }

    /** Ctrl-X Ctrl-O works also when the Ctrl-O came through its key binding. */
    @Test
    void ctrlXThenABoundCtrlORunsContentAssist() {
        eclipseTypes("x");
        type("<C-x>");
        router.routeBound("<C-o>");
        assertEquals(List.of(KeyRouter.Completion.CONTENT_ASSIST), completions);
    }

    @Test
    void ctrlCEndsTheInsertAsEscDoes() {
        eclipseTypes("c");
        InsertReplay.Plan plan = new InsertReplay.Plan(List.of("abc"), 1, 0, 1, 3);
        router.setInsertReplaySupplier(() -> plan);
        assertEquals(List.of(true), type("<C-c>"));
        assertEquals(List.of(plan), neovim.leftInsert);
        assertEquals(List.of(), neovim.synced);
    }

    /** In an insert Neovim drives, Neovim types: the keys are its own there, Eclipse cannot complete. */
    @Test
    void inAnInsertNeovimDrivesTheSpecialKeysGoToNeovim() {
        eclipseTypes("npxc");
        neovim.neovimDriven = true;
        type("<C-n>", "<C-x>", "<C-c>");
        assertEquals(List.of("<C-n>", "<C-x>", "<C-c>"), neovim.sent);
        assertEquals(List.of(), completions);
    }
}
