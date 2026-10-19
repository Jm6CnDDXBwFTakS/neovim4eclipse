// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.jm6cnddxbwftaks.eclipseneovim.core.ClipboardPort.Selection;

/**
 * Neovim's clipboard registers {@code "+} and {@code "*}, kept in the system clipboard through a
 * {@link ClipboardPort}: Eclipse is Neovim's <em>clipboard provider</em>.
 *
 * <h2>The concept</h2>
 * Neovim has no clipboard code of its own. When a command uses {@code "+} or {@code "*} (or any
 * register, with {@code clipboard=unnamedplus}), it calls a provider: a pair of functions,
 * {@code copy} and {@code paste}, configured in {@code g:clipboard} (see {@code :help clipboard}).
 * Neovim's own providers run tools such as {@code xclip} or {@code pbcopy}. The one installed by
 * {@link LuaScripts#SETUP} asks Eclipse instead, so pasting works with no tool installed and
 * shares Eclipse's clipboard: copy in another program, then {@code "+p} in the editor.
 *
 * <h2>What a register holds that the clipboard does not</h2>
 * A register knows whether it holds characters ({@code v}), whole lines ({@code V}) or a block
 * (Ctrl-V, {@code b}); the system clipboard holds only text. Two rules bridge the gap:
 * <ul>
 *   <li>Neovim passes linewise and blockwise text with a final empty line, so the text ends with a
 *       line break, as a whole-line copy does in every other application.</li>
 *   <li>When the clipboard still holds what Neovim copied last, the paste returns Neovim's own
 *       lines and type, so {@code "+yy} then {@code "+p} pastes a line and a block stays a block.
 *       Otherwise (text copied in another program) only the lines are returned and Neovim decides
 *       the type itself: linewise if the text ends with a line break, characters if not. That is
 *       also what Neovim's own providers do.</li>
 * </ul>
 * The comparison ignores how lines are separated: the text is written with the platform's
 * separator ({@code \r\n} on Windows) and some programs change it.
 *
 * <p>Thread-safe (every method is {@code synchronized}). Today all calls come from the one RPC
 * thread, in the order Neovim sent them, so a copy is always remembered before the paste that
 * follows it; nothing here relies on that thread, though.
 */
final class ClipboardRegisters {

    /**
     * What a paste hands back to Neovim.
     *
     * @param lines   the lines, split at any line break; empty if the clipboard has no text
     * @param regtype {@code v}, {@code V} or a block type when it is known (Neovim copied this text),
     *                or {@code null} to let Neovim decide from the text
     */
    record Paste(List<String> lines, String regtype) {

        static final Paste NOTHING = new Paste(List.of(), null);

        /**
         * The value the provider's paste function returns: {@code [lines, regtype]} or just
         * {@code lines} (the two forms {@code :help clipboard} allows).
         */
        Object toRpc() {
            return regtype == null ? lines : List.of(lines, regtype);
        }
    }

    /** What Neovim last copied to a selection: the text as written (normalized) and the register. */
    private record Copied(String text, List<String> lines, String regtype) {
    }

    private final ClipboardPort port;
    private final String lineSeparator;
    private final Map<Selection, Copied> lastCopied = new EnumMap<>(Selection.class);

    /**
     * @param lineSeparator joins lines on the clipboard, normally {@link System#lineSeparator()}
     *                      (a parameter so that tests can check Windows behavior anywhere)
     */
    ClipboardRegisters(ClipboardPort port, String lineSeparator) {
        this.port = Objects.requireNonNull(port);
        this.lineSeparator = Objects.requireNonNull(lineSeparator);
    }

    /** The selection a register name stands for: {@code *} is the primary selection, anything else the clipboard. */
    static Selection selectionOf(String register) {
        return "*".equals(register) ? Selection.PRIMARY : Selection.CLIPBOARD;
    }

    /**
     * The provider's copy: Neovim wrote register {@code register} ({@code +} or {@code *}).
     *
     * @param lines   as Neovim passes them: a final empty line for linewise and blockwise text
     * @param regtype Neovim's register type, kept for {@link #paste}
     */
    synchronized void copy(String register, List<String> lines, String regtype) {
        Selection selection = selectionOf(register);
        String text = String.join(lineSeparator, lines);
        lastCopied.put(selection, new Copied(normalize(text), List.copyOf(lines), regtype));
        port.copy(selection, text);
    }

    /**
     * A yank into any other register ({@code yy}, {@code dw}, {@code "ay}), which this plug-in also
     * copies to the clipboard, so that text yanked in Neovim can be pasted in other programs.
     *
     * @param lines   the register contents ({@code v:event.regcontents}: no final empty line)
     * @param regtype {@code v}, {@code V}, or Ctrl-V followed by the block width
     */
    synchronized void copyYank(List<String> lines, String regtype) {
        List<String> asProvided = new ArrayList<>(lines);
        if (!"v".equals(regtype)) {
            asProvided.add(""); // the form copy() gets: whole lines and blocks end with a line break
        }
        copy("+", asProvided, regtype);
    }

    /** The provider's paste: Neovim reads register {@code register}. */
    synchronized Paste paste(String register) {
        Selection selection = selectionOf(register);
        String text = port.paste(selection).orElse(null);
        if (text == null || text.isEmpty()) {
            return Paste.NOTHING;
        }
        String normalized = normalize(text);
        Copied copied = lastCopied.get(selection);
        if (copied != null && copied.text().equals(normalized)) {
            return new Paste(copied.lines(), copied.regtype());
        }
        return new Paste(Arrays.asList(normalized.split("\n", -1)), null);
    }

    /** Every line break as {@code \n}: Windows writes {@code \r\n}, old Mac programs {@code \r}. */
    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
