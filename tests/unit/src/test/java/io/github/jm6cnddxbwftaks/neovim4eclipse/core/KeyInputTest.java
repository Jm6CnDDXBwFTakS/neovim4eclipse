// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Mode;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ChangedLines;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;

/**
 * The key-input pipeline without Neovim. A scripted fake plays Neovim: each key may switch it to a
 * new mode ({@link ScriptedNeovim#after}), and every call is recorded in order, so a test reads as
 * "typing these keys makes these calls and the editor hears this".
 *
 * <p>{@link KeyInput} runs on a real {@link StateSync} and {@link StatusTracker}; the fake answers
 * for both. Recorded calls look like {@code "type x"}, {@code "snapshot"} (the per-key state read),
 * {@code "bufferLines"} (a full sync), {@code "setCursor 1:2"}. What the editor hears is in
 * {@link #heard}: {@code "mode i"}, {@code "buffer [text]"}.
 *
 * <p>These tests check the order of requests, not what Neovim does with them; the integration tests
 * ({@code NeovimSessionIntegrationTest}) run the same paths against a real {@code nvim}.
 */
class KeyInputTest {

    /** Neovim as far as KeyInput and StateSync can see it. */
    private final class ScriptedNeovim implements KeyInput.Neovim, StateSync.Neovim {
        final List<String> calls = new ArrayList<>();
        /** Mode Neovim is in after each key; keys not listed leave the mode as it is. */
        final Map<String, Mode> after = new HashMap<>();
        Mode mode = new Mode("n", false);
        List<String> lines = List.of("text");
        /** Typing this key throws (a lost connection). */
        String failingKey;
        /** Typing this key makes Neovim send a buffer line event (as editing in Neovim does). */
        String keyWithLineEvent;
        boolean bufferLinesFail;
        /** Re-typing this text finds Neovim outside an insert (a timer's stopinsert): nothing is typed. */
        String refusedText;

        @Override
        public void type(String keys) throws Exception {
            calls.add("type " + keys);
            if (keys.equals(failingKey)) {
                throw new IllegalStateException("connection lost");
            }
            if (keys.equals(keyWithLineEvent)) {
                state.dispatchBufferChange(0, 1, List.of("event for " + keys));
            }
            mode = after.getOrDefault(keys, mode);
        }

        @Override
        public boolean typeText(String text) throws Exception {
            if (text.equals(refusedText)) {
                calls.add("feed " + text + " refused");
                return false;
            }
            try {
                type(text); // the same, recorded as "feed": raw text, with no mapping applied
            } finally {
                calls.set(calls.size() - 1, "feed " + text);
            }
            return true;
        }

        @Override
        public void awaitKeysConsumed() {
            calls.add("keysConsumed");
        }

        @Override
        public List<String> bufferLines(Duration timeout) throws Exception {
            calls.add("bufferLines after macro");
            if (bufferLinesFail) {
                throw new IllegalStateException("macro still running");
            }
            return lines;
        }

        @Override
        public void setCursor(int line1, int col0) {
            calls.add("setCursor " + line1 + ":" + col0);
        }

        @Override
        public void mouse(MouseInput event) {
            calls.add("mouse " + event.action().luaName() + " " + event.line1() + ":" + event.col0());
            mode = after.getOrDefault("mouse " + event.action().luaName(), mode);
        }

        @Override
        public Mode getMode() {
            return mode;
        }

        @Override
        public Object stateSnapshot(int statuslineWidth, boolean folds) {
            calls.add("snapshot");
            Object[] raw = new Object[StateSnapshot.BUFNR + 1];
            Arrays.fill(raw, "");
            raw[StateSnapshot.CURSOR_LINE] = 1;
            raw[StateSnapshot.CURSOR_COL] = 0;
            raw[StateSnapshot.RECORDING] = recording;
            raw[StateSnapshot.BUFNR] = 1;
            return Arrays.asList(raw);
        }

        @Override
        public List<String> bufferLines() {
            calls.add("bufferLines");
            return lines;
        }
    }

    /** Hears mode and buffer changes, as the editor does. */
    private final class Editor implements StateSync.Listeners {
        final List<BufferChange> changes = new ArrayList<>();

        @Override
        public void mode(String mode) {
            heard.add("mode " + mode);
        }

