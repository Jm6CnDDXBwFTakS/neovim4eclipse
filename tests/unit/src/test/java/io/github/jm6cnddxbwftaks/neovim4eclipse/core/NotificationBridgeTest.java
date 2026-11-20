// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.JumpLabels;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimHighlight;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.SearchMatch;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridEvent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GridSnapshot;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageBuffer;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewContent;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ViewText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Handle;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.ExpectedLog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.QuickfixEntries;

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
            @Override public void onBufferEnter(int bufnr, int winid, boolean jumped, String projectFile,
                    boolean pageFollows) {
                events.add("enter:" + bufnr + (winid == EditorWindow.UNKNOWN ? "" : "#" + winid) + (jumped ? " jump" : "")
                        + (projectFile.isEmpty() ? "" : " file " + projectFile) + (pageFollows ? " follows" : ""));
            }
            @Override public void onBufferReload(int bufnr, boolean kept) { events.add("reload:" + bufnr + (kept ? " kept" : "")); }
            @Override public void onWindowClosed(int winid) { events.add("winclosed:" + winid); }
            @Override public void onUi2Windows(List<Integer> wins, int cmd, int msg, boolean overlay) {
                events.add("ui2:" + wins + (overlay ? " overlay" : "") + (cmd != wins.stream().findFirst().orElse(-1) ? " cmd " + cmd : ""));
            }
            @Override public void onStatusLines(List<GridSnapshot> lines) {
                events.add("statuslines:" + lines.stream().map(l -> l.window() + "=" + (l.height() == 0 ? "-" : l.row(0))).toList());
            }
            @Override public void onEcho(int bufnr, boolean starts) { events.add("echo@" + bufnr + ":" + starts); }
            @Override public void onBufferClosed(int bufnr) { events.add("closed:" + bufnr); }
            @Override public void onWrap(int bufnr, boolean wrap, int winid) { events.add("wrap@" + bufnr + ":" + wrap + (winid == EditorWindow.UNKNOWN ? "" : "#" + winid)); }
            @Override public void onViewportOverheadChanged() { events.add("overhead"); }
            @Override public void onSearchMatches(int bufnr, List<SearchMatch> matches, int winid) { events.add("search@" + bufnr + ":" + matches + "#" + winid); }
            @Override public void onHighlights(int bufnr, List<NeovimHighlight> highlights, List<JumpLabels.Label> labels, int winid) {
                events.add("hl@" + bufnr + ":" + highlights + (labels.isEmpty() ? "" : "+" + labels) + "#" + winid);
            }
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
                    throw new IllegalStateException("the clipboard is gone (expected by the test)");
                }
                return List.of(List.of("pasted"), "v");
            }
            @Override public DocumentWrite.Result onWrite(int bufnr, DocumentWrite.Request request) {
                events.add("write@" + bufnr + (request.createFolders() ? " ++p" : "") + " " + request.kind()
                        + (request.target().isEmpty() ? "" : " " + request.target())
                        + (request.first() > 0 ? " " + request.first() + "," + request.last() : "") + (request.force() ? "!" : ""));
                if (bufnr == 13) {
                    throw new IllegalStateException("workspace closed (expected by the test)");
                }
                return bufnr == 7 ? DocumentWrite.Result.failed("read-only") : DocumentWrite.Result.saved("written");
            }
            @Override public void onIdeAction(IdeAction action) {
                events.add("action:" + action.name() + ":" + action.count()
                        + (action.step() == 0 ? "" : ":step " + action.step()));
            }
            @Override public void onHostCommand(HostCommand command) {
                events.add("command:" + command.kind() + (command.force() ? "!" : "") + ":" + command.args()
                        + (command.origin() == HostCommand.NO_WINDOW ? "" : " in " + command.origin()));
            }
            @Override public void onQuickfix(QuickfixEntries.Kind kind, String arg, boolean bang) {
                events.add("quickfix:" + kind + (bang ? "!" : "") + ":" + arg);
            }
            @Override public void onShowView(QuickfixEntries.Kind kind) {
                events.add("view:" + kind);
            }
            @Override public void onCmdlineType(String type) { events.add("cmdline:" + type); }
            @Override public void onMessages(List<ViewText.Message> history) { events.add("messages:" + history); }
            @Override public void onViewContent(ViewContent content) {
                events.add(switch (content) {
                    case ViewText text -> "view:" + text.title() + ":" + text.lines();
                    case PageBuffer page -> "page:" + page.title() + ":" + page.buffer() + ":" + page.line();
                });
            }
            @Override public void onGridEvents(List<GridEvent> grid) { events.add("grid:" + grid); }
            @Override public void onCommandWindow(int winid, String type) { events.add("cmdwin:" + winid + ":" + type); }
            @Override public void onCommandWindowClosed() { events.add("cmdwin closed"); }
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
        try (ExpectedLog log = ExpectedLog.of(NotificationBridge.class)) {
            notify("eclipse_command", "substitute", false, List.of()); // not Eclipse's: dropped
            log.assertLogged(Level.WARNING, "Eclipse cannot carry out :substitute");
        }
        notify("eclipse_command", "quit");                          // malformed: dropped
        assertEquals(List.of("command:EDIT:[my file.txt]", "command:QUIT!:[]"), events);
    }

    /** The fourth value is the window the command ran in; without it, or not a number, the command has no origin. */
    @Test
    void hostCommandCarriesTheWindowItRanIn() {
        notify("eclipse_command", "quit", false, List.of(), 1004);
        notify("eclipse_command", "quit", false, List.of());
        notify("eclipse_command", "quit", false, List.of(), "not a window");
        assertEquals(List.of("command:QUIT:[] in 1004", "command:QUIT:[]", "command:QUIT:[]"), events);
    }

    /** {@code :Eproblems! warning}, {@code :Etasks}: the kind, the argument, the bang; and {@code :copen}'s view. */
    @Test
    void quickfixCommandsCarryKindArgumentAndBang() {
        notify("eclipse_quickfix", "problems", "warning", true);
        notify("eclipse_quickfix", "tasks", "JM6,FIXME", false);
        notify("eclipse_quickfix", "problems");           // no argument, no bang
        try (ExpectedLog log = ExpectedLog.of(NotificationBridge.class)) {
            notify("eclipse_quickfix", "vimgrep", "", false); // not a kind of ours: dropped
            notify("eclipse_quickfix");                       // malformed: dropped
            log.assertLogged(Level.WARNING, "Ignored eclipse_quickfix [vimgrep");
            log.assertLogged(Level.WARNING, "Ignored eclipse_quickfix []");
        }
        notify("eclipse_show_view", "tasks");
        notify("eclipse_show_view", "other");
        assertEquals(List.of("quickfix:PROBLEMS!:warning", "quickfix:TASKS:JM6,FIXME", "quickfix:PROBLEMS:",
                "view:TASKS"), events);
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
     * to open it. LuaScripts.DOCUMENT_BUFFER: a document's buffer reloaded by Neovim says whether it
     * kept its text (an :edit!: only the attach is renewed) or is empty (Eclipse loads the text).
     */
    @Test
    void aProjectFileComesWithItsNameAndAReloadWithItsBuffer() {
        notify("eclipse_buf_enter", 5, 1002L, true, "/ws/app/B.java");
        notify("eclipse_buf_enter", 6, 1002L, false, "");
        notify("eclipse_buf_enter", 7, 1002L, false, 3L); // not a name: none
        // A help page that took the help buffer as its page (Ctrl-] in the help editor).
        notify("eclipse_buf_enter", 8, 1003L, false, "", true);
        notify("eclipse_reload", 4, true);
        notify("eclipse_reload", 3, false);
        notify("eclipse_reload", 2); // no flag: not kept
        notify("eclipse_reload");
        assertEquals(List.of("enter:5#1002 jump file /ws/app/B.java", "enter:6#1002", "enter:7#1002",
                "enter:8#1003 follows", "reload:4 kept", "reload:3", "reload:2"), events);
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

    /** LuaScripts.SEARCH: four numbers per match; an incomplete group or a payload without a list is ignored. */
    @Test
    void searchMatchesComeInGroupsOfFour() {
        notify("eclipse_search", 3, List.of(1, 2, 1, 5, 3, 0, 4, 3), 1001);
        notify("eclipse_search", 3, List.of(), 1001);
        notify("eclipse_search", 3, List.of(1, 2, 1), 1001);
        notify("eclipse_search", 3);
        assertEquals(List.of("search@3:" + List.of(new SearchMatch(1, 2, 1, 5), new SearchMatch(3, 0, 4, 3)) + "#1001",
                "search@3:[]#1001", "search@3:[]#1001"), events);
    }

    /**
     * LuaScripts.HIGHLIGHTS: six numbers per highlight (the range, the background, the text color or
     * -1); an incomplete group or a payload without a list is ignored.
     */
    @Test
    void highlightsComeInGroupsOfSix() {
        notify("eclipse_highlights", 3, List.of(1, 4, 1, 7, 0xFCE094, 0x07080D, 2, 0, 2, 3, 0x112233, -1), 1001);
        notify("eclipse_highlights", 3, List.of(), 1001);
        notify("eclipse_highlights", 3, List.of(1, 4, 1, 7, 0xFCE094), 1001);
        notify("eclipse_highlights", 3);
        assertEquals(List.of("hl@3:" + List.of(new NeovimHighlight(new SearchMatch(1, 4, 1, 7), 0xFCE094, 0x07080D),
                new NeovimHighlight(new SearchMatch(2, 0, 2, 3), 0x112233, NeovimHighlight.NO_COLOR)) + "#1001",
                "hl@3:[]#1001", "hl@3:[]#1001"), events);
    }

    /**
     * A jump plug-in's report: a backdrop with a text color only, and its labels as the fourth
     * argument. A range with neither color has nothing to paint and is left out.
     */
    @Test
    void highlightsMayComeWithATextColorOnlyAndWithLabels() {
        notify("eclipse_highlights", 3, List.of(1, 0, 1, 10, -1, 0x777777, 2, 0, 2, 3, -1, -1), 1001,
                List.of(List.of(1, 7, 0, "a", 0x101010, 0xF0F0F0, true)));
        assertEquals(List.of("hl@3:" + List.of(new NeovimHighlight(new SearchMatch(1, 0, 1, 10), NeovimHighlight.NO_COLOR, 0x777777))
                + "+" + List.of(new JumpLabels.Label(1, 7, 0, "a", 0x101010, 0xF0F0F0, true)) + "#1001"), events);
    }

    /** A list of ranges far longer than a screen of text can hold is cut, not passed on whole. */
    @Test
    void noMoreHighlightsThanTheLimitAreTaken() {
        List<Object> flat = new ArrayList<>();
        for (int i = 0; i < NotificationBridge.MAX_HIGHLIGHTS + 10; i++) {
            flat.addAll(List.of(1, i, 1, i + 1, 0x112233, -1));
        }
        notify("eclipse_highlights", 3, flat, 1001);
        assertEquals(1, events.size());
        assertEquals(NotificationBridge.MAX_HIGHLIGHTS, events.get(0).split("NeovimHighlight\\[", -1).length - 1);
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

    /** LuaScripts.UI2's window ids: cmd, msg, pager, dialog; one ui2 has not made (-1) or not a number is left out. */
    @Test
    void ui2sWindowsCarryTheirIds() {
        notify("eclipse_ui2_windows", 1002L, 1003L, 1004L, 1005L);
        notify("eclipse_ui2_windows", 1002L, -1L, "x", 1005L);
        notify("eclipse_ui2_windows", 1002L, 1003L, 1004L, 1005L, true); // the user's 'cmdheight' 0
        notify("eclipse_ui2_windows", 1002L, 1003L, 1004L, 1005L, "yes"); // only true is
        notify("eclipse_ui2_windows", -1L, 1003L, 1004L, 1005L, true); // no command line window yet
        assertEquals(List.of("ui2:[1002, 1003, 1004, 1005]", "ui2:[1002, 1005]", "ui2:[1002, 1003, 1004, 1005] overlay",
                "ui2:[1002, 1003, 1004, 1005]", "ui2:[1003, 1004, 1005] overlay cmd -1"), events);
    }

    /** LuaScripts.STATUS_LINE's every window's line ({@code util/StatusLines}); no argument, no lines. */
    @Test
    void statusLinesArePassedOnPerWindow() {
        List<Object> line = List.of(true, List.of("a", "b"), List.of(1, 1), List.of(List.of(-1, -1, false, false, false, false, false)));
        notify("eclipse_status_lines", List.of(List.of(1000L, line), List.of(1001L, List.of(false))));
        notify("eclipse_status_lines");
        assertEquals(List.of("statuslines:[1000=ab, 1001=-]", "statuslines:[]"), events);
    }

    /** The two notifications around Eclipse's write of a buffer (LuaScripts.SET_DOCUMENT_LINES). */
    @Test
    void anEchoStartsAndEndsForItsBuffer() {
        notify("eclipse_echo", 4, true);
        notify("eclipse_echo", 4, false);
        notify("eclipse_echo");
        assertEquals(List.of("echo@4:true", "echo@4:false"), events);
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
        try (ExpectedLog log = ExpectedLog.of(NotificationBridge.class)) {
            assertEquals(List.of(), paste("!"));
            log.assertLogged(Level.WARNING, "Reading the system clipboard for Neovim failed");
        }
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
        assertEquals(List.of("", "written", ""), request("eclipse_write", 3, " ++p"));
        assertEquals(List.of("read-only", "", ""), request("eclipse_write", 7L, ""));
        try (ExpectedLog log = ExpectedLog.of(NotificationBridge.class)) {
            assertEquals(List.of("Eclipse could not save the file: workspace closed (expected by the test)", "", ""), request("eclipse_write", 13));
            log.assertLogged(Level.WARNING, "Saving buffer 13 for Neovim failed");
        }
        assertEquals(List.of("Eclipse got a write request without a buffer number", "", ""), request("eclipse_write"));
        assertEquals(List.of("", "written", ""), request("eclipse_write", 3, "", "copy", "/ws/p/other.txt"));
        assertEquals(List.of("", "written", ""), request("eclipse_write", 3, "", "append", "/ws/p/log.txt", 5L, 10L, true));
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

    /**
     * A nil changedtick marks an {@code 'inccommand'} preview of {@code :s}: the screen changed, not
     * the buffer, and no event follows when the preview ends. Dropped, so Eclipse's document never
     * takes a preview as an edit; the real change after Enter has its changedtick.
     */
    @Test
    void bufferLinesEventOfAPreviewIsDropped() {
        notify("nvim_buf_lines_event", 1, null, 0, 3, List.of("XlphX", "betX", "gXmmX"), false);
        notify("nvim_buf_lines_event", 1, 6, 0, 3, List.of("XlphX", "betX", "gXmmX"), false);
        assertEquals(List.of("lines@1:0:3:[XlphX, betX, gXmmX]"), events);
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
        // Neovim sends these too: mode changes to every UI, changedtick events to nvim_buf_attach.
        notify("redraw", List.of("mode_change", List.of("normal", 0)));
        notify("nvim_buf_changedtick_event", 1, 5);
        assertTrue(events.isEmpty(), events.toString());
    }

    /** LuaScripts.UI2: each command line's type as it opens, and "" as it closes. */
    @Test
    void aCommandLinesTypeAndItsEnd() {
        notify("eclipse_cmdline", "/");
        notify("eclipse_cmdline", "");
        notify("eclipse_cmdline");
        assertEquals(List.of("cmdline:/", "cmdline:", "cmdline:"), events);
    }

    /** LuaScripts.UI2: :messages, each message as {kind, text}; an entry that is no pair is skipped. */
    @Test
    void theMessageHistoryForItsPage() {
        notify("eclipse_messages", List.of(List.of("", "3 lines yanked"), List.of("emsg", "E486: x"), "junk"));
        notify("eclipse_messages");
        assertEquals(List.of("messages:[" + new ViewText.Message("", "3 lines yanked") + ", "
                + new ViewText.Message("emsg", "E486: x") + "]"), events);
    }

    /** The command-line window: told by the plug-in's Lua; its grid comes with the redraws. */
    @Test
    void theCommandLineWindowAndItsGrid() {
        notify("eclipse_cmdwin", 1004, ":");
        notify("redraw", List.of("grid_line", List.of(4, 0, 0, List.of(List.of("x", 0)), false)),
                List.of("win_pos", List.of(4, io.github.jm6cnddxbwftaks.neovim4eclipse.core.rpc.Handle.window(1004), 31, 0, 120, 7)),
                List.of("flush"));
        notify("eclipse_cmdwin_closed");
        assertEquals(List.of("cmdwin:1004::",
                "grid:[" + new GridEvent.Line(4, 0, 0, List.of(new GridEvent.Cell("x", 0))) + ", "
                        + new GridEvent.WinPos(4, 1004, false, 31, 0, 120, 7, 0) + ", " + new GridEvent.Flush() + "]",
                "cmdwin closed"), events, "the window's handle read as its ID");
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

    /**
     * {@code eclipse_view_page}: a buffer of Neovim's own for a page editor, a help page or a
     * {@code :checkhealth} report. Only those two kinds are known, and only with a buffer and a line
     * of 1 or more; anything else reaches nobody.
     */
    @Test
    void aPageBufferOfAKnownKindOnly() {
        notify("eclipse_view_page", "help", 7, 27);
        notify("eclipse_view_page", "checkhealth", 9L, 1);
        notify("eclipse_view_page", "messages", 3, 1); // not a kind the net hands over
        notify("eclipse_view_page", "help", List.of(7), 27); // no number
        notify("eclipse_view_page", "help", 7, 0);
        notify("eclipse_view_page", "help", -1, 1);
        notify("eclipse_view_page", "help", 7);
        notify("eclipse_view_page");
        assertEquals(List.of("page:help:7:27", "page:checkhealth:9:1"), events);
    }
}
