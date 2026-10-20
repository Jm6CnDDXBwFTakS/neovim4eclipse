// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Neovim's answer to Tab in the {@code :} box: the completed line and the candidates to list.
 *
 * <p>Example: for {@code tabn} and one Tab, {@code line} is {@code tabnew}, {@code candidates} are
 * {@code [tabnew, tabnext]} and {@code total} is 2. {@code candidates} can be shorter than
 * {@code total}: Neovim sends at most a capped number ({@code :h } has thousands).
 *
 * @param line       the command line as Neovim's own Tab left it, without the {@code :}
 * @param candidates the first candidates, in Neovim's order
 * @param total      how many candidates there are in all
 */
public record Completion(String line, List<String> candidates, int total) {

    public Completion {
        candidates = List.copyOf(candidates);
        total = Math.max(total, candidates.size());
    }

    /**
     * Reads the answer of the {@code COMPLETE_CMDLINE} Lua script, {@code {line, total, candidates}},
     * which arrives through msgpack-rpc as a {@link List}.
     *
     * @return the completion, or {@code null} when Neovim did not complete (it was not in normal
     *         mode) or the answer is not understood
     */
    public static Completion parse(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 3 || list.get(0) == null) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        if (list.get(2) instanceof List<?> items) {
            for (Object item : items) {
                if (item != null) {
                    candidates.add(String.valueOf(item));
                }
            }
        }
        int total = list.get(1) instanceof Number n ? n.intValue() : candidates.size();
        return new Completion(String.valueOf(list.get(0)), candidates, total);
    }
}
