// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.MessageRoute.View;

/**
 * Where Neovim's messages go. The messages are the ones Neovim 0.12.6 sends for the commands named
 * in each test (experiments X1 and A1 of the cleanup plan); {@code MessageEventTest} reads them from
 * the wire.
 */
class MessageRouteTest {

    private final MessageRoute route = new MessageRoute();

    private void add(String kind, String text) {
        route.add(new MessageEvent.Show(kind, text, false, false, "", "typed_cmd"));
    }

    private static View line(String text, boolean error) {
        return new View(text, error, "", "");
    }

    private static View output(String title, String text) {
        return new View("", false, title, text);
    }

    // ---- the message line ----

    @Test
    void oneLineGoesToTheMessageLine() {
        add("", "3 lines yanked");
        assertEquals(line("3 lines yanked", false), route.view());
    }

    @Test
    void errorsAreMarkedAsErrors() {
        for (String kind : new String[] { "emsg", "echoerr", "lua_error", "rpc_error" }) {
            route.clear();
            add(kind, "E486: Pattern not found: x");
            assertEquals(line("E486: Pattern not found: x", true), route.view(), kind);
        }
    }

    /** A one-line {@code :echo}, {@code :lua print}, {@code :pwd}, a warning: the message line, as in Vim. */
    @Test
    void oneLineOfOutputThatIsNoListingGoesToTheMessageLineToo() {
        for (String kind : new String[] { "echo", "echomsg", "lua_print", "wmsg", "", "undo", "a_new_kind" }) {
            route.clear();
            add(kind, "hello");
            assertEquals(line("hello", false), route.view(), kind);
        }
    }

    /** The search's echo is not shown (the box showed the pattern); what follows it is. */
    @Test
    void theEchoOfASearchIsNotShown() {
        add("search_cmd", "/nope");
        add("emsg", "E486: Pattern not found: nope");
        assertEquals(line("E486: Pattern not found: nope", true), route.view());
    }

    /** {@code n} after a wrap: the count replaces the search's echo. */
    @Test
    void replaceLastReplacesThePreviousMessage() {
        add("search_cmd", "/a1");
        route.add(new MessageEvent.Show("search_count", "/a1          W [1/1]", true, false, "", ""));
        assertEquals(line("/a1          W [1/1]", false), route.view());
    }

    /** {@code :w} sends its message twice with id "bufwrite"; the second updates the first. */
    @Test
    void aMessageWithAKnownIdUpdatesThatMessage() {
        route.add(new MessageEvent.Show("progress", "\"a.txt\" ", false, false, "bufwrite", "typed_cmd"));
        route.add(new MessageEvent.Show("progress", "\"a.txt\" [New] 5L, 13B written", false, false, "bufwrite",
                "typed_cmd"));
        assertEquals(line("\"a.txt\" [New] 5L, 13B written", false), route.view());
    }

    @Test
    void nothingToShowWithoutMessagesOrWithEmptyOnes() {
        assertEquals(View.NONE, route.view());
        add("empty", "");
        add("", "   ");
        assertEquals(View.NONE, route.view());
    }

    @Test
    void clearForgetsTheMessages() {
        add("emsg", "E1: x");
        route.clear();
        assertEquals(View.NONE, route.view());
    }

    // ---- the output popup ----

    /** As on Neovim's own screen: a one-line listing needs no "Press ENTER" prompt. */
    @Test
    void aListingOfOneLineGoesToTheMessageLine() {
        route.commandLine(":set ts?");
        add("list_cmd", "  tabstop=8");
        assertEquals(new View("tabstop=8", false, "", ""), route.view());
    }

    /** The file info of a typed {@code :b} (without F in 'shortmess') comes as a listing too. */
    @Test
    void theFileInfoOfATypedBufferCommandGoesToTheMessageLine() {
        route.commandLine(":b other.txt");
        add("list_cmd", "\"/p/other.txt\" 2 lines --50%--");
        assertEquals("\"/p/other.txt\" 2 lines --50%--", route.view().line());
    }