        @Override
        public void cursor(CursorPos cursor) {
        }

        @Override
        public void selection(VisualSelection selection) {
        }

        @Override
        public void folds(FoldState folds) {
        }

        @Override
        public void bufferChange(BufferChange change) {
            heard.add("buffer " + change.newLines());
            changes.add(change);
        }
    }

    private final List<String> heard = new ArrayList<>();
    private final ScriptedNeovim nvim = new ScriptedNeovim();
    private final Editor editor = new Editor();
    private final StatusTracker status = new StatusTracker();
    /** The register being recorded into, as the state snapshot reports it ("" = not recording). */
    private String recording = "";
    private Runnable push = () -> nvim.calls.add("push document");
    private final StateSync state = new StateSync(nvim, editor, status, (winid, bufnr) -> bufnr == 1, this::modeChanged);
    private final KeyInput keys = new KeyInput(nvim, state, status, () -> push, Duration.ofMillis(50));

    /** Wired like the session: every mode change reaches KeyInput first. */
    private void modeChanged(String mode) {
        keys.onModeChanged(mode);
    }

    private void type(String... sequence) throws Exception {
        for (String key : sequence) {
            keys.input(key);
        }
    }

    // ---- ordinary keys ----

    @Test
    void aMotionIsTypedThenTheStateIsRead() throws Exception {
        type("j");

        assertEquals(List.of("type j", "snapshot"), nvim.calls);
    }

    @Test
    void aKeyThatChangesTextAlsoSyncsTheBuffer() throws Exception {
        type("x");

        assertEquals(List.of("type x", "bufferLines", "snapshot"), nvim.calls);
    }

    /** Neovim shows the pending command itself ({@code msg_showcmd}, see StatusTrackerTest). */
    @Test
    void whileNeovimWaitsForTheRestOfACommandNothingIsAsked() throws Exception {
        nvim.after.put("f", new Mode("n", true)); // waiting for the character

        type("f");

        assertEquals(List.of("type f"), nvim.calls);
    }

    // ---- insert mode ----

    @Test
    void enteringInsertSyncsTheBufferAndEclipseMayTypeOnceItIsApplied() throws Exception {
        nvim.after.put("i", new Mode("i", false));

        type("i");

        assertEquals(List.of("type i", "bufferLines", "snapshot"), nvim.calls);
        assertFalse(keys.isNeovimDrivenInsert());
        editor.changes.get(0).afterApply().run(); // the editor applied the sync
        assertTrue(state.isEclipseTypingReady());
    }

    @Test
    void anInsertStartedWhileRecordingAMacroIsDrivenByNeovim() throws Exception {
        recording = "q";
        type("j"); // the snapshot reports the recording
        nvim.after.put("i", new Mode("i", false));

        type("i");

        assertTrue(keys.isNeovimDrivenInsert(), "every typed key must reach the macro");
    }

    @Test
    void leavingInsertModeEndsANeovimDrivenInsert() throws Exception {
        nvim.after.put("i", new Mode("i", false));
        nvim.after.put("<Esc>", new Mode("n", false));
        type("i");
        keys.driveFromNeovim(); // a key arrived before Eclipse could type

        type("<Esc>");

        assertFalse(keys.isNeovimDrivenInsert());
    }

    // ---- leaving insert mode after Eclipse typed ----

    @Test
    void leavingInsertReplaysTheTypedTextThenPushesTheDocument() throws Exception {
        nvim.keyWithLineEvent = "abc"; // Neovim's echo of the replay: must not reach the editor
        InsertReplay.Plan plan = new InsertReplay.Plan(List.of("abc"), 1, 0, 1, 2);

        keys.leaveInsertMode(plan);

        assertEquals(List.of("setCursor 1:0", "feed abc", "keysConsumed", "type <Esc>", "keysConsumed",
                "push document", "setCursor 1:2", "snapshot"), nvim.calls);
        assertEquals(List.of("mode n"), heard, "the replayed text's line event is not applied twice");
    }

    @Test
    void escIsBeingHandledFromTheReplayToThePushAndNotAfter() throws Exception {
        nvim.keyWithLineEvent = "abc";
        push = () -> nvim.calls.add("push, leaving " + keys.isLeavingInsertMode());
        assertFalse(keys.isLeavingInsertMode());

        keys.leaveInsertMode(new InsertReplay.Plan(List.of("abc"), 1, 0, 1, 2));

        assertTrue(nvim.calls.contains("push, leaving true"),
                "Neovim's top lines from the replay are ignored until the push: " + nvim.calls);
        assertFalse(keys.isLeavingInsertMode(), "Neovim's top lines count again");
    }

