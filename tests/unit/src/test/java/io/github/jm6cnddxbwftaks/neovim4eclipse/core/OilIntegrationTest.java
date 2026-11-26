// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.PinnedPlugins;
import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * oil.nvim through the session, at the release pinned and vetted in
 * {@code releng/test-plugins.properties}: a file manager whose float lists a folder as lines of a
 * buffer, and that moves, makes and deletes files when that buffer is written. What it asks of the
 * session: its float opens with the keyboard ({@code require('oil').open_float(folder)}), its
 * buffer takes the keys of a rename ({@code cf.}), its {@code :w} reaches oil's own writer, not the
 * plug-in's, and its confirmation float takes the {@code y}; the file is then moved on disk, its
 * text as it was. No program starts ({@link PinnedPlugins#REFUSE_PROGRAMS}). The same with keys on
 * a real editor, an editor open on the file moved, is {@code OilEndToEndTest}. Skipped without the
 * plug-in ({@link PinnedPlugins#HOW}).
 *
 * <p>The test has a Neovim of its own: oil's setup acts on the whole Neovim.
 */
@Tag("integration")
class OilIntegrationTest {

    @Test
    void aLineRenamedInTheFloatMovesTheFileOnceWritten(@TempDir Path folder) throws Exception {
        TestNeovim.require();
        Optional<Path> oil = PinnedPlugins.find("oil.nvim");
        assumeTrue(oil.isPresent(), "oil.nvim is not fetched at its pinned commit: " + PinnedPlugins.HOW);
        Path before = folder.resolve("open-me.txt");
        Path after = folder.resolve("moved.txt");
        Files.writeString(before, "one\ntwo\n");
        try (NeovimSession s = NeovimSession.start(TestNeovim.PATH, List.of("--clean"))) {
            s.lua(PinnedPlugins.loadOil(oil.get()) + " return 0");
            // In the folder, as an editor's window is in its project's: oil's confirmation names
            // each file from Neovim's working folder, so the move reads as in the editor.
            s.lua("vim.fn.chdir(" + PinnedPlugins.longString(folder) + ") return 0");
            s.lua(PinnedPlugins.openOilFloat(folder) + " return 0");
            // oil fills its buffer once it has read the folder, after the call returns.
            TestWait.until("oil's float listing open-me.txt, with the keyboard",
                    () -> Boolean.TRUE.equals(s.lua("return vim.bo.filetype == 'oil'"
                            + " and vim.api.nvim_win_get_config(0).relative ~= ''"
                            + " and vim.fn.search('open-me', 'nw') > 0")),
                    () -> state(s));

            s.lua("vim.fn.search('open-me') return 0");
            s.input("cf.");
            s.input("moved.");
            s.input("<Esc>");
            TestWait.until("the line renamed, Normal mode", () -> Boolean.TRUE.equals(
                    s.lua("return vim.fn.search('moved\\\\.txt', 'nw') > 0"
                            + " and vim.api.nvim_get_mode().mode == 'n'")),
                    () -> state(s));
            assertEquals("one\ntwo\n", Files.readString(before), "nothing moves before the write");

            s.input(":w<CR>");
            // y carries out every action the confirmation lists: answered only to the one move.
            TestWait.until("oil's confirmation with the keyboard, listing the one move",
                    () -> MOVE.equals(s.lua(PinnedPlugins.OIL_CONFIRMATION)),
                    () -> "it lists '" + confirmation(s) + "'; " + state(s));
            s.input("y");
            TestWait.until("open-me.txt moved to moved.txt",
                    () -> Files.exists(after) && !Files.exists(before),
                    () -> "on disk " + names(folder) + "; " + state(s));
            assertEquals("one\ntwo\n", Files.readString(after), "the file's text, moved with it");
            assertEquals("", s.lua(PinnedPlugins.OIL_CONFIRMATION), "the confirmation closed");
            assertEquals("", s.lua(PinnedPlugins.PROGRAMS_REFUSED), "oil asked to start no program");
        }
    }

    /** The one action the rename makes, as oil's confirmation lists it from the folder itself. */
    private static final String MOVE = "MOVE open-me.txt -> moved.txt";

    private static String confirmation(NeovimSession s) {
        try {
            return String.valueOf(s.lua(PinnedPlugins.OIL_CONFIRMATION));
        } catch (Exception e) {
            return "(Neovim did not answer: " + e + ")";
        }
    }

    /** Neovim's mode, current buffer and its lines, last error: for a failure's message. */
    private static String state(NeovimSession s) {
        try {
            return String.valueOf(s.lua("return 'mode ' .. vim.api.nvim_get_mode().mode"
                    + " .. ', buffer ' .. vim.api.nvim_buf_get_name(0)"
                    + " .. ' [' .. table.concat(vim.api.nvim_buf_get_lines(0, 0, -1, false), '|') .. ']'"
                    + " .. ', errmsg ' .. vim.v.errmsg"));
        } catch (Exception e) {
            return "Neovim did not answer: " + e;
        }
    }

    private static List<String> names(Path folder) {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return List.of("(not listed: " + e + ")");
        }
    }
}
