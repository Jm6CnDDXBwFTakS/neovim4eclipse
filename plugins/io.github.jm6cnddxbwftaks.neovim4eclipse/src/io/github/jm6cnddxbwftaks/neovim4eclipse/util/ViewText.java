// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Text for a page editor: the message history ({@code :messages}), which arrives from Neovim's ui2
 * as a list of messages ({@code LuaScripts.UI2}), not as a buffer, and which the plug-in puts into a page buffer of the editing Neovim
 * ({@code LuaScripts.PAGE_TEXT}). Example: {@code :messages} becomes
 * {@code ViewText("messages", ["E486: Pattern not found: x", "3 lines yanked"], [(0, "ErrorMsg")])}.
 *
 * @param title      what the page editor's title says after "Neovim: ": {@code messages}
 * @param lines      the text, one string per line, without line breaks
 * @param highlights lines drawn in a highlight group of Neovim's
 */
public record ViewText(String title, List<String> lines, List<LineHighlight> highlights)
        implements ViewContent {

    /** The title of {@link #messages}. */
    public static final String MESSAGES = "messages";

    /** Message kinds that are errors, drawn in {@code ErrorMsg}. */
    static final Set<String> ERROR_KINDS = Set.of("emsg", "echoerr", "lua_error", "rpc_error");
    /** Message kinds that are warnings, drawn in {@code WarningMsg}. */
    static final Set<String> WARNING_KINDS = Set.of("wmsg");

    /**
     * One message of the history: its kind as Neovim names it ({@code "emsg"}, {@code "wmsg"},
     * {@code "echoerr"}, {@code ""} ...) and its text, which may hold line breaks.
     */
    public record Message(String kind, String text) {
    }

    public ViewText {
        lines = List.copyOf(lines);
        highlights = List.copyOf(highlights);
    }

    /** Line {@code line} (0-based) of the text is drawn in Neovim's highlight group {@code group}. */
    public record LineHighlight(int line, String group) {
    }

    /**
     * {@code :messages} as its page editor shows it: each entry on its own lines, an error in
     * {@code ErrorMsg}, a warning in {@code WarningMsg}, the rest in the default colors. A message
     * of three lines (a Lua error and its traceback) colors all three. No highlight definitions are
     * copied: the page draws Neovim's own {@code ErrorMsg}.
     */
    public static ViewText messages(List<Message> history) {
        List<String> lines = new ArrayList<>();
        List<LineHighlight> highlights = new ArrayList<>();
        for (Message entry : history) {
            String group = groupOf(entry.kind());
            for (String line : entry.text().replace("\r", "").split("\n", -1)) {
                if (!group.isEmpty()) {
                    highlights.add(new LineHighlight(lines.size(), group));
                }
                lines.add(line);
            }
        }
        return new ViewText(MESSAGES, lines, highlights);
    }

    /** The highlight group a message of {@code kind} is drawn in, {@code ""} for the default colors. */
    static String groupOf(String kind) {
        if (ERROR_KINDS.contains(kind)) {
            return "ErrorMsg";
        }
        return WARNING_KINDS.contains(kind) ? "WarningMsg" : "";
    }
}