    @Test
    void aListingOfMoreLinesGoesToThePopupTitledWithItsCommandLine() {
        route.commandLine(":ls");
        add("list_cmd", "\n  1 %a   \"a.txt\"  line 1\n  2      \"b.txt\"  line 0");
        assertEquals(output(":ls", "  1 %a   \"a.txt\"  line 1\n  2      \"b.txt\"  line 0"), route.view());
    }

    /** As Vim shows it above a "Press ENTER" prompt; the error color is not kept. */
    @Test
    void aMessageOfTwoLinesGoesToThePopupErrorsIncluded() {
        route.commandLine(":w");
        add("echoerr", "Error in BufWriteCmd Autocommands for \"<buffer=1>\":\nEclipse could not save a.txt: read-only");
        assertEquals(output(":w", "Error in BufWriteCmd Autocommands for \"<buffer=1>\":\n"
                + "Eclipse could not save a.txt: read-only"), route.view());
    }

    @Test
    void severalMessagesGoToThePopupTogether() {
        add("emsg", "E1: first");
        add("", "2 substitutions on 1 line");
        assertEquals(output("", "E1: first\n2 substitutions on 1 line"), route.view());
    }

    /** {@code :!seq 1 2}: the echo of the command goes, the output is appended in chunks. */
    @Test
    void shellOutputIsAppendedAndTheCommandsEchoDropped() {
        route.commandLine(":!seq 1 2");
        route.add(new MessageEvent.Show("shell_cmd", ":!seq 1 2\r\n", false, false, "1", "typed_cmd"));
        route.add(new MessageEvent.Show("shell_out", "1\n", false, true, "2", "typed_cmd"));
        route.add(new MessageEvent.Show("shell_out", "2\r\n", false, true, "3", "typed_cmd"));
        assertEquals(output(":!seq 1 2", "1\n2"), route.view());
    }

    /** What Neovim prints between the command and its output stays: {@code :!ls} with changes. */
    @Test
    void theRestOfTheShellCommandsMessageStays() {
        route.add(new MessageEvent.Show("shell_cmd", ":!ls\r\n[No write since last change]\n", false, false, "1",
                "typed_cmd"));
        route.add(new MessageEvent.Show("shell_out", "a.txt\n", false, true, "2", "typed_cmd"));
        assertEquals("[No write since last change]\na.txt", route.view().output());
    }

    /** {@code :!true}: nothing printed, nothing shown. */
    @Test
    void aShellCommandWithoutOutputShowsNothing() {
        route.add(new MessageEvent.Show("shell_cmd", ":!true\r\n", false, false, "1", "typed_cmd"));
        assertEquals(View.NONE, route.view());
    }

    @Test
    void theHistoryGoesToThePopup() {
        route.commandLine(":messages");
        route.add(new MessageEvent.History("E486: Pattern not found: x\n3 lines yanked"));
        assertEquals(output(":messages", "E486: Pattern not found: x\n3 lines yanked"), route.view());
    }

    /** A timer's message has no command line, so its output has no title. */
    @Test
    void outputNotFromATypedCommandHasNoTitle() {
        route.commandLine(":ls");
        route.add(new MessageEvent.Show("echo", "a\nb", false, false, "", ""));
        assertEquals(output("", "a\nb"), route.view());
    }

    /** The command line survives {@link MessageRoute#clear}: Enter, which clears, comes after it. */
    @Test
    void theCommandLineIsKeptAcrossClear() {
        route.commandLine(":ls");
        route.clear();
        add("list_cmd", "x\ny");
        assertEquals(":ls", route.view().title());
    }

    @Test
    void leadingBlankLinesAndTrailingSpaceGoButIndentationStays() {
        add("list_cmd", "\n\n  tabstop=8\n  shiftwidth=4  \n");
        assertEquals("  tabstop=8\n  shiftwidth=4", route.view().output());
    }
}
