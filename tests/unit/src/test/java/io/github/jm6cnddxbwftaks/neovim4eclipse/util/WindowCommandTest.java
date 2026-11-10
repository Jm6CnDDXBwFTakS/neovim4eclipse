// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WindowCommand.Action;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WindowCommand.Target;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.WindowCommand.Where;

/**
 * The words Neovim sends for a window command ({@code LuaScripts.WINDOW_COMMANDS} writes them, the
 * Lua spec windows_spec.lua checks that side), read into a {@link WindowCommand}.
 */
class WindowCommandTest {

    private static WindowCommand parse(String... words) {
        return WindowCommand.parse(List.of(words));
    }

    @Test
    void splitsSayTheSideAndWhatTheNewEditorShows() {
        assertEquals(new WindowCommand(Action.SPLIT, Where.BELOW, Target.SAME, "", (char) 0, 0),
                parse("split", "below", "same"));
        assertEquals(new WindowCommand(Action.SPLIT, Where.LEFT, Target.NEW, "", (char) 0, 0),
                parse("split", "left", "new"));
        assertEquals(new WindowCommand(Action.SPLIT, Where.RIGHT, Target.FILE, "/ws/my file.txt", (char) 0, 0),
                parse("split", "right", "file", "/ws/my file.txt"));
    }

    @Test
    void keysAndCountsAreKept() {
        assertEquals(new WindowCommand(Action.FOCUS, null, null, "", 'l', 2), parse("focus", "l", "2"));
        assertEquals(new WindowCommand(Action.SIZE, null, null, "", '|', 0), parse("size", "|", "0"));
        assertEquals(new WindowCommand(Action.EXCHANGE, null, null, "", (char) 0, 3), parse("exchange", "3"));
        assertEquals(new WindowCommand(Action.ROTATE, null, null, "", 'R', 1), parse("rotate", "up", "1"));
        assertEquals(Action.ONLY, parse("only").action());
    }

    @Test
    void whereSaysWhetherTheEditorsEndUpSideBySide() {
        assertEquals(true, Where.LEFT.sideBySide());
        assertEquals(true, Where.RIGHT.sideBySide());
        assertEquals(false, Where.ABOVE.sideBySide());
        assertEquals(false, Where.BELOW.sideBySide());
    }

    /** A hand-typed {@code :Eclipse window} with words Eclipse does not know: the caller shows an error. */
    @Test
    void unknownWordsAreNotACommand() {
        assertNull(WindowCommand.parse(List.of()));
        assertNull(parse("split"));
        assertNull(parse("split", "sideways", "same"));
        assertNull(parse("split", "below", "file"), "a file split names its file");
        assertNull(parse("split", "below", "same", "extra"));
        assertNull(parse("focus", "z", "0"), "not a focus key");
        assertNull(parse("focus", "l", "x"), "not a count");
        assertNull(parse("focus", "l", "-1"));
        assertNull(parse("size", "+", "1234567"), "a count of more than six digits");
        assertNull(parse("rotate", "left", "0"));
        assertNull(parse("only", "now"));
        assertNull(parse("vanish"));
    }

    @Test
    void aHostCommandOfKindWindowCarriesIt() {
        HostCommand command = HostCommand.of("window", false, List.of("focus", "w", "0"));
        assertEquals(HostCommand.Kind.WINDOW, command.kind());
        assertEquals(new WindowCommand(Action.FOCUS, null, null, "", 'w', 0), command.window());
        assertNull(HostCommand.of("window", false, List.of("focus", "nowhere", "0")), "words Eclipse does not know");
        assertNull(HostCommand.of("quit", false, List.of()).window(), "another kind");
    }
}
