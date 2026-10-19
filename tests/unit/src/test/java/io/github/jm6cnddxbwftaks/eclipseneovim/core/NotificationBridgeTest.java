// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.msgpack.jackson.dataformat.MessagePackExtensionType;

import com.ensarsarajcic.neovim.java.api.types.msgpack.Buffer;
import com.ensarsarajcic.neovim.java.corerpc.message.NotificationMessage;
import com.ensarsarajcic.neovim.java.corerpc.message.RequestMessage;

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
            @Override public void onIdeAction(IdeAction action) {
                events.add("action:" + action.name() + ":" + action.count());
            }
        });
    }

    private static NotificationMessage msg(String name, Object... args) {
        return new NotificationMessage(name, new ArrayList<>(Arrays.asList(args)));
    }

    private static RequestMessage request(Object... args) {
        return new RequestMessage.Builder("eclipse_clipboard_paste", new ArrayList<>(Arrays.asList(args))).build();
    }

    @Test
    void ideActionCarriesNameAndCount() {
        bridge.onAction(msg("eclipse_action", "next_tab", 3));
        bridge.onAction(msg("eclipse_action", "definition"));
        bridge.onAction(msg("eclipse_action"));
        assertEquals(List.of("action:next_tab:3", "action:definition:0"), events);
    }

    @Test
    void modeNotification() {
        bridge.onMode(msg("eclipse_mode", "i"));
        assertEquals(List.of("mode:i"), events);
    }

    @Test
    void cursorNotificationAcceptsNumbersAndNumericStrings() {
        bridge.onCursor(msg("eclipse_cursor", 3, 7L));
        bridge.onCursor(msg("eclipse_cursor", "4", "2"));
        assertEquals(List.of("cursor:3:7", "cursor:4:2"), events);
    }

    @Test
    void cursorNotificationCarriesTheBufferWhenGiven() {
        bridge.onCursor(msg("eclipse_cursor", 3, 7, 5));
        assertEquals(List.of("cursor:3:7@5"), events);
    }

    @Test
    void bufferEnterNotification() {
        bridge.onBufEnter(msg("eclipse_buf_enter", 4L));
        bridge.onBufEnter(msg("eclipse_buf_enter"));
        assertEquals(List.of("enter:4"), events);
    }

    @Test
    void bufferHandlesDecodeFromNeovimJavaTypesRawExtensionsAndNumbers() {
        assertEquals(7, NotificationBridge.bufferNumber(new Buffer(7)));
        // msgpack positive fixint 9, as Neovim encodes a Buffer handle's payload
        assertEquals(9, NotificationBridge.bufferNumber(new MessagePackExtensionType((byte) 0, new byte[] { 9 })));
        assertEquals(3, NotificationBridge.bufferNumber(3));
        assertEquals(null, NotificationBridge.bufferNumber("x"));
    }

    @Test
    void yankNotificationWithLineList() {
        bridge.onYank(msg("eclipse_yank", List.of("one", "two"), "V"));
        assertEquals(List.of("yank:[one, two]:V"), events);
    }

    @Test
    void yankNotificationWithCharwiseType() {
        bridge.onYank(msg("eclipse_yank", List.of("word"), "v"));
        assertEquals(List.of("yank:[word]:v"), events);
    }

    @Test
    void yankNotificationKeepsABlockTypeAndDefaultsToCharacters() {
        bridge.onYank(msg("eclipse_yank", List.of("ab", "cd"), "\u00162"));
        bridge.onYank(msg("eclipse_yank", List.of("x")));
        assertEquals(List.of("yank:[ab, cd]:\u00162", "yank:[x]:v"), events);
    }

    @Test
    void clipboardCopyCarriesRegisterLinesAndType() {
        bridge.onClipboardCopy(msg("eclipse_clipboard_copy", "+", List.of("line", ""), "V"));
        bridge.onClipboardCopy(msg("eclipse_clipboard_copy", "*", List.of("w"), "v"));
        bridge.onClipboardCopy(msg("eclipse_clipboard_copy", "+"));
        assertEquals(List.of("copy+:[line, ]:V", "copy*:[w]:v"), events);
    }

    @Test
    void clipboardPasteAnswersWithTheSinksValue() {
        assertEquals(List.of(List.of("pasted"), "v"), bridge.onClipboardPaste(request("*")));
        assertEquals(List.of(List.of("pasted"), "v"), bridge.onClipboardPaste(request()));
        assertEquals(List.of("paste*", "paste+"), events, "no register given: the clipboard");
    }

    @Test
    void aFailingClipboardPastesNothingInsteadOfThrowingAtNeovim() {
        assertEquals(List.of(), bridge.onClipboardPaste(request("!")));
    }

    @Test
    void requestsAreAnsweredByName() {
        assertEquals(List.of(List.of("pasted"), "v"), bridge.onRequest(request("+")));
        RequestMessage unknown = new RequestMessage.Builder("eclipse_unknown", new ArrayList<>()).build();
        assertThrows(UnsupportedOperationException.class, () -> bridge.onRequest(unknown));
    }

    @Test
    void yankNotificationWithSingleString() {
        bridge.onYank(msg("eclipse_yank", "solo", "v"));
        assertEquals(List.of("yank:[solo]:v"), events);
    }

    @Test
    void bufferLinesEventUsesFirstLastAndLineData() {
        // [bufnr, changedtick, firstline, lastline, linedata, more]
        bridge.onBufLines(msg("nvim_buf_lines_event", 1, 5, 2, 3, List.of("x", "y"), false));
        assertEquals(List.of("lines@1:2:3:[x, y]"), events);
    }

    @Test
    void bufferLinesEventWithEmptyLineDataIsADeletion() {
        bridge.onBufLines(msg("nvim_buf_lines_event", 1, 5, 2, 3, List.of(), false));
        assertEquals(List.of("lines@1:2:3:[]"), events);
    }

    @Test
    void initialAttachEventHasMinusOneLastLine() {
        bridge.onBufLines(msg("nvim_buf_lines_event", 1, 0, 0, -1, List.of("a"), false));
        assertEquals(List.of("lines@1:0:-1:[a]"), events);
    }

    @Test
    void malformedPayloadsAreIgnoredWithoutThrowing() {
        bridge.onMode(msg("eclipse_mode"));
        bridge.onCursor(msg("eclipse_cursor", 1));
        bridge.onCursor(msg("eclipse_cursor", "x", "y"));
        bridge.onYank(msg("eclipse_yank"));
        bridge.onYank(msg("eclipse_yank", (Object) null));
        bridge.onBufLines(msg("nvim_buf_lines_event", 1, 2));
        bridge.onBufLines(msg("nvim_buf_lines_event", 1, 2, "a", "b", List.of()));
        assertTrue(events.isEmpty(), events.toString());
    }
}
