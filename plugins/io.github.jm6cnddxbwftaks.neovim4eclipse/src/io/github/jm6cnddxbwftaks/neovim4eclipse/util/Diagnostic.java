// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One Eclipse problem as Neovim stores it: a diagnostic in the sense of {@code :help diagnostic-structure}.
 *
 * <h2>Why Eclipse's problems become Neovim diagnostics</h2>
 * Eclipse knows where the errors and warnings are (the Java compiler, a language server, any builder);
 * Neovim knows how to move between them: {@code ]d} / {@code [d} (next / previous, with a count),
 * {@code ]D} / {@code [D} (last / first), all of them in the jumplist and in macros. Mirroring each
 * open document's problems into its buffer with {@code vim.diagnostic.set()} makes those default keys
 * work without the plug-in mapping anything (see {@code docs/design/DIAGNOSTICS.md}).
 *
 * <h2>Positions</h2>
 * As in Neovim: {@code lnum} and {@code endLnum} are 0-based lines, {@code col} and {@code endCol} are
 * 0-based <em>UTF-8 byte</em> columns, the end exclusive. Example: an error over {@code bar} in the
 * second line {@code "é bar"} is {@code lnum 1, col 3, endLnum 1, endCol 6} ({@code é} is two bytes).
 * {@link DiagnosticPositions} makes them from Eclipse's document offsets.
 *
 * @param lnum     0-based line where the problem starts
 * @param col      0-based byte column where it starts
 * @param endLnum  0-based line where it ends
 * @param endCol   0-based byte column after its last character (equal to {@code col} for an empty range)
 * @param severity how bad it is
 * @param message  Eclipse's text for it, such as {@code "x cannot be resolved"}; never {@code null}
 */
public record Diagnostic(int lnum, int col, int endLnum, int endCol, Severity severity, String message) {

    /**
     * Eclipse's three problem severities and their {@code vim.diagnostic.severity} numbers. Neovim
     * has a fourth, {@code HINT} (4), which no Eclipse problem maps to.
     */
    public enum Severity {
        /** {@code vim.diagnostic.severity.ERROR}. */
        ERROR(1),
        /** {@code vim.diagnostic.severity.WARN}. */
        WARN(2),
        /** {@code vim.diagnostic.severity.INFO}. */
        INFO(3);

        private final int neovimNumber;

        Severity(int neovimNumber) {
            this.neovimNumber = neovimNumber;
        }

        /** The number {@code vim.diagnostic.set} takes in a diagnostic's {@code severity}. */
        public int neovimNumber() {
            return neovimNumber;
        }
    }

    /** How many arguments one diagnostic takes in {@link #toLuaArgs}. */
    public static final int LUA_ARGS_PER_DIAGNOSTIC = 6;

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        message = message == null ? "" : message;
    }

    /**
     * The arguments of {@code LuaScripts.SET_DIAGNOSTICS}: the buffer number, then six strings per
     * diagnostic (lnum, col, end_lnum, end_col, severity, message). Flat strings, because
     * {@code nvim_exec_lua} is called with a list of strings here; no JSON, so a message with quotes or
     * line breaks needs no escaping. Example: buffer 3 with one error
     * {@code ["3", "0", "4", "0", "7", "1", "x cannot be resolved"]}.
     */
    public static List<String> toLuaArgs(int bufnr, List<Diagnostic> diagnostics) {
        List<String> args = new ArrayList<>(1 + LUA_ARGS_PER_DIAGNOSTIC * diagnostics.size());
        args.add(Integer.toString(bufnr));
        for (Diagnostic d : diagnostics) {
            args.add(Integer.toString(d.lnum));
            args.add(Integer.toString(d.col));
            args.add(Integer.toString(d.endLnum));
            args.add(Integer.toString(d.endCol));
            args.add(Integer.toString(d.severity.neovimNumber()));
            args.add(d.message);
        }
        return args;
    }
}
