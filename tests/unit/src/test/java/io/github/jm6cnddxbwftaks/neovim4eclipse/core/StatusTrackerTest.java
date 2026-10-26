// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.MessageEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StatusItem;

/**
 * {@link StatusTracker}: what the status bar, the message line and the output popup show, fed with
 * the message events Neovim sends, without a Neovim process. (Where each message goes is
 * {@code MessageRouteTest}'s subject; this test is about when the listeners hear of it.)
 */
class StatusTrackerTest {

    private static final MessageEvent FLUSH = new MessageEvent.Flush();

    private final StatusTracker tracker = new StatusTracker();
    private final List<StatusInfo> seen = new ArrayList<>();
    private final List<CommandOutput> outputs = new ArrayList<>();

    StatusTrackerTest() {
        tracker.setItems(EnumSet.allOf(StatusItem.class));
        tracker.addStatusListener(seen::add);
        tracker.addOutputListener(outputs::add);
    }

    private static MessageEvent show(String kind, String text) {
        return new MessageEvent.Show(kind, text, false, false, "", "typed_cmd");
    }

    private StatusInfo last() {
        return seen.get(seen.size() - 1);
    }

    /** A redraw's messages are passed on together, at its end: a search's echo and count show as one. */
    @Test
    void messagesArePassedOnAtTheEndOfARedraw() {
        tracker.onMessageEvent(show("", "3 lines yanked"));
        assertTrue(seen.isEmpty());

        tracker.onMessageEvent(FLUSH);

        assertEquals(new StatusInfo("", "", "3 lines yanked", false), last());
    }

    @Test
    void aMessageStaysUntilTheNextKeyEvenOverLaterRedraws() {
        tracker.onMessageEvent(show("emsg", "E486: Pattern not found: x"));
        tracker.onMessageEvent(FLUSH);
        tracker.onMessageEvent(FLUSH);
        tracker.publishStatusline("sl");
        assertEquals(new StatusInfo("sl", "", "E486: Pattern not found: x", true), last());

        tracker.clearMessage();

        assertEquals(new StatusInfo("sl", "", "", false), last());
    }

    /** {@code n} twice with no match: the second error must reach the message line again. */
    @Test
    void theSameErrorAfterTheNextKeyIsHeardAgain() {
        StatusInfo error = new StatusInfo("", "", "E486: Pattern not found: x", true);
        tracker.onMessageEvent(show("emsg", error.message()));
        tracker.onMessageEvent(FLUSH);
        tracker.clearMessage();
        tracker.onMessageEvent(show("emsg", error.message()));
        tracker.onMessageEvent(FLUSH);
        assertEquals(List.of(error, StatusInfo.EMPTY, error), seen);
    }

    @Test
    void clearingWithNoMessageFiresNothing() {
        tracker.clearMessage();
        assertTrue(seen.isEmpty());
    }

    /** Neovim sends the pending command itself, also while it waits for the rest ({@code 2f}). */
    @Test
    void thePendingCommandComesFromNeovim() {
        tracker.onMessageEvent(new MessageEvent.Showcmd("2f"));
        tracker.onMessageEvent(FLUSH);
        assertEquals("2f", last().cmd());

        tracker.clearMessage(); // a key clears messages, not the pending command
        assertEquals("2f", last().cmd());

        tracker.onMessageEvent(new MessageEvent.Showcmd(""));
        tracker.onMessageEvent(FLUSH);
        assertEquals("", last().cmd());
    }

    @Test
    void thePendingCommandIsLeftOutWhenTheStatusBarHidesIt() {
        tracker.setItems(EnumSet.noneOf(StatusItem.class));
        tracker.onMessageEvent(new MessageEvent.Showcmd("2f"));
        tracker.onMessageEvent(FLUSH);
        assertTrue(seen.isEmpty());
    }

    @Test
    void anUnchangedStatusIsNotFiredAgain() {
        tracker.publishStatusline("sl");
        tracker.publishStatusline("sl");
        tracker.onMessageEvent(FLUSH);
        assertEquals(1, seen.size());
    }

    /** A listing goes to the popup, titled with its command line, and the message line stays empty. */
    @Test
    void outputGoesToTheOutputListenersWithItsCommandLine() {
        tracker.commandLine(":ls");
        tracker.onMessageEvent(show("list_cmd", "  1 %a   \"a.txt\"  line 1"));
        tracker.onMessageEvent(FLUSH);

        assertEquals(List.of(new CommandOutput(":ls", "  1 %a   \"a.txt\"  line 1")), outputs);
        assertTrue(seen.isEmpty(), "nothing for the message line: " + seen);
    }

    /** Shell output arrives in chunks; the popup gets the whole text each time it grew, once. */
    @Test
    void growingOutputIsPassedAgainWholeAndOnlyWhenItChanged() {
        tracker.commandLine(":!make");
        tracker.onMessageEvent(new MessageEvent.Show("shell_cmd", ":!make\r\n", false, false, "1", "typed_cmd"));
        tracker.onMessageEvent(new MessageEvent.Show("shell_out", "cc a.c\n", false, true, "2", "typed_cmd"));
        tracker.onMessageEvent(FLUSH);
        tracker.onMessageEvent(FLUSH);
        tracker.onMessageEvent(new MessageEvent.Show("shell_out", "cc b.c\n", false, true, "3", "typed_cmd"));
        tracker.onMessageEvent(FLUSH);

        assertEquals(List.of(new CommandOutput(":!make", "cc a.c"), new CommandOutput(":!make", "cc a.c\ncc b.c")),
                outputs);
    }

    /** After the next key the same output is new output: {@code :ls} twice opens the popup twice. */
    @Test
    void theSameOutputAfterTheNextKeyIsPassedAgain() {
        for (int i = 0; i < 2; i++) {
            tracker.clearMessage();
            tracker.onMessageEvent(show("list_cmd", "x"));
            tracker.onMessageEvent(FLUSH);
        }
        assertEquals(2, outputs.size());
    }

    @Test
    void aSecondLineMovesTheMessageFromTheLineToThePopup() {
        tracker.onMessageEvent(show("emsg", "E1: first"));
        tracker.onMessageEvent(FLUSH);
        assertEquals("E1: first", last().message());

        tracker.onMessageEvent(show("emsg", "E2: second"));
        tracker.onMessageEvent(FLUSH);

        assertEquals("", last().message());
        assertFalse(last().error());
        assertEquals("E1: first\nE2: second", outputs.get(0).text());
    }

    @Test
    void recordingListenersHearStartAndStopOnce() {
        List<String> registers = new ArrayList<>();
        tracker.addRecordingListener(registers::add);
        tracker.updateRecording("a");
        tracker.updateRecording("a");
        tracker.updateRecording("");
        assertEquals(List.of("a", ""), registers);
        assertEquals("", tracker.recordingRegister());
    }
}
