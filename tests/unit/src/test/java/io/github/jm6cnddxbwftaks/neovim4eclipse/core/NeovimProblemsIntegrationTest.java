// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * The opt-out of Eclipse's problems, {@code vim.g.neovim4eclipse_problems = 0}, read by the session
 * at start after the user's config ({@link NeovimSession#problemsEnabled()}). What the script does
 * with the keys is {@code diagnostics_spec.lua}; here only the read through the session, with the
 * variable set as an {@code init.lua} would (before the session starts). Skipped when no
 * {@code nvim} is found.
 */
@Tag("integration")
class NeovimProblemsIntegrationTest {

    private static NeovimSession start(String... extraArgs) throws Exception {
        assumeTrue(NvimExecutable.looksAvailable(TestNeovim.PATH), "nvim not available");
        List<String> args = new ArrayList<>(List.of("--clean"));
        args.addAll(List.of(extraArgs));
        return NeovimSession.start(TestNeovim.PATH, args);
    }

    @Test
    void problemsAreSentUnlessTheConfigSaysZero() throws Exception {
        try (NeovimSession s = start()) {
            assertTrue(s.problemsEnabled(), "not set");
        }
        try (NeovimSession s = start("--cmd", "let g:neovim4eclipse_problems = 0")) {
            assertFalse(s.problemsEnabled(), "0");
            // Neovim's ]d is wrapped only to say why it finds nothing.
            // (The message goes to :messages too, as Neovim's own does: easier to read back than ext_messages.)
            Object messages = s.lua("vim.cmd('normal ]d') return vim.fn.execute('messages')");
            assertTrue(String.valueOf(messages).contains(
                    "]d: Eclipse's problems are not sent to Neovim (vim.g.neovim4eclipse_problems = 0)"),
                    () -> ":messages holds " + messages);
        }
    }
}
