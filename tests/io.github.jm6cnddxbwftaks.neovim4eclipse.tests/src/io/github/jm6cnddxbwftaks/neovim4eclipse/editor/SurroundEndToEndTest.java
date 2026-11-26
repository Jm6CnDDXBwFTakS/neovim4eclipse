// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.ctrlKey;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.escape;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.type;
import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.SessionProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;

/**
 * vim-surround and vim-repeat in a real editor, at the commits pinned and vetted in
 * {@code releng/test-plugins.properties}: the README's "plug-ins that work on text work as in a
 * terminal", with keys pressed on the editor. {@code ysiw"} and its {@code .}, {@code cs"'}, a tag
 * typed at the prompt, and {@code Ctrl-G s} in an insert Eclipse types, the caret after the
 * letter typed into the pair; Eclipse's text and Neovim's agree after each test (the text check).
 * The same through the session alone is {@code SurroundIntegrationTest}. Skipped without the
 * plug-ins ({@link PinnedPlugins#HOW}).
 *
 * <p>The plug-in's mappings ({@code ys}, {@code cs}, {@code ds}, Visual {@code S}, Insert
 * {@code Ctrl-G s}) are the whole Neovim's, which the UI tests share, so each test ends with
 * <em>Restart Neovim</em> ({@code restartNeovimAfterThisTest}).
 */
class SurroundEndToEndTest extends EndToEndTestBase {

    private static final String LINES = "alpha beta gamma\ndelta epsilon\nzeta eta\n";

    /** Whether this test loaded the plug-ins, so that its Neovim is replaced afterwards. */
    private boolean loaded;

    @BeforeEach
    void loadSurround() {
        Optional<Path> surround = PinnedPlugins.find("vim-surround");
        Optional<Path> repeat = PinnedPlugins.find("vim-repeat");
        assumeTrue(surround.isPresent() && repeat.isPresent(),
                "vim-surround and vim-repeat are not fetched at their pinned commits: " + PinnedPlugins.HOW);
        loaded = true;
        SessionProbe.lua(session, PinnedPlugins.loadSurround(surround.get(), repeat.get()) + " return 0");
        document.set(LINES);
        settle();
        type(widget, "gg0");
        settle();
    }

    @AfterEach
    void restartNeovim() {
        if (!loaded) {
            return; // skipped, or the editor never opened: nothing was loaded
        }
        NeovimSession fresh = restartNeovimAfterThisTest();
        assertEquals("", SessionProbe.lua(fresh, "return vim.fn.maparg('ys', 'n')"),
                "the next test's Neovim has no vim-surround mapping");
    }

    @Test
    void ysAndItsRepeatCsAndATagChangeTheText() {
        type(widget, "ysiw\"");
        expectText("the word surrounded", "\"alpha\" beta gamma\ndelta epsilon\nzeta eta\n");
        type(widget, "fb");
        settle();
        type(widget, ".");
        expectText(". repeated it through vim-repeat", "\"alpha\" \"beta\" gamma\ndelta epsilon\nzeta eta\n");
        type(widget, "cs\"'");
        expectText("the quotes around the cursor changed", "\"alpha\" 'beta' gamma\ndelta epsilon\nzeta eta\n");
        type(widget, "jj0");
        settle();
        type(widget, "ysiw<em>");
        expectText("a tag typed at the prompt, ended by >",
                "\"alpha\" 'beta' gamma\ndelta epsilon\n<em>zeta</em> eta\n");
        waitUntil("Normal mode", TIMEOUT_MS, () -> "n".equals(session.getMode()));
    }

    /**
     * {@code o}, {@code x} typed by Eclipse, then {@code Ctrl-G s"}: a pair after the {@code x},
     * the caret between; the {@code y} typed next goes in there, the caret after it, and then the
     * insert is Eclipse's again: it types the {@code z}. The {@code y} itself goes through Neovim:
     * the mapping types its pair through an expression ({@code <C-R>=}), and Eclipse takes the
     * typing back only after one more key, as after a key typed before Eclipse had Neovim's text
     * ({@code KeyInput.handBackToEclipse}).
     */
    @Test
    void ctrlGSInAnInsertEclipseTypesPutsInAPair() throws Exception {
        type(widget, "G");
        type(widget, "o");
        waitUntil("an insert Eclipse types", TIMEOUT_MS,
                () -> session.isTypingMode() && session.isEclipseTypingReady() && !session.isNeovimDrivenInsert());
        type(widget, "x");
        ctrlKey(widget, 'g');
        type(widget, "s\"");
        expectText("a pair after the x", LINES + "x\"\"\n");
        type(widget, "y");
        int afterY = LINES.length() + "x\"y".length();
        waitUntil("the y between the quotes, the caret after it", TIMEOUT_MS,
                () -> (LINES + "x\"y\"\n").equals(document.get()) && widget.getCaretOffset() == afterY,
                () -> "; document " + document.get().replace("\n", "|") + ", caret " + widget.getCaretOffset());
        waitUntil("the insert Eclipse's again", TIMEOUT_MS,
                () -> session.isTypingMode() && session.isEclipseTypingReady() && !session.isNeovimDrivenInsert(),
                () -> "; driven " + session.isNeovimDrivenInsert() + ", ready " + session.isEclipseTypingReady());
        type(widget, "z");
        assertEquals(LINES + "x\"yz\"\n", document.get(), "Eclipse typed the z, after the y");
        escape(widget);
        waitUntil("Normal mode, the insert over", TIMEOUT_MS,
                () -> "n".equals(session.getMode()) && !session.isNeovimDrivenInsert());
        assertEquals(List.of("alpha beta gamma", "delta epsilon", "zeta eta", "x\"yz\""), session.getBufferLines());
    }

    private void expectText(String what, String text) {
        waitUntil(what, TIMEOUT_MS, () -> text.equals(document.get()),
                () -> "; document " + document.get().replace("\n", "|") + ", mode " + session.getMode());
    }
}
