// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core.rpc;

/**
 * The answer to {@code nvim_get_mode()}: Neovim's current mode and whether it is blocked.
 *
 * <p>Neovim answers with a map, for example {@code {"mode": "n", "blocking": false}} in normal
 * mode, or {@code {"mode": "n", "blocking": true}} right after {@code f}, while it waits for the
 * character to find. While it is blocked, Neovim defers most other API calls until the command is
 * complete; {@code nvim_get_mode} is one of the few it answers at once, so the plug-in asks it first
 * after each key (see {@code StateSync}).
 *
 * <p>This is the plug-in's own type, so that code outside the RPC layer does not depend on
 * neovim-java's {@code VimMode} (see {@code docs/RPC_CLIENT.md}, migration step 1). The mode
 * strings themselves are interpreted by {@code util/VimModes}.
 *
 * @param mode     the mode code, such as {@code "n"}, {@code "i"}, {@code "v"} or {@code "no"}
 *                 (see {@code :help mode()}); may be {@code null} if Neovim sent none
 * @param blocking {@code true} if Neovim is waiting for the rest of a command (an operator's motion,
 *                 the character after {@code f}, the register after {@code "})
 */
public record Mode(String mode, boolean blocking) {
}
