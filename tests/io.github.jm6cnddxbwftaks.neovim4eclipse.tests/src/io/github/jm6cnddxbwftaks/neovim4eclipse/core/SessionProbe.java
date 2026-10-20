// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

/**
 * Lets the UI tests ask Neovim directly, for checks the editor cannot show: an error message
 * ({@code v:errmsg}), the buffer list. {@link NeovimSession#lua} is package-private; this class is
 * in the same package, and the test fragment shares the plug-in's class loader, so it may call it.
 *
 * <p>Blocks until Neovim answers. Fine on the UI thread for these small reads; not for anything
 * that makes Neovim ask Eclipse something back (such as the clipboard).
 */
public final class SessionProbe {

    private SessionProbe() {
    }

    /** Runs {@code code} in Neovim and returns its result as Neovim sends it. */
    public static Object lua(NeovimSession session, String code) {
        try {
            return session.lua(code);
        } catch (Exception e) {
            throw new AssertionError("Neovim did not run: " + code, e);
        }
    }
}
