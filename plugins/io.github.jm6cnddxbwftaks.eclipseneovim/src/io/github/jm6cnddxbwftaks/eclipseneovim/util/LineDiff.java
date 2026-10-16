// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.List;

/**
 * Smallest single line-range change that turns one list of lines into another: the common leading
 * and trailing lines are kept, everything between is replaced.
 *
 * <p>Used when pushing the Eclipse document into a Neovim buffer. Replacing the whole buffer would
 * add an undo step on every push (even when nothing changed) and delete the marks on every line;
 * replacing only the differing range keeps undo history and marks outside it.
 */
public final class LineDiff {

    /** Replace lines {@code [start, end)} (0-based) of the old list with {@code replacement}. */
    public record Change(int start, int end, List<String> replacement) {
    }

    private LineDiff() {
    }

    /** @return the change, or {@code null} when both lists are equal */
    public static Change between(List<String> oldLines, List<String> newLines) {
        int oldSize = oldLines.size();
        int newSize = newLines.size();
        int prefix = 0;
        int maxPrefix = Math.min(oldSize, newSize);
        while (prefix < maxPrefix && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }
        if (prefix == oldSize && prefix == newSize) {
            return null;
        }
        // The suffix may not overlap the prefix: with old [a, a] and new [a], prefix is 1 and the
        // single remaining old "a" must be deleted, not matched again.
        int suffix = 0;
        while (suffix < oldSize - prefix && suffix < newSize - prefix
                && oldLines.get(oldSize - 1 - suffix).equals(newLines.get(newSize - 1 - suffix))) {
            suffix++;
        }
        return new Change(prefix, oldSize - suffix, List.copyOf(newLines.subList(prefix, newSize - suffix)));
    }
}
