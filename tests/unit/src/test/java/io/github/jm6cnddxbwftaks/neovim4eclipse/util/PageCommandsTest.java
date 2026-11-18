// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageCommands.Action;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PageCommands.Decision;

/** What a page does with each command typed in it ({@link PageCommands}), row by row of its table. */
class PageCommandsTest {

    private static Decision typed(String name, boolean bang, String... args) {
        HostCommand command = HostCommand.of(name, bang, List.of(args));
        if (command == null) {
            throw new AssertionError("not a host command: " + name + " " + List.of(args));
        }
        return PageCommands.decide(command);
    }

    private static Action action(String name, String... args) {
        return typed(name, false, args).action();
    }

    @Test
    void whatVimDoesToTheWindowHappensToThePage() {
        assertEquals(Action.CLOSE_PAGE, action("quit"));
        assertEquals(Action.CLOSE_PAGE, action("close"));
        assertEquals(Action.CLOSE_PAGE, action("bdelete"), "no buffer named: this one");
        assertEquals(Action.CLOSE_PAGE, action("xit"), ":x writes only a change, and a page has none");
        assertEquals(Action.SECOND_PAGE, action("window", "split", "below", "same"), ":split, Ctrl-W s");
        assertEquals(Action.SECOND_PAGE, action("window", "split", "right", "same"), ":vsplit, Ctrl-W v");
        assertEquals(Action.SECOND_PAGE, action("window", "tab", "same"), ":tab split");
    }

    @Test
    void movingOnAndOpeningBesideAreDoneFromThePage() {
        assertEquals(Action.FROM_PAGE, action("window", "focus", "l", "0"));
        assertEquals(Action.FROM_PAGE, action("window", "only"));
        assertEquals(Action.FROM_PAGE, action("window", "size", "+", "5"));
        assertEquals(Action.FROM_PAGE, action("window", "split", "above", "new"), ":new, Ctrl-W n");
        assertEquals(Action.FROM_PAGE, action("window", "split", "right", "file", "/ws/app/b.txt"), ":vsplit b.txt");
        assertEquals(Action.FROM_PAGE, action("window", "tab", "new"), ":tabnew");
        assertEquals(Action.FROM_PAGE, action("tabnext"), "gt");
    }

    @Test
    void aFileGoesToTheCodesTabGroup() {
        assertEquals(Action.IN_CODE_GROUP, action("edit", "/ws/app/b.txt"));
        assertEquals(Action.IN_CODE_GROUP, action("enew"));
    }

    @Test
    void whatIsAboutEveryEditorIsDoneAsFromTheCode() {
        assertEquals(Action.AS_FROM_CODE, action("wall"));
        assertEquals(Action.AS_FROM_CODE, action("qall"));
        assertEquals(Action.AS_FROM_CODE, action("wqall"));
        assertEquals(Action.AS_FROM_CODE, action("bdelete", "3"), "another buffer, named");
        assertEquals(Action.AS_FROM_CODE, action("terminal"));
    }

    @Test
    void writingThePageOrEditingItsFileIsRefusedWithVimsError() {
        assertEquals(new Decision(Action.REFUSE, PageCommands.CANNOT_WRITE), typed("wq", false));
        assertEquals(new Decision(Action.REFUSE, PageCommands.NO_FILE_NAME), typed("edit", false));
        assertEquals(new Decision(Action.REFUSE, PageCommands.NO_FILE_NAME), typed("edit", true), ":e!");
    }
}
