// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;

/**
 * mini.nvim's mini.pairs and mini.ai in a real editor, at the release pinned and vetted in
 * {@code releng/test-plugins.properties}, with keys pressed on the editor. In an insert Eclipse
 * types, mini.pairs is never asked: the bracket Eclipse typed stays one, also in what Neovim
 * recorded once {@code Esc} has handed the insert to it (the re-typing uses no mapping), which
 * {@code .} replays. In an insert Neovim types (one recorded into a macro) mini.pairs closes the
 * bracket and takes the pair out with Backspace, as in a terminal. mini.ai's own {@code cia} (an
 * argument) and {@code daf} (a call over two lines) change the text; Neovim has neither. Eclipse's text and Neovim's agree after each test (the text check). The same through
 * the session alone is {@code MiniIntegrationTest}. Skipped without the plug-in
 * ({@link PinnedPlugins#HOW}).
 *
 * <p>The modules' mappings are the whole Neovim's, which the UI tests share, so each test ends
 * with <em>Restart Neovim</em> ({@code restartNeovimAfterThisTest}).
 */
class MiniEndToEndTest extends EndToEndTestBase {

    /** Backspace, as both the character and the key code of the key event. */
    private static final char BACKSPACE = '\b';

    /** Whether this test loaded the plug-in, so that its Neovim is replaced afterwards. */
    private boolean loaded;

    @BeforeEach
    void loadMini() {
        Optional<Path> mini = PinnedPlugins.find("mini.nvim");
        assumeTrue(mini.isPresent(), "mini.nvim is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        loaded = true;
        SessionProbe.lua(session, PinnedPlugins.loadMini(mini.get()) + " return 0");
    }

    @AfterEach
    void restartNeovim() {
        if (!loaded) {
            return; // skipped, or the editor never opened: nothing was loaded
        }
        NeovimSession fresh = restartNeovimAfterThisTest();
        assertEquals(true, SessionProbe.lua(fresh, "return package.loaded['mini.pairs'] == nil"),
                "the next test's Neovim never loaded mini.nvim");
    }

    @Test
    void anInsertEclipseTypesKeepsItsBracketsAndARecordedOneGetsThePairs() {
        document.set("one\n");
        settle();
        type(widget, "G");
        type(widget, "o");
        waitUntil("an insert Eclipse types", TIMEOUT_MS,
                () -> session.isTypingMode() && session.isEclipseTypingReady() && !session.isNeovimDrivenInsert());
        type(widget, "(x");
        assertEquals("one\n(x\n", document.get(), "Eclipse typed the bracket, and no other");
        escape(widget);
        waitUntil("Normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        // Eclipse's document is pushed to Neovim after the re-typing, so the text alone could not
        // tell a re-typing that went through mini.pairs: . replays what Neovim recorded, and that
        // would hold its closing bracket.
        type(widget, ".");
        expectText(". replays the insert with one bracket: the re-typing took no mapping", "one\n(x\n(x\n");

        type(widget, "qq");
        type(widget, "o");
        waitUntil("an insert Neovim types, recorded", TIMEOUT_MS,
                () -> session.isTypingMode() && session.isNeovimDrivenInsert());
        type(widget, "(");
        expectText("( put in ()", "one\n(x\n(x\n()\n");
        type(widget, "y");
        expectText("the y between the brackets", "one\n(x\n(x\n(y)\n");
        type(widget, BACKSPACE);
        expectText("Backspace took the y", "one\n(x\n(x\n()\n");
        type(widget, BACKSPACE);
        expectText("Backspace took the pair", "one\n(x\n(x\n\n");
        escape(widget);
        type(widget, "q");
        waitUntil("the insert and the recording over", TIMEOUT_MS,
                () -> "n".equals(session.getMode()) && !session.isNeovimDrivenInsert());
    }

    @Test
    void miniAisOwnTextObjectsChangeTheText() {
        document.set("foo(bar, baz) end\nx = f(1,\n  2) + 3\n");
        settle();
        type(widget, "gg0");
        settle();
        type(widget, "cia");
        escape(widget);
        expectText("cia from before the bracket emptied the first argument", "foo(, baz) end\nx = f(1,\n  2) + 3\n");
        waitUntil("Normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
        type(widget, "j0ff");
        settle();
        type(widget, "daf");
        expectText("daf took the call over two lines", "foo(, baz) end\nx =  + 3\n");
    }

    private void expectText(String what, String text) {
        waitUntil(what, TIMEOUT_MS, () -> text.equals(document.get()),
                () -> "; document " + document.get().replace("\n", "|") + ", mode " + session.getMode());
    }
}
