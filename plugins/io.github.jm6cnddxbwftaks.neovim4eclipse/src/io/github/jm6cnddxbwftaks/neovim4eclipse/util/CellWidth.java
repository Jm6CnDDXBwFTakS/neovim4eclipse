// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * How many cells of Neovim's screen a character takes: 1 for most, 2 for the wide characters of
 * East Asian scripts and for emoji, 0 for a mark that combines with the character before it.
 *
 * <p>Why the plug-in needs it: Neovim says where things are in <em>cells</em> (the completion
 * list begins "at cell 4"), Eclipse's document counts {@code char}s. Example: in {@code 日本 al},
 * the {@code a} is at cell 5 but at char index 3. Text the plug-in lays out in cells itself (the
 * completion list's columns, {@link CompletionMenu}) is measured the same way.
 *
 * <p>An approximation of Neovim's own rule ({@code :help 'ambiwidth'}, the Unicode East Asian
 * Width table): the ranges below cover the scripts and symbols in everyday use, not every code
 * point. A wrong guess moves the list sideways by a cell; it never changes text.
 */
public final class CellWidth {

    private CellWidth() {
    }

    /** The cells of one code point: 0, 1 or 2. */
    public static int of(int codePoint) {
        if (codePoint < 0x300) {
            return 1; // Latin and the like: the common case, decided first
        }
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.FORMAT) {
            return 0; // combining accents, zero-width joiners
        }
        return isWide(codePoint) ? 2 : 1;
    }

    /** The cells of {@code text}; a tab counts as one, the caller's text has none that matter. */
    public static int of(String text) {
        int cells = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            cells += of(cp);
            i += Character.charCount(cp);
        }
        return cells;
    }

    /**
     * The char index reached by going back {@code cells} cells from {@code charIndex} in
     * {@code line}, never before the line's start. Example: {@code charsBack("日本 alpha", 8, 5)}
     * is 3, the {@code a}: five one-cell letters back from the end. A wide character that the
     * count ends inside of is taken whole.
     *
     * @param charIndex where to start, clamped into the line
     * @param cells     how far back; 0 or less gives {@code charIndex}
     */
    public static int charsBack(String line, int charIndex, int cells) {
        int index = Math.max(0, Math.min(charIndex, line.length()));
        int left = cells;
        while (left > 0 && index > 0) {
            int cp = line.codePointBefore(index);
            index -= Character.charCount(cp);
            left -= of(cp);
        }
        return index;
    }

    /**
     * {@code text} cut to at most {@code cells} cells, whole characters only. Example:
     * {@code fit("日本語", 5)} is {@code 日本}, four cells: the third character would need six.
     */
    public static String fit(String text, int cells) {
        int used = 0;
        int i = 0;
        int limit = Math.max(0, cells) * 8 + 8; // chars: stops a run of zero-width characters, which never fills a cell
        while (i < text.length() && i < limit) {
            int cp = text.codePointAt(i);
            int width = of(cp);
            if (used + width > cells) {
                break;
            }
            used += width;
            i += Character.charCount(cp);
        }
        return text.substring(0, i);
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F) // Hangul Jamo
                || (cp >= 0x2E80 && cp <= 0x303E) // CJK radicals, punctuation
                || (cp >= 0x3041 && cp <= 0x33FF) // Hiragana, Katakana, CJK symbols
                || (cp >= 0x3400 && cp <= 0x4DBF) // CJK extension A
                || (cp >= 0x4E00 && cp <= 0x9FFF) // CJK unified ideographs
                || (cp >= 0xA000 && cp <= 0xA4CF) // Yi
                || (cp >= 0xAC00 && cp <= 0xD7A3) // Hangul syllables
                || (cp >= 0xF900 && cp <= 0xFAFF) // CJK compatibility ideographs
                || (cp >= 0xFE30 && cp <= 0xFE4F) // CJK compatibility forms
                || (cp >= 0xFF00 && cp <= 0xFF60) // fullwidth forms
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1F64F) // emoji: symbols, pictographs, faces
                || (cp >= 0x1F900 && cp <= 0x1F9FF)
                || (cp >= 0x20000 && cp <= 0x3FFFD); // CJK extensions B and later
    }
}
