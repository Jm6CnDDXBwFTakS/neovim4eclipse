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
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Mode;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.InsertReplay;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;

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
        String commandLineType = ":";
        String commandLineText = "";
        /** Typing this key throws (a lost connection). */
        String failingKey;
        /** Typing this key makes Neovim send a buffer line event (as editing in Neovim does). */
        String keyWithLineEvent;
        boolean bufferLinesFail;

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
        public void awaitKeysConsumed() {
            calls.add("keysConsumed");
        }

        @Override
        public String commandLineType() {
            return commandLineType;
        }

        @Override
        public String commandLineText() {
            return commandLineText;
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
        public Mode getMode() {
            return mode;
        }

        @Override
        public Object stateSnapshot(boolean statusline) {
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
    private Predicate<String> hostHandler;
    private Runnable push = () -> nvim.calls.add("push document");
    private final StateSync state = new StateSync(nvim, editor, status, bufnr -> bufnr == 1, this::modeChanged);
    private final KeyInput keys = new KeyInput(nvim, state, status, () -> hostHandler, () -> push,
            Duration.ofMillis(50));

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

        assertEquals(List.of("setCursor 1:0", "type abc", "keysConsumed", "type <Esc>", "keysConsumed",
                "push document", "setCursor 1:2", "snapshot"), nvim.calls);
        assertEquals(List.of("mode n"), heard, "the replayed text's line event is not applied twice");
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

            assertEquals(List.of("setCursor 1:0", "type abc", "push document", "type <Esc>", "snapshot"),
                    nvim.calls);
            state.dispatchBufferChange(0, 1, List.of("later"));
            assertEquals(List.of("mode n", "buffer [later]"), heard);
            log.assertLogged(Level.WARNING, "replaying typed text into Neovim failed");
        }
    }

    // ---- Enter on Neovim's own : command line ----

    @Test
    void enterOnAColonCommandLineIsOfferedToEclipseFirst() throws Exception {
        nvim.mode = new Mode("c", false);
        nvim.commandLineText = " w ";
        List<String> offered = new ArrayList<>();
        hostHandler = text -> offered.add(text);

        type("<CR>");

        assertEquals(List.of("w"), offered);
        assertEquals(List.of("type <Esc>", "bufferLines", "snapshot"), nvim.calls,
                "Neovim's command line is cancelled, Enter is not typed");
    }

    @Test
    void aCommandEclipseDoesNotHandleIsTypedAsUsual() throws Exception {
        nvim.mode = new Mode("c", false);
        nvim.commandLineText = "s/a/b/";
        hostHandler = text -> false;

        type("<CR>");

        assertTrue(nvim.calls.contains("type <CR>"), nvim.calls.toString());
    }

    @Test
    void enterOnASearchLineIsNotOffered() throws Exception {
        nvim.mode = new Mode("c", false);
        nvim.commandLineType = "/";
        nvim.commandLineText = "w";
        hostHandler = text -> {
            throw new AssertionError("offered " + text);
        };

        type("<CR>");

        assertTrue(nvim.calls.contains("type <CR>"), nvim.calls.toString());
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
