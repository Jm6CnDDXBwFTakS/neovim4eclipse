// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.Completion;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimExecutable;

/**
 * Tab in the {@code :} box, against a real {@code nvim} ({@link NeovimSession#completeAsync}, the
 * {@code COMPLETE_CMDLINE} script): the completed lines, and that Neovim is left exactly as it was.
 * Skipped when no Neovim is found (see {@link NeovimSessionIntegrationTest}).
 */
@Tag("integration")
class CommandCompletionIntegrationTest {

    private static final String NVIM = System.getProperty("nvim.path", "nvim");

    @TempDir
    Path dir;

    private static NeovimSession start() throws Exception {
        assumeTrue(NvimExecutable.looksAvailable(NVIM), "nvim not available");
        return NeovimSession.start(NVIM, List.of("--clean"));
    }

    /** Completes on the input thread and waits; {@code null} when Neovim gave no answer. */
    private static Completion complete(NeovimSession s, String line, String steps, Path... folders) throws Exception {
        CompletableFuture<Completion> answer = new CompletableFuture<>();
        s.completeAsync(line, steps, Arrays.stream(folders).map(Path::toString).toList(), answer::complete);
        s.runAsync("no answer", () -> answer.complete(null)); // runs after completeAsync's work
        return answer.get(10, TimeUnit.SECONDS);
    }

    private static String line(NeovimSession s, String line, String steps, Path... folders) throws Exception {
        Completion completion = complete(s, line, steps, folders);
        assertNotNull(completion, "no answer for " + line + " " + steps);
        return completion.line();
    }

    private Path files(String... names) throws Exception {
        for (String name : names) {
            Files.createFile(dir.resolve(name));
        }
        return dir;
    }

    @Test
    void tabCyclesThroughTheMatchesAndBackToWhatWasTyped() throws Exception {
        try (NeovimSession s = start()) {
            // tabNext, tabnew, tabnext: the plug-in sets 'ignorecase', which command names follow.
            List<String> candidates = complete(s, "tabn", "T").candidates();
            assertTrue(candidates.containsAll(List.of("tabnew", "tabnext")), candidates.toString());
            for (int i = 0; i < candidates.size(); i++) {
                assertEquals(candidates.get(i), line(s, "tabn", "T".repeat(i + 1)), "the list is in Tab's order");
            }
            assertEquals("tabn", line(s, "tabn", "T".repeat(candidates.size() + 1)));
        }
    }

    @Test
    void shiftTabStartsFromTheLastMatchAndGoesBack() throws Exception {
        try (NeovimSession s = start()) {
            List<String> candidates = complete(s, "tabn", "T").candidates();
            int last = candidates.size() - 1;
            assertEquals(candidates.get(last), line(s, "tabn", "TPP"));
            assertEquals(candidates.get(last - 1), line(s, "tabn", "TPPP"));
            assertEquals("tabn", line(s, "tabn", "TP"));
        }
    }

    @Test
    void optionValuesAreAppended() throws Exception {
        try (NeovimSession s = start()) {
            assertEquals("set ts=8", line(s, "set ts=", "T"));
        }
    }

    @Test
    void keyNamesInTheLineAreTextNotKeys() throws Exception {
        try (NeovimSession s = start()) {
            String first = line(s, "tabn", "T");
            assertEquals("echo \"<Tab><CR>\" | " + first, line(s, "echo \"<Tab><CR>\" | tabn", "T"));
        }
    }

    @Test
    void editCompletesFileNamesInTheGivenFolder() throws Exception {
        files("alpha.txt", "alpine.txt", "with space.txt");
        try (NeovimSession s = start()) {
            assertEquals("e alpha.txt", line(s, "e al", "T", dir));
            assertEquals("e alpine.txt", line(s, "e al", "TT", dir));
            assertEquals("e with\\ space.txt", line(s, "e with", "T", dir));
        }
    }

    @Test
    void editTriesTheSecondFolderWhenTheFirstHasNoMatch(@TempDir Path empty) throws Exception {
        files("alpha.txt");
        try (NeovimSession s = start()) {
            assertEquals("e alpha.txt", line(s, "e al", "T", empty, dir));
            assertEquals("e alpha.txt", line(s, "e al", "T", empty.resolve("gone"), dir), "a missing folder is skipped");
        }
    }

    @Test
    void neovimIsLeftAsItWas(@TempDir Path local) throws Exception {
        files("alpha.txt");
        try (NeovimSession s = start()) {
            String before = state(s);
            complete(s, "e al", "T", dir);
            complete(s, "tabn", "TT");
            assertEquals(before, state(s));

            // nvim_cmd takes the folder as an argument, with no escaping to get right on Windows.
            s.lua("vim.cmd.lcd({ args = { [==[" + local + "]==] }, mods = { noautocmd = true } })");
            String windowLocal = state(s);
            complete(s, "e al", "T", dir);
            assertEquals(windowLocal, state(s), "the window's own directory is kept");
        }
    }

    /** Everything the script changes for a moment, as one comparable text. */
    private static String state(NeovimSession s) throws Exception {
        return String.valueOf(s.lua("""
                return table.concat({ vim.api.nvim_get_mode().mode, vim.fn.getcwd(), vim.fn.haslocaldir(),
                  vim.fn.getcwd(-1, -1), vim.o.wildmode, vim.o.wildcharm, vim.o.eventignore,
                  vim.fn.histnr(':'), vim.fn.histnr('='), tostring(_G.__eclipse_capture) }, ' | ')
                """));
    }

    @Test
    void aMacroBeingRecordedDoesNotGetTheCompletionKeys() throws Exception {
        try (NeovimSession s = start()) {
            s.input("q");
            s.input("a");
            complete(s, "tabn", "T");
            s.input("q");
            assertEquals("", s.lua("return vim.fn.getreg('a')"));
        }
    }

    @Test
    void wildmodeLongestIsFollowed() throws Exception {
        files("alpha.txt", "alpine.txt");
        try (NeovimSession s = start()) {
            s.command("set wildmode=longest:full");
            assertEquals("e alp", line(s, "e al", "T", dir));
        }
    }

    @Test
    void wildmodeListDoesNotLeaveNeovimAtAPrompt() throws Exception {
        files("alpha.txt", "alpine.txt");
        try (NeovimSession s = start()) {
            s.command("set wildmode=list:longest");
            assertEquals("e alp", line(s, "e al", "T", dir));
            assertEquals("n false", s.lua("local m = vim.api.nvim_get_mode() return m.mode .. ' ' .. tostring(m.blocking)"));
            assertEquals("list:longest", s.lua("return vim.o.wildmode"));
        }
    }

    @Test
    void nothingIsCompletedOutsideNormalMode() throws Exception {
        try (NeovimSession s = start()) {
            s.input("i");
            assertEquals(null, complete(s, "tabn", "T"));
            s.input("<Esc>");
            assertEquals(List.of(""), s.getBufferLines(), "no ':tabn' typed into the buffer");
        }
    }

    @Test
    void theListIsCappedButTheTotalIsKept() throws Exception {
        try (NeovimSession s = start()) {
            Completion completion = complete(s, "set ", "T"); // every option name
            assertNotNull(completion);
            assertEquals(NeovimSession.COMPLETION_CAP, completion.candidates().size());
            assertTrue(completion.total() > NeovimSession.COMPLETION_CAP, "total " + completion.total());
        }
    }
}
