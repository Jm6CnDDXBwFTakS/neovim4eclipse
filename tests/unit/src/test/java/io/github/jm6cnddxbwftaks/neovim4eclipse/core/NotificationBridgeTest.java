// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Handle;

/**
 * {@link NotificationBridge} as the RPC layer calls it: {@code notification(name, args)} and
 * {@code request(name, args)}, so each test also checks that the name reaches the right handler.
 */
class NotificationBridgeTest {

    /** Records every call as a readable string. */
    private final List<String> events = new ArrayList<>();
    private NotificationBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new NotificationBridge(new NotificationBridge.Sink() {
            @Override public void onMode(String mode) { events.add("mode:" + mode); }
            @Override public void onCursor(int line1, int col0, int bufnr) {
                events.add("cursor:" + line1 + ":" + col0 + (bufnr == NotificationBridge.UNKNOWN_BUFFER ? "" : "@" + bufnr));
            }
            @Override public void onBufferLines(int bufnr, int first, int last, List<String> lines) {
                events.add("lines@" + bufnr + ":" + first + ":" + last + ":" + lines);
            }
            @Override public void onBufferEnter(int bufnr) { events.add("enter:" + bufnr); }
            @Override public void onYank(List<String> lines, String regtype) {
                events.add("yank:" + lines + ":" + regtype);
            }
            @Override public void onClipboardCopy(String register, List<String> lines, String regtype) {
                events.add("copy" + register + ":" + lines + ":" + regtype);
            }
            @Override public Object onClipboardPaste(String register) {
                events.add("paste" + register);
                if ("!".equals(register)) {
                    throw new IllegalStateException("the clipboard is gone");
                }
                return List.of(List.of("pasted"), "v");
            }
            @Override public String onWrite(int bufnr) {
                events.add("write@" + bufnr);
                if (bufnr == 13) {
                    throw new IllegalStateException("workspace closed");
                }
                return bufnr == 7 ? "read-only" : null;
            }
            @Override public void onIdeAction(IdeAction action) {
                events.add("action:" + action.name() + ":" + action.count());
            }
        });
    }

    /** Delivers a notification, as the RPC layer does. Arrays.asList: an argument may be null. */
    private void notify(String name, Object... args) {
        bridge.notification(name, Arrays.asList(args));
    }

    /** Sends a request, as the RPC layer does, and returns the answer. */
    private Object request(String name, Object... args) {
        return bridge.request(name, Arrays.asList(args));
    }

    private Object paste(Object... args) {
        return request("eclipse_clipboard_paste", args);
    }

    @Test
    void ideActionCarriesNameAndCount() {
        notify("eclipse_action", "next_tab", 3);
        notify("eclipse_action", "definition");
        notify("eclipse_action");
        assertEquals(List.of("action:next_tab:3", "action:definition:0"), events);
    }

    @Test
    void modeNotification() {
        notify("eclipse_mode", "i");
        assertEquals(List.of("mode:i"), events);
    }

    @Test
    void cursorNotificationAcceptsNumbersAndNumericStrings() {
        notify("eclipse_cursor", 3, 7L);
        notify("eclipse_cursor", "4", "2");
        assertEquals(List.of("cursor:3:7", "cursor:4:2"), events);
    }

    @Test
    void cursorNotificationCarriesTheBufferWhenGiven() {
        notify("eclipse_cursor", 3, 7, 5);
        assertEquals(List.of("cursor:3:7@5"), events);
    }

    @Test
    void bufferEnterNotification() {
        notify("eclipse_buf_enter", 4L);
        notify("eclipse_buf_enter");
        assertEquals(List.of("enter:4"), events);
    }

    @Test
    void bufferHandlesAndPlainNumbersGiveTheBufferNumber() {
        assertEquals(7, NotificationBridge.bufferNumber(Handle.buffer(7)));
        assertEquals(3, NotificationBridge.bufferNumber(3L));
        assertEquals(null, NotificationBridge.bufferNumber(Handle.window(1000)), "a window is not a buffer");
        assertEquals(null, NotificationBridge.bufferNumber("x"));
    }

    @Test
    void yankNotificationWithLineList() {
        notify("eclipse_yank", List.of("one", "two"), "V");
        assertEquals(List.of("yank:[one, two]:V"), events);
    }

    @Test
    void yankNotificationWithCharwiseType() {
        notify("eclipse_yank", List.of("word"), "v");
        assertEquals(List.of("yank:[word]:v"), events);
    }

    @Test
    void yankNotificationKeepsABlockTypeAndDefaultsToCharacters() {
        notify("eclipse_yank", List.of("ab", "cd"), "\u00162");
        notify("eclipse_yank", List.of("x"));
        assertEquals(List.of("yank:[ab, cd]:\u00162", "yank:[x]:v"), events);
    }

    @Test
    void clipboardCopyCarriesRegisterLinesAndType() {
        notify("eclipse_clipboard_copy", "+", List.of("line", ""), "V");
        notify("eclipse_clipboard_copy", "*", List.of("w"), "v");
        notify("eclipse_clipboard_copy", "+");
        assertEquals(List.of("copy+:[line, ]:V", "copy*:[w]:v"), events);
    }

    @Test
    void clipboardPasteAnswersWithTheSinksValue() {
        assertEquals(List.of(List.of("pasted"), "v"), paste("*"));
        assertEquals(List.of(List.of("pasted"), "v"), paste());
        assertEquals(List.of("paste*", "paste+"), events, "no register given: the clipboard");
    }

    @Test
    void aFailingClipboardPastesNothingInsteadOfThrowingAtNeovim() {
        assertEquals(List.of(), paste("!"));
    }

    @Test
    void requestsAreAnsweredByName() {
        assertEquals(List.of(List.of("pasted"), "v"), paste("+"));
        // Thrown, so the RPC layer answers Neovim with an error (Neovim waits for every request).
        assertThrows(UnsupportedOperationException.class, () -> request("eclipse_unknown"));
    }

    /** The answer is what Neovim shows: "" for a save that worked, else the message. */
    @Test
    void writeRequestsAnswerWithTheErrorOrNothing() {
        assertEquals("", request("eclipse_write", 3));
        assertEquals("read-only", request("eclipse_write", 7L));
        assertEquals("Eclipse could not save the file: workspace closed", request("eclipse_write", 13));
        assertEquals("Eclipse got a write request without a buffer number", request("eclipse_write"));
        assertEquals(List.of("write@3", "write@7", "write@13"), events);
    }

    @Test
    void yankNotificationWithSingleString() {
        notify("eclipse_yank", "solo", "v");
        assertEquals(List.of("yank:[solo]:v"), events);
    }

    @Test
    void bufferLinesEventUsesFirstLastAndLineData() {
        // [bufnr, changedtick, firstline, lastline, linedata, more]
        notify("nvim_buf_lines_event", 1, 5, 2, 3, List.of("x", "y"), false);
        assertEquals(List.of("lines@1:2:3:[x, y]"), events);
    }

    @Test
    void bufferLinesEventWithEmptyLineDataIsADeletion() {
        notify("nvim_buf_lines_event", 1, 5, 2, 3, List.of(), false);
        assertEquals(List.of("lines@1:2:3:[]"), events);
    }

    @Test
    void initialAttachEventHasMinusOneLastLine() {
        notify("nvim_buf_lines_event", 1, 0, 0, -1, List.of("a"), false);
        assertEquals(List.of("lines@1:0:-1:[a]"), events);
    }

    @Test
    void unknownNotificationsAreIgnored() {
        // Neovim sends these too: redraw to every UI, changedtick events to nvim_buf_attach.
        notify("redraw", List.of("flush"));
        notify("nvim_buf_changedtick_event", 1, 5);
        assertTrue(events.isEmpty(), events.toString());
    }

    @Test
    void malformedPayloadsAreIgnoredWithoutThrowing() {
        notify("eclipse_mode");
        notify("eclipse_cursor", 1);
        notify("eclipse_cursor", "x", "y");
        notify("eclipse_yank");
        notify("eclipse_yank", (Object) null);
        notify("nvim_buf_lines_event", 1, 2);
        notify("nvim_buf_lines_event", 1, 2, "a", "b", List.of());
        assertTrue(events.isEmpty(), events.toString());
    }
}
