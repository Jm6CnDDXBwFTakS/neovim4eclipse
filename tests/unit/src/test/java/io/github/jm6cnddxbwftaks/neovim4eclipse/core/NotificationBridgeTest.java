// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.CmdlineEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.MessageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Handle;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;

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
            @Override public void onCursor(int line1, int col0, int bufnr, int winid) {
                events.add("cursor:" + line1 + ":" + col0 + (bufnr == NotificationBridge.UNKNOWN_BUFFER ? "" : "@" + bufnr)
                        + (winid == EditorWindow.UNKNOWN ? "" : "#" + winid));
            }
            @Override public void onBufferLines(int bufnr, int first, int last, List<String> lines) {
                events.add("lines@" + bufnr + ":" + first + ":" + last + ":" + lines);
            }
            @Override public void onBufferEnter(int bufnr, int winid, boolean jumped, String projectFile) {
                events.add("enter:" + bufnr + (winid == EditorWindow.UNKNOWN ? "" : "#" + winid) + (jumped ? " jump" : "")
                        + (projectFile.isEmpty() ? "" : " file " + projectFile));
            }
            @Override public void onBufferReload(int bufnr) { events.add("reload:" + bufnr); }
            @Override public void onWindowClosed(int winid) { events.add("winclosed:" + winid); }
            @Override public void onBufferClosed(int bufnr) { events.add("closed:" + bufnr); }
            @Override public void onWrap(int bufnr, boolean wrap, int winid) { events.add("wrap@" + bufnr + ":" + wrap + (winid == EditorWindow.UNKNOWN ? "" : "#" + winid)); }
            @Override public void onViewportOverheadChanged() { events.add("overhead"); }
            @Override public void onTopLine(int bufnr, int line1, int winid) { events.add("top@" + bufnr + ":" + line1 + (winid == EditorWindow.UNKNOWN ? "" : "#" + winid)); }
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
            @Override public DocumentWrite.Result onWrite(int bufnr, DocumentWrite.Request request) {
                events.add("write@" + bufnr + (request.createFolders() ? " ++p" : "") + " " + request.kind()
                        + (request.target().isEmpty() ? "" : " " + request.target())
                        + (request.first() > 0 ? " " + request.first() + "," + request.last() : "") + (request.force() ? "!" : ""));
                if (bufnr == 13) {
                    throw new IllegalStateException("workspace closed");
                }
                return bufnr == 7 ? DocumentWrite.Result.failed("read-only") : DocumentWrite.Result.saved("written");
            }
            @Override public void onIdeAction(IdeAction action) {
                events.add("action:" + action.name() + ":" + action.count()
                        + (action.step() == 0 ? "" : ":step " + action.step()));
            }
            @Override public void onHostCommand(HostCommand command) {
                events.add("command:" + command.kind() + (command.force() ? "!" : "") + ":" + command.args());
            }
            @Override public void onCmdline(List<CmdlineEvent> cmdline) {
                for (CmdlineEvent event : cmdline) {
                    events.add(event instanceof CmdlineEvent.Show show ? "cmdline" + show.firstc() + show.content()
                            : "cmdline:" + event);
                }
            }
            @Override public void onMessageEvent(MessageEvent event) {
                events.add("message:" + event);
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
        notify("eclipse_action", "history_back", 2, 7); // Back steps 7 and 8
        assertEquals(List.of("action:next_tab:3", "action:definition:0", "action:history_back:2:step 7"), events);
    }

    /** {@code :Eclipse[!] name args}: Neovim's full name, the bang, the arguments as a list. */
    @Test
    void hostCommandCarriesNameBangAndArguments() {
        notify("eclipse_command", "edit", false, List.of("my file.txt"));
        notify("eclipse_command", "quit", true, List.of());
        notify("eclipse_command", "substitute", false, List.of()); // not Eclipse's: dropped
        notify("eclipse_command", "quit");                          // malformed: dropped
        assertEquals(List.of("command:EDIT:[my file.txt]", "command:QUIT!:[]"), events);
    }

    /** The safety nets: Neovim deleted a document buffer, or is quitting its window, by itself. */
    @Test
    void bufferClosedCarriesTheBuffer() {
        notify("eclipse_buffer_closed", 4);
        notify("eclipse_buffer_closed");
        assertEquals(List.of("closed:4"), events);
    }

    /**
     * LuaScripts.PROJECT_FILES: a project's file without an editor comes with its name, for Eclipse
     * to open it; a document's buffer reloaded by a script asks for the document's text again.
     */
    @Test
    void aProjectFileComesWithItsNameAndAReloadWithItsBuffer() {
        notify("eclipse_buf_enter", 5, 1002L, true, "/ws/app/B.java");
        notify("eclipse_buf_enter", 6, 1002L, false, "");
        notify("eclipse_buf_enter", 7, 1002L, false, 3L); // not a name: none
        notify("eclipse_reload", 4);
        notify("eclipse_reload");
        assertEquals(List.of("enter:5#1002 jump file /ws/app/B.java", "enter:6#1002", "enter:7#1002", "reload:4"), events);
    }

    /** {@code :set wrap} in Neovim (LuaScripts.VIEWPORT); a payload without a boolean is ignored. */
    @Test
    void wrapCarriesTheBufferAndTheValue() {
        notify("eclipse_wrap", 3, true);
        notify("eclipse_wrap", 3, false);
        notify("eclipse_wrap", 3, 1);
        notify("eclipse_wrap");
        notify("eclipse_wrap", 3, true, 1001);
        assertEquals(List.of("wrap@3:true", "wrap@3:false", "wrap@3:true#1001"), events);
    }

    @Test
    void aScrollCarriesTheBufferAndTheTopLine() {
        notify("eclipse_topline", 3, 40);
        notify("eclipse_topline", 3, 0); // not a line
        notify("eclipse_topline", 3);
        notify("eclipse_topline", 3, 41, 1002);
        assertEquals(List.of("top@3:40", "top@3:41#1002"), events);
    }

    @Test
    void theCursorsTopLineComesBeforeTheCursor() {
        notify("eclipse_cursor", 50, 2, 3, 40);
        assertEquals(List.of("top@3:40", "cursor:50:2@3"), events, "the editor scrolls, then the caret moves");
    }

    /** With a window per editor (docs/design/WINDOWS.md) the reports say which window they are from. */
    @Test
    void theCursorAndTheBufferEnterCarryTheWindow() {
        notify("eclipse_cursor", 50, 2, 3, 40, 1001);
        notify("eclipse_buf_enter", 3, 1002L);
        notify("eclipse_buf_enter", 3, "x"); // not a window: unknown
        notify("eclipse_buf_enter", 4, 1002L, true); // a move in the window's jump history
        assertEquals(List.of("top@3:40#1001", "cursor:50:2@3#1001", "enter:3#1002", "enter:3", "enter:4#1002 jump"),
                events);
    }

    /** The safety net of LuaScripts.WINDOWS: a window closed in Neovim by itself. */
    @Test
    void aWindowClosedInNeovimCarriesItsId() {
        notify("eclipse_window_closed", 1003);
        notify("eclipse_window_closed");
        assertEquals(List.of("winclosed:1003"), events);
    }

    @Test
    void anOverheadChangeHasNoPayload() {
        notify("eclipse_viewport");
        assertEquals(List.of("overhead"), events);
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

    /** The answer is {@code [error, message]}: the message for a save that worked, else the error. */
    @Test
    void writeRequestsAnswerWithTheErrorOrTheMessage() {
        assertEquals(List.of("", "written"), request("eclipse_write", 3, " ++p"));
        assertEquals(List.of("read-only", ""), request("eclipse_write", 7L, ""));
        assertEquals(List.of("Eclipse could not save the file: workspace closed", ""), request("eclipse_write", 13));
        assertEquals(List.of("Eclipse got a write request without a buffer number", ""), request("eclipse_write"));
        assertEquals(List.of("", "written"), request("eclipse_write", 3, "", "copy", "/ws/p/other.txt"));
        assertEquals(List.of("", "written"), request("eclipse_write", 3, "", "append", "/ws/p/log.txt", 5L, 10L, true));
        // The file in the system's form: \ws\p\other.txt on Windows (DocumentWrite.Request.of).
        assertEquals(List.of("write@3 ++p OWN", "write@7 OWN", "write@13 OWN",
                "write@3 COPY " + java.nio.file.Path.of("/ws/p/other.txt"),
                "write@3 APPEND " + java.nio.file.Path.of("/ws/p/log.txt") + " 5,10!"), events);
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

    /** The fold module's z mapping reports its pending z itself; it goes where Neovim's showcmd goes. */
    @Test
    void thePluginsOwnShowcmdIsAShowcmdAndAFlush() {
        notify("eclipse_showcmd", "3z");
        notify("eclipse_showcmd", "");
        notify("eclipse_showcmd"); // malformed: dropped
        assertEquals(List.of("message:" + new MessageEvent.Showcmd("3z"), "message:" + new MessageEvent.Flush(),
                "message:" + new MessageEvent.Showcmd(""), "message:" + new MessageEvent.Flush()), events);
    }

    @Test
    void unknownNotificationsAreIgnored() {
        // Neovim sends these too: grid redraws to every UI, changedtick events to nvim_buf_attach.
        notify("redraw", List.of("grid_line", List.of(1, 0, 0, List.of(), false)));
        notify("nvim_buf_changedtick_event", 1, 5);
        assertTrue(events.isEmpty(), events.toString());
    }

    /** The command line first: the output of a command is titled with it, from the same redraw. */
    @Test
    void aRedrawsCommandLineEventsGoBeforeItsMessageEvents() {
        notify("redraw",
                List.of("msg_show", List.of("list_cmd", List.of(List.of(0, "x", 0)), false, false, false, 1, "typed_cmd")),
                List.of("cmdline_show", List.of(List.of(List.of(0, "ls", 0)), 2, ":", "", 0, 1)),
                List.of("flush"));
        assertEquals(List.of("cmdline:ls",
                "message:" + new MessageEvent.Show("list_cmd", "x", false, false, "1", "typed_cmd"),
                "message:" + new MessageEvent.Flush()), events);
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
