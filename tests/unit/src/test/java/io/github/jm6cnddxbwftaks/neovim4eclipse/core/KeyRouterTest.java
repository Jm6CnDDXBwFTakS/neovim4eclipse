// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
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
        boolean waitingForArgument;
        /** Keys sent but not yet processed by Neovim: {@link #waitingForArgument} is then out of date. */
        boolean keysInFlight;
        boolean recording;
        String mode = "n";
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
        public boolean isWaitingForArgument() {
            return waitingForArgument;
        }

        @Override
        public String getMode() {
            return mode;
        }

        @Override
        public boolean hasKeysInFlight() {
            return keysInFlight;
        }

        @Override
        public boolean isRecording() {
            return recording;
        }

        @Override
        public void driveInsertFromNeovim() {
            neovimDriven = true;
        }

        @Override
        public void inputAsync(String keys) {
            sent.add(keys);
            // As in NeovimSession: until Neovim has processed the key, its answers (mode, waiting
            // for an argument) are about earlier keys. A test sets it back to false for "caught up".
            keysInFlight = true;
        }

        @Override
        public void leaveInsertModeAsync(InsertReplay.Plan replay) {
            leftInsert.add(replay);
        }
    }

    private final FakeNeovim neovim = new FakeNeovim();
    private final KeyRouter router = new KeyRouter(neovim);
    private final List<String> prompts = new ArrayList<>();
    private final List<String> fileCommands = new ArrayList<>();

    @BeforeEach
    void wireHost() {
        router.setPromptHandler(key -> prompts.add(key));
        router.setFileCommands(new KeyRouter.FileCommands() {
            @Override
            public void saveAndClose() {
                fileCommands.add("ZZ");
            }

            @Override
            public void close() {
                fileCommands.add("ZQ");
            }
        });
    }

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
        assertEquals(List.of(), prompts);
    }

    @Test
    void theKeyAfterACommandThatTakesAnArgumentGoesToNeovim() {
        type("f", ":");
        assertEquals(List.of("f", ":"), neovim.sent);
        assertEquals(List.of(), prompts);
    }

    /**
     * {@code ggqa ... q:} typed faster than Neovim processes it: Neovim's answer "waiting for a key"
     * is still the one from the first {@code g} when {@code q} arrives, but the second {@code g} has
     * already been sent as that key. The {@code q} starts a recording, so the last {@code q} stops it
     * and {@code :} opens the prompt (and does not become {@code q:}, the command-line window).
     */
    @Test
    void anOutOfDateWaitingAnswerDoesNotTakeTheNextKey() {
        neovim.waitingForArgument = true; // Neovim's answer after the first g
        neovim.keysInFlight = true; // but the second g is already on its way
        type("q", "a", "j", "q", ":");
        assertEquals(List.of("q", "a", "j", "q"), neovim.sent);
        assertEquals(List.of(":"), prompts);
    }

    @Test
    void theArgumentIsOnlyOneKey() {
        type("f", "x", ":");
        assertEquals(List.of("f", "x"), neovim.sent);
        assertEquals(List.of(":"), prompts);
    }

    @Test
    void neovimWaitingForAnArgumentAlsoSendsTheKey() {
        neovim.waitingForArgument = true;
        type("/");
        assertEquals(List.of("/"), neovim.sent);
        assertEquals(List.of(), prompts);
    }

    @Test
    void qEndingARecordingTakesNoArgument() {
        neovim.recording = true;
        type("q", ":");
        assertEquals(List.of("q"), neovim.sent);
        assertEquals(List.of(":"), prompts);
    }

    /**
     * {@code qa ... q:} typed faster than Neovim reports that a recording started: the second
     * {@code q} still stops it, so {@code :} opens the prompt instead of going to Neovim as a register.
     */
    @Test
    void aQuickQAfterQaStopsTheRecordingBeforeNeovimReportsIt() {
        type("q", "a", "j", "q", ":");
        assertEquals(List.of("q", "a", "j", "q"), neovim.sent);
        assertEquals(List.of(":"), prompts);
    }

    @Test
    void qColonStartsNoRecording() {
        type("q", ":"); // the command-line window, not a recording
        type("q", ":");
        assertEquals(List.of("q", ":", "q", ":"), neovim.sent);
        assertEquals(List.of(), prompts);
    }

    /** Once Neovim reports the same as the keys said, its answer counts again. */
    @Test
    void neovimsAnswerCountsAgainOnceItAgrees() {
        type("q", "a");
        neovim.recording = true; // Neovim has caught up
        type("j"); // routed while both agree
        neovim.recording = false; // and the recording ended some other way
        type("q", ":");
        assertEquals(List.of("q", "a", "j", "q", ":"), neovim.sent, "q starts a recording again, so : is its register");
        assertEquals(List.of(), prompts);
    }

    @Test
    void untilNeovimAgreesTheKeysCount() {
        type("q", "a", "q"); // started and stopped faster than Neovim answers
        neovim.recording = true; // Neovim's late answer about the start
        type("q", ":");
        assertEquals(List.of("q", "a", "q", "q", ":"), neovim.sent, "the third q starts a new recording");
        assertEquals(List.of(), prompts);
    }

    @Test
    void aKeySentElsewhereLetsNeovimsAnswerCountAgain() {
        type("q", "a");
        router.sentElsewhere("<C-l>");
        type("q", ":");
        assertEquals(List.of("q", "a", "q", ":"), neovim.sent, "Neovim says not recording: q takes : as register");
    }

    // ---- what Vim's grammar makes of a key (util/KeyGrammar), typed faster than Neovim answers ----

    /** After {@code di} the quote names the text object; it takes no argument, so {@code :} opens the prompt. */
    @Test
    void theColonAfterATextObjectOpensThePrompt() {
        for (String quote : new String[] { "\"", "'", "`" }) {
            prompts.clear();
            type("d", "i", quote, ":");
            assertEquals(List.of(":"), prompts, "di" + quote + ":");
        }
        prompts.clear();
        type("v", "i", "\"", ":");
        assertEquals(List.of(":"), prompts, "vi\":");
    }

    /** {@code gqq} formats the line; its last {@code q} starts no recording. */
    @Test
    void gqqStartsNoRecording() {
        type("g", "q", "q", ":");
        assertEquals(List.of("g", "q", "q"), neovim.sent);
        assertEquals(List.of(":"), prompts);
    }

    /** {@code <C-w>q} closes a window; that {@code q} starts no recording either. */
    @Test
    void ctrlWqStartsNoRecording() {
        type("<C-w>", "q", ":");
        assertEquals(List.of(":"), prompts);
    }

    /** {@code [/} and {@code ]/} are motions (to a comment's start or end), not searches. */
    @Test
    void theSlashAfterABracketGoesToNeovim() {
        type("[", "/", "d", "]", "/");
        assertEquals(List.of("[", "/", "d", "]", "/"), neovim.sent);
        assertEquals(List.of(), prompts);
    }

    /** A {@code :} typed right after {@code o} is text in the new line, even before Neovim reports insert mode. */
    @Test
    void aColonTypedRightAfterAnInsertKeyIsText() {
        type("o", ":");
        assertEquals(List.of("o", ":"), neovim.sent);
        assertEquals(List.of(), prompts);
    }

    // ---- Neovim's answer, once it has caught up, replaces the guess ----

    /** A half-typed mapping ({@code <Space>} of {@code <Space>s}): Neovim waits, so {@code :} belongs to it. */
    @Test
    void neovimWaitingAfterAKeyTheGrammarDoesNotKnow() {
        type("<Space>");
        neovim.keysInFlight = false; // Neovim has processed it ...
        neovim.waitingForArgument = true; // ... and waits for the rest of the mapping
        type(":");
        assertEquals(List.of("<Space>", ":"), neovim.sent);
        assertEquals(List.of(), prompts);
    }

    /** Neovim reports an operator the keys did not show (a mapping to {@code d}): {@code /} is its search motion. */
    @Test
    void neovimsOperatorPendingMakesTheSlashASearchMotion() {
        neovim.mode = "no";
        type("/");
        assertEquals(List.of("/"), prompts);
    }

    /** Neovim back in normal mode undoes a wrong guess: here an {@code f} whose argument Eclipse took. */
    @Test
    void neovimsNormalModeEndsAGuessedArgument() {
        type("f");
        neovim.keysInFlight = false; // Neovim has processed f and is no longer waiting
        type(":");
        assertEquals(List.of(":"), prompts);
    }

    // ---- g and the ROT13 operator g? ----

    /**
     * {@code g??} encodes the current line with ROT13 ({@code g?} doubled, like {@code dd}). Neither
     * {@code ?} is a backward search, even typed faster than Neovim answers (keys in flight, so its
     * "waiting for a key" is out of date): the second {@code ?} once opened the search prompt.
     */
    @Test
    void gQuestionQuestionGoesToNeovimEvenTypedQuickly() {
        neovim.keysInFlight = true;
        assertEquals(List.of(true, true, true), type("g", "?", "?"));
        assertEquals(List.of("g", "?", "?"), neovim.sent);
        assertEquals(List.of(), prompts);
    }

    @Test
    void gQuestionGQuestionAlsoGoesToNeovim() {
        type("g", "?", "g", "?");
        assertEquals(List.of("g", "?", "g", "?"), neovim.sent);
        assertEquals(List.of(), prompts);
    }

    /** After {@code g?} a {@code /} is the operator's motion, a forward search: the prompt opens. */
    @Test
    void aSearchAfterGQuestionStillOpensThePrompt() {
        type("g", "?", "/");
        assertEquals(List.of("g", "?"), neovim.sent);
        assertEquals(List.of("/"), prompts);
    }

    /** In visual mode {@code g?} encodes the selection at once, so the next {@code ?} is a search. */
    @Test
    void inVisualModeTheQuestionAfterGQuestionIsASearch() {
        neovim.mode = "v";
        type("g", "?", "?");
        assertEquals(List.of("g", "?"), neovim.sent);
        assertEquals(List.of("?"), prompts);
    }

    /** No {@code g} command is a prompt: the key after {@code g} goes to Neovim, whatever it is. */
    @Test
    void thePromptKeyAfterGGoesToNeovim() {
        neovim.keysInFlight = true;
        type("g", ":");
        assertEquals(List.of("g", ":"), neovim.sent);
        assertEquals(List.of(), prompts);
    }

    /** {@code gg} ends the {@code g} command, so a {@code :} after it opens the prompt again. */
    @Test
    void afterACompleteGCommandPromptKeysOpenThePrompt() {
        type("g", "g", ":");
        assertEquals(List.of("g", "g"), neovim.sent);
        assertEquals(List.of(":"), prompts);
    }

    @Test
    void promptKeysOpenTheEditorsPrompt() {
        assertEquals(List.of(true, true, true), type(":", "/", "?"));
        assertEquals(List.of(":", "/", "?"), prompts);
        assertEquals(List.of(), neovim.sent);
    }

    @Test
    void aPromptKeyTheHandlerDeclinesGoesToNeovim() {
        router.setPromptHandler(key -> false);
        assertEquals(List.of(true), type(":"));
        assertEquals(List.of(":"), neovim.sent);
    }

    @Test
    void withoutAPromptHandlerPromptKeysGoToNeovim() {
        router.setPromptHandler(null);
        type("/");
        assertEquals(List.of("/"), neovim.sent);
    }

    @Test
    void zzSavesAndCloses() {
        assertEquals(List.of(true, true), type("Z", "Z"));
        assertEquals(List.of("ZZ"), fileCommands);
        assertEquals(List.of(), neovim.sent);
    }

    @Test
    void zqAndZqLowercaseClose() {
        type("Z", "Q", "Z", "q");
        assertEquals(List.of("ZQ", "ZQ"), fileCommands);
        assertEquals(List.of(), neovim.sent);
    }

    @Test
    void zFollowedByAnotherKeyReplaysBothToNeovim() {
        type("Z", "x");
        assertEquals(List.of("Z", "x"), neovim.sent);
        assertEquals(List.of(), fileCommands);
    }

    @Test
    void withoutFileCommandsZGoesStraightToNeovim() {
        router.setFileCommands(null);
        type("Z", "Z");
        assertEquals(List.of("Z", "Z"), neovim.sent);
    }

    @Test
    void ctrlLettersGivenToEclipseAreLeftAlone() {
        router.setCtrlKeyFilter(letter -> letter != 'w');
        assertEquals(List.of(false, true), type("<C-w>", "<C-o>"));
        assertEquals(List.of("<C-o>"), neovim.sent);
    }

    @Test
    void aCtrlLetterAfterZIsReplayedEvenIfGivenToEclipse() {
        router.setCtrlKeyFilter(letter -> letter != 'w');
        type("Z", "<C-w>");
        assertEquals(List.of("Z", "<C-w>"), neovim.sent); // Z<C-w> is one Neovim command, not two keys
    }

    @Test
    void aZTypedAsAnArgumentDoesNotStartZZ() {
        type("f", "Z", "Z"); // fZ jumps to the next Z; the second Z only starts a new pending Z
        assertEquals(List.of("f", "Z"), neovim.sent);
        assertEquals(List.of(), fileCommands);
        type("Z");
        assertEquals(List.of("ZZ"), fileCommands);
    }

    @Test
    void aCommandAfterZIsNotWaitingForAnArgument() {
        type("Z", "f", ":"); // Zf is replayed as two keys; the f there does not take ":" as its argument
        assertEquals(List.of("Z", "f"), neovim.sent);
        assertEquals(List.of(":"), prompts);
    }

    /**
     * A Ctrl key sent by its command reaches Neovim like a typed one: after {@code f} it is the
     * argument, so the next {@code :} opens the prompt; a held-back {@code Z} is dropped.
     */
    @Test
    void aKeySentElsewhereCountsAsTyped() {
        type("f");
        router.sentElsewhere("<C-x>");
        type(":");
        assertEquals(List.of(":"), prompts);

        type("Z");
        router.sentElsewhere("<C-x>");
        type("x");
        assertEquals(List.of("f", "x"), neovim.sent);
        assertFalse(fileCommands.contains("ZZ"));
    }

    /** {@code <C-w>} sent by its command is a prefix: the {@code :} after it belongs to it. */
    @Test
    void aPrefixSentElsewhereTakesTheNextKey() {
        router.sentElsewhere("<C-w>");
        neovim.keysInFlight = true; // the controller sends it to Neovim right after
        type(":");
        assertEquals(List.of(":"), neovim.sent);
        assertEquals(List.of(), prompts);
    }
}