    @Test
    void theViewStepRunsOnceTheCursorIsFinal() throws Exception {
        nvim.keyWithLineEvent = "abc";
        keys.setLeaveInsertView(() -> nvim.calls.add("view"));

        keys.leaveInsertMode(new InsertReplay.Plan(List.of("abc"), 1, 0, 1, 2));
        assertEquals(List.of("setCursor 1:0", "feed abc", "keysConsumed", "type <Esc>", "keysConsumed",
                "push document", "setCursor 1:2", "keysConsumed", "view", "snapshot"), nvim.calls,
                "after the replay's cursor: a dragged cursor is not moved back");

        nvim.calls.clear();
        keys.leaveInsertMode(null);
        assertEquals(List.of("push document", "type <Esc>", "keysConsumed", "view", "snapshot"), nvim.calls,
                "after the <Esc> has moved the cursor");
    }

    @Test
    void withoutAReplayPlanTheDocumentIsPushedBeforeEsc() throws Exception {
        keys.leaveInsertMode(null);

        assertEquals(List.of("push document", "type <Esc>", "snapshot"), nvim.calls);
    }

    @Test
    void aFailedReplayFallsBackToPushThenEscAndResumesBufferEvents() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(KeyInput.class)) {
            nvim.failingKey = "abc";

            keys.leaveInsertMode(new InsertReplay.Plan(List.of("abc"), 1, 0, 1, 2));

            assertEquals(List.of("setCursor 1:0", "feed abc", "push document", "type <Esc>", "snapshot"),
                    nvim.calls);
            state.dispatchBufferChange(0, 1, List.of("later"));
            assertEquals(List.of("mode n", "buffer [later]"), heard);
            log.assertLogged(Level.WARNING, "replaying typed text into Neovim failed");
        }
    }

    /**
     * Neovim left the insert behind Eclipse's back (a timer's {@code stopinsert}): the typed text is
     * not re-typed, since in Normal mode a pasted {@code :!cmd} would run. The document push makes
     * the text right; only {@code .} misses it.
     */
    @Test
    void aReplayOutsideInsertModeTypesNothingAndFallsBackToPushThenEsc() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(KeyInput.class)) {
            nvim.refusedText = ":!rm -rf x";

            keys.leaveInsertMode(new InsertReplay.Plan(List.of(":!rm -rf x", "more"), 1, 0, 1, 2));

            assertEquals(List.of("setCursor 1:0", "feed :!rm -rf x refused", "push document", "type <Esc>",
                    "snapshot"), nvim.calls, "no later chunk is typed either");
            state.dispatchBufferChange(0, 1, List.of("later"));
            assertEquals(List.of("mode n", "buffer [later]"), heard, "buffer events count again");
            log.assertLogged(Level.WARNING, "Neovim was no longer in insert mode");
        }
    }

    // ---- Enter on Neovim's own : command line ----

    /**
     * Enter on a {@code :} line is typed like any key, {@code :q} too: Neovim hands a command Eclipse
     * carries out back as {@code :Eclipse quit} (LuaScripts.HOST_COMMANDS). No buffer sync follows:
     * Eclipse runs such a command later (a revert, {@code :e!}, must not be overwritten).
     */
    // ---- sync points: a Ctrl key Neovim handles while Eclipse types ----

    /** "foo bar" then Ctrl-W: the text into Neovim's open insert (no Esc), push, caret, view, then the key. */
    @Test
    void aSyncPointHandsTheTypedTextOverThenSendsTheKeyInTheSameInsert() throws Exception {
        nvim.mode = new Mode("i", false);
        nvim.keyWithLineEvent = "foo bar"; // the re-typing's echo: Eclipse has that text already
        keys.setLeaveInsertView(() -> nvim.calls.add("view"));
        InsertReplay.Plan typed = new InsertReplay.Plan(List.of("foo bar"), 1, 2, 1, 9);

        keys.syncInsertThenInput(new InsertReplay.SyncPoint(typed, 1, 9), "<C-w>");

        assertEquals(List.of("setCursor 1:2", "feed foo bar", "keysConsumed", "push document", "setCursor 1:9",
                "keysConsumed", "view", "type <C-w>", "keysConsumed", "snapshot", "bufferLines"), nvim.calls);
        assertFalse(nvim.calls.contains("type <Esc>"), "the insert goes on");
        assertFalse(heard.contains("buffer [event for foo bar]"), "not applied twice");
        assertFalse(keys.isNeovimDrivenInsert());
        editor.changes.get(editor.changes.size() - 1).afterApply().run(); // the editor applied the sync
        assertTrue(state.isEclipseTypingReady(), "Eclipse types again");
    }

    /**
     * At a sync point Neovim turns out to be in Normal mode: the typed text is not re-typed and the
     * key is not sent either (Normal-mode Ctrl-W is a window command); the document is still pushed
     * and the editor hears the real mode.
     */
    @Test
    void aSyncPointOutsideInsertModeSendsNeitherTextNorKey() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(KeyInput.class)) {
            nvim.refusedText = "foo bar";
            InsertReplay.Plan typed = new InsertReplay.Plan(List.of("foo bar"), 1, 2, 1, 9);

            keys.syncInsertThenInput(new InsertReplay.SyncPoint(typed, 1, 9), "<C-w>");

            assertEquals(List.of("setCursor 1:2", "feed foo bar refused", "push document", "setCursor 1:9",
                    "snapshot"), nvim.calls);
            assertTrue(heard.contains("mode n"), "the editor hears Normal mode: " + heard);
            log.assertLogged(Level.WARNING, "the key was not sent");
        }
    }

    /** In a long document the key's line events are enough: Eclipse types once they are applied, without a full sync. */
    @Test
    void aSyncPointInALongDocumentWaitsOnlyForTheKeysEvents() throws Exception {
        nvim.mode = new Mode("i", false);
        int lines = ChangedLines.COMPARE_WHOLE_UP_TO_LINES + 1;

        keys.syncInsertThenInput(new InsertReplay.SyncPoint(null, 1, 0, lines), "<C-w>");

        assertFalse(nvim.calls.contains("bufferLines"), nvim.calls.toString());
        assertTrue(lastChange().changesNothing(), "a marker after the key's events");
        lastChange().afterApply().run();
        assertTrue(state.isEclipseTypingReady());
    }

    /** Ctrl-W's own change is Neovim's, so it reaches the editor. */
    @Test
    void theKeysChangeReachesTheEditor() throws Exception {
        nvim.mode = new Mode("i", false);
        nvim.keyWithLineEvent = "<C-w>";

        keys.syncInsertThenInput(new InsertReplay.SyncPoint(null, 1, 0), "<C-w>");

        assertTrue(heard.contains("buffer [event for <C-w>]"), heard.toString());
    }

    @Test
    void aSyncPointIsHandledAsLeavingUntilTheKey() throws Exception {
        nvim.mode = new Mode("i", false);
        push = () -> nvim.calls.add("push, leaving " + keys.isLeavingInsertMode());

        keys.syncInsertThenInput(new InsertReplay.SyncPoint(null, 1, 0), "<C-u>");

        assertTrue(nvim.calls.contains("push, leaving true"), "the re-typing's cursors are dropped: " + nvim.calls);
        assertFalse(keys.isLeavingInsertMode());
    }

    /** Ctrl-R waits for a register name: Neovim drives the rest of the insert, and nothing is asked. */
    @Test
    void aKeyNeovimWaitsAfterLetsNeovimDriveTheRestOfTheInsert() throws Exception {
        nvim.mode = new Mode("i", false);
        nvim.after.put("<C-r>", new Mode("i", true));

        keys.syncInsertThenInput(new InsertReplay.SyncPoint(null, 1, 0), "<C-r>");

        assertEquals(List.of("push document", "setCursor 1:0", "type <C-r>"), nvim.calls);
        assertTrue(keys.isNeovimDrivenInsert());
    }

    /**
     * Ctrl-R then {@code a}: Neovim inserts register a and is in plain insert mode again, so typing
     * goes back to Eclipse with the insert-start handshake (a full sync, Eclipse types once applied).
     */
    @Test
    void onceNeovimHasTheKeyItWaitedForEclipseTypesAgain() throws Exception {
        ctrlRWaitingForARegister();
        nvim.after.put("a", new Mode("i", false));

        queue("a");

        assertTrue(nvim.calls.contains("bufferLines"), "the handshake: " + nvim.calls);
        assertTrue(keys.isNeovimDrivenInsert(), "Neovim drives until the editor has applied the sync");
        lastChange().afterApply().run(); // the editor applied the sync
        assertFalse(keys.isNeovimDrivenInsert());
        assertFalse(keys.isHandBackPending());
        assertTrue(state.isEclipseTypingReady(), "Eclipse types again");
    }

    /** Ctrl-K e: Neovim still waits for the digraph's second letter: no hand-back yet, and nothing asked. */
    @Test
    void whileNeovimStillWaitsTheInsertStaysNeovims() throws Exception {
        ctrlRWaitingForARegister();
        nvim.after.put("<C-r>", new Mode("i", true)); // Ctrl-R Ctrl-R waits for the register too

        queue("<C-r>");

        assertFalse(nvim.calls.contains("bufferLines"), nvim.calls.toString());
        assertTrue(keys.isNeovimDrivenInsert());
        assertTrue(keys.isHandBackPending());
    }

    /** {@code a} then {@code b} typed fast: {@code b} waits behind {@code a}, so {@code a} does not hand back; {@code b} does. */
    @Test
    void aKeyThatWaitsInTheQueuePutsTheHandBackOffToTheLastKey() throws Exception {
        ctrlRWaitingForARegister();
        nvim.after.put("a", new Mode("i", false));
        keys.keyQueued(); // a
        keys.keyQueued(); // b, queued behind it

        keys.inputQueued("a");
        assertFalse(nvim.calls.contains("bufferLines"), "a key waits: " + nvim.calls);
        keys.inputQueued("b");

        assertTrue(nvim.calls.contains("bufferLines"), nvim.calls.toString());
        lastChange().afterApply().run();
        assertFalse(keys.isNeovimDrivenInsert());
    }

    /** A key queued after the read and before the editor applied the sync: Neovim keeps the insert, the next key retries. */
    @Test
    void aKeyQueuedBeforeTheSyncIsAppliedCallsTheHandBackOff() throws Exception {
        ctrlRWaitingForARegister();
        nvim.after.put("a", new Mode("i", false));
        queue("a");

        keys.keyQueued(); // the user typed b; it goes to Neovim, which drives
        lastChange().afterApply().run();

        assertTrue(keys.isNeovimDrivenInsert(), "b reaches Neovim after the read: Eclipse must not type yet");
        assertFalse(state.isEclipseTypingReady());
        assertTrue(keys.isHandBackPending(), "the next key retries");
        keys.inputQueued("b");
        lastChange().afterApply().run();
        assertFalse(keys.isNeovimDrivenInsert());
    }

    /** A letter typed in the millisecond before Eclipse had Neovim's text went to Neovim; then Eclipse types again. */
    @Test
    void aKeyTypedBeforeEclipseMayTypeIsHandedBackOnceNeovimHasIt() throws Exception {
        nvim.mode = new Mode("i", false);
        state.readMode(); // in an insert Eclipse types
        keys.driveFromNeovim(); // the key router: a key came before the insert's sync was applied
        nvim.calls.clear();

        queue("x");

        assertTrue(nvim.calls.contains("bufferLines"), nvim.calls.toString());
        lastChange().afterApply().run();
        assertFalse(keys.isNeovimDrivenInsert());
        assertTrue(state.isEclipseTypingReady());
    }

    /**
     * {@code ihello<Esc>ibye} typed fast: the second {@code i} enters insert mode while {@code b}
     * (and more) still wait in the queue for Neovim. The insert's sync must not let Eclipse type:
     * Neovim's events for those keys come after it. Neovim keeps the insert; the last key hands it
     * back.
     */
    @Test
    void anInsertEnteredWhileKeysWaitStaysNeovimsUntilTheLastOfThem() throws Exception {
        nvim.mode = new Mode("n", false);
        state.readMode();
        nvim.after.put("i", new Mode("i", false));
        keys.keyQueued(); // i
        keys.keyQueued(); // b, queued behind it before Neovim reported the insert

        keys.inputQueued("i");
        lastChange().afterApply().run(); // the editor applied the insert's sync

        assertFalse(state.isEclipseTypingReady(), "b waits for Neovim: Eclipse must not type yet");
        assertTrue(keys.isNeovimDrivenInsert(), "the keys go to Neovim meanwhile");
        assertTrue(keys.isHandBackPending());

        nvim.calls.clear();
        keys.inputQueued("b"); // the last key: the hand-back
        assertTrue(nvim.calls.contains("bufferLines"), nvim.calls.toString());
        lastChange().afterApply().run();
        assertFalse(keys.isNeovimDrivenInsert());
        assertTrue(state.isEclipseTypingReady(), "Eclipse types the next key");
    }

    /** No key waits when the insert's sync is applied: Eclipse types at once, as before. */
    @Test
    void anInsertEnteredWithNoKeyWaitingLetsEclipseType() throws Exception {
        nvim.mode = new Mode("n", false);
        state.readMode();
        nvim.after.put("i", new Mode("i", false));

        queue("i");
        lastChange().afterApply().run();

        assertTrue(state.isEclipseTypingReady());
        assertFalse(keys.isNeovimDrivenInsert());
    }

    /** A block-visual I: Neovim types to the end (Esc repeats the text on every line), even after a gap key. */
    @Test
    void anInsertFromBlockwiseVisualIsNotHandedBack() throws Exception {
        nvim.mode = new Mode("\u0016", false);
        state.readMode();
        nvim.after.put("I", new Mode("i", false));
        queue("I");
        keys.driveFromNeovim(); // a gap key as well
        nvim.calls.clear();

        queue("x");

        assertFalse(nvim.calls.contains("bufferLines"), nvim.calls.toString());
        assertTrue(keys.isNeovimDrivenInsert());
    }

    /** While recording, the macro must hold every typed key: Neovim types to the end. */
    @Test
    void anInsertWhileRecordingIsNotHandedBack() throws Exception {
        recording = "q";
        nvim.mode = new Mode("i", false);
        state.syncModeAndCursor(); // the snapshot reports the recording
        keys.driveFromNeovim();
        nvim.calls.clear();

        queue("x");

        assertFalse(nvim.calls.contains("bufferLines"), nvim.calls.toString());
        assertTrue(keys.isNeovimDrivenInsert());
    }

    /** Esc while Neovim still waits ends the insert: no hand-back is left for the next one. */
    @Test
    void leavingTheInsertDropsTheHandBack() throws Exception {
        ctrlRWaitingForARegister();
        nvim.after.put("<Esc>", new Mode("n", false));

        queue("<Esc>");

        assertFalse(keys.isHandBackPending());
        assertFalse(keys.isNeovimDrivenInsert());
    }

    private void ctrlRWaitingForARegister() throws Exception {
        nvim.mode = new Mode("i", false);
        nvim.after.put("<C-r>", new Mode("i", true));
        keys.syncInsertThenInput(new InsertReplay.SyncPoint(null, 1, 0), "<C-r>");
        assertTrue(keys.isHandBackPending());
        nvim.calls.clear();
    }

    /** One key as the session queues it: counted, then typed on the input thread. */
    private void queue(String key) throws Exception {
        keys.keyQueued();
        keys.inputQueued(key);
    }

    private BufferChange lastChange() {
        return editor.changes.get(editor.changes.size() - 1);
    }

    /** Ctrl-O: one normal-mode command; entering insert again after it is the usual sync. */
    @Test
    void ctrlOLeavesInsertForOneCommandWithoutABufferSync() throws Exception {
        nvim.mode = new Mode("i", false);
        nvim.after.put("<C-o>", new Mode("niI", false));

        keys.syncInsertThenInput(new InsertReplay.SyncPoint(null, 1, 0), "<C-o>");

        assertFalse(nvim.calls.contains("bufferLines"), nvim.calls.toString());
        assertTrue(heard.contains("mode niI"));
    }

    @Test
    void enterOnAColonLineIsTypedAndSyncsNoBuffer() throws Exception {
        nvim.mode = new Mode("c", false);
        nvim.after.put("<CR>", new Mode("n", false));

        type("<CR>");

        assertEquals(List.of("type <CR>", "snapshot"), nvim.calls);
    }

    // ---- the mouse ----

    @Test
    void aMouseEventGoesToNeovimThenTheStateIsRead() throws Exception {
        nvim.after.put("mouse drag", new Mode("v", false));

        keys.mouse(new MouseInput(MouseInput.Action.PRESS, "", 2, 3));
        keys.mouse(new MouseInput(MouseInput.Action.DRAG, "", 3, 1));

        assertEquals(List.of("mouse press 2:3", "keysConsumed", "snapshot", "mouse drag 3:1", "keysConsumed",
                "snapshot"), nvim.calls);
        assertEquals(List.of("mode n", "mode v"), heard, "the drag started visual mode");
    }

    /** {@code d} then a click: Neovim would not run the script until {@code d} is complete. */
    @Test
    void aMouseEventWhileNeovimWaitsForTheRestOfACommandIsDropped() throws Exception {
        nvim.mode = new Mode("no", true);

        keys.mouse(new MouseInput(MouseInput.Action.PRESS, "", 2, 3));

        assertEquals(List.of(), nvim.calls);
    }

    // ---- macro playback ----

    @Test
    void aMacroIsAppliedAsOneBufferChangeAfterItFinished() throws Exception {
        nvim.after.put("@", new Mode("n", true)); // waiting for the register name
        nvim.after.put("a", new Mode("n", false));
        nvim.keyWithLineEvent = "a"; // the macro's own edits arrive as line events
        nvim.lines = List.of("after macro");

        type("@", "a");

        assertEquals(List.of("type @", "type a", "bufferLines after macro", "snapshot"), nvim.calls);
        assertEquals(List.of("buffer [after macro]", "mode n"), heard);
    }

    /**
     * {@code @a} with {@code a} = {@code ddf}: the {@code dd} edits while buffer events are
     * suppressed, then {@code f} waits for its character. Nothing can be read while Neovim waits;
     * the key that ends the wait finishes the playback, and the buffer is synced after it, so
     * the deleted line leaves the document too.
     */
    @Test
    void aMacroThatStopsWaitingForAKeyIsSyncedAfterThatKey() throws Exception {
        nvim.after.put("@", new Mode("n", true)); // waiting for the register name
        nvim.after.put("a", new Mode("n", true)); // the macro's f waits for its character
        nvim.after.put("x", new Mode("n", false));
        nvim.keyWithLineEvent = "a"; // the dd, while events are suppressed
        nvim.lines = List.of("after macro");

        type("@", "a");
        assertEquals(List.of("type @", "type a"), nvim.calls, "nothing is asked while Neovim waits");
        assertEquals(List.of(), heard, "the dd's event was suppressed");

        type("x");

        assertEquals(List.of("type @", "type a", "type x", "bufferLines after macro", "snapshot"), nvim.calls);
        assertEquals(List.of("buffer [after macro]", "mode n"), heard);
    }

    /** Once that sync is done, the next keys are ordinary keys again. */
    @Test
    void afterTheKeyThatFinishesAMacroKeysAreOrdinary() throws Exception {
        nvim.after.put("@", new Mode("n", true));
        nvim.after.put("a", new Mode("n", true));
        nvim.after.put("x", new Mode("n", false));
        type("@", "a", "x");
        nvim.calls.clear();

        type("j");

        assertEquals(List.of("type j", "snapshot"), nvim.calls);
    }

    @Test
    void aMacroThatDoesNotFinishInTimeStillResumesBufferEvents() throws Exception {
        try (ExpectedLog log = ExpectedLog.of(KeyInput.class)) {
            nvim.after.put("@", new Mode("n", true));
            nvim.after.put("a", new Mode("n", false));
            nvim.bufferLinesFail = true;
            type("@", "a");

            state.dispatchBufferChange(0, 1, List.of("later"));

            assertTrue(heard.contains("buffer [later]"), heard.toString());
            log.assertLogged(Level.WARNING, "buffer sync after macro failed");
        }
    }

    @Test
    void aFailedKeyDuringMacroStartResumesBufferEvents() {
        nvim.after.put("@", new Mode("n", true));
        nvim.failingKey = "a";

        try {
            type("@", "a");
        } catch (Exception expected) {
            // the key is lost; what matters is that the editor hears Neovim again
        }

        state.dispatchBufferChange(0, 1, List.of("later"));
        assertEquals(List.of("buffer [later]"), heard);
    }
}
