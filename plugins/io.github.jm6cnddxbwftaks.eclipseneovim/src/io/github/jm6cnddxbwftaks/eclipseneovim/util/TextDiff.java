// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.LineRangeEdit.Edit;

/**
 * Computes the smallest single replacement that turns one text into another, by trimming the
 * common prefix and suffix.
 *
 * <p>Used for full re-syncs so that Eclipse sees a small, local edit instead of
 * {@code IDocument.set()} on the whole text. A whole-document set discards annotations, folding
 * and marker positions and resets the caret; a local edit keeps them.
 */
public final class TextDiff {

    private TextDiff() {
    }

    /**
     * @return the edit to apply to {@code oldText}, or {@code null} if the texts are identical
     */
    public static Edit minimalEdit(String oldText, String newText) {
        String a = oldText == null ? "" : oldText;
        String b = newText == null ? "" : newText;
        if (a.equals(b)) {
            return null;
        }
        int max = Math.min(a.length(), b.length());
        int prefix = 0;
        while (prefix < max && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        // Never cut between the two halves of a surrogate pair.
        if (prefix > 0 && Character.isHighSurrogate(a.charAt(prefix - 1))) {
            prefix--;
        }
        int suffix = 0;
        int maxSuffix = max - prefix;
        while (suffix < maxSuffix && a.charAt(a.length() - 1 - suffix) == b.charAt(b.length() - 1 - suffix)) {
            suffix++;
        }
        if (suffix > 0 && Character.isLowSurrogate(a.charAt(a.length() - suffix))) {
            suffix--;
        }
        return new Edit(prefix, a.length() - prefix - suffix, b.substring(prefix, b.length() - suffix));
    }
}
