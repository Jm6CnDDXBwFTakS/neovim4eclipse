// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.ArrayList;
import java.util.List;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.LineRangeEdit.LineSource;

/**
 * A {@link LineSource} over a plain string, with Eclipse's line model: {@code \n}, {@code \r\n} and
 * {@code \r} end lines, and text ending in a line break has an empty last line after it (as
 * {@code IDocument.getNumberOfLines()} counts it). Stands in for a JFace document in unit tests.
 */
final class StringLineSource implements LineSource {

    private final String text;
    private final List<Integer> starts = new ArrayList<>();
    private final List<Integer> contentLengths = new ArrayList<>();

    StringLineSource(String text) {
        this.text = text;
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                starts.add(start);
                contentLengths.add(i - start);
                i += c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n' ? 2 : 1;
                start = i;
            } else {
                i++;
            }
        }
        starts.add(start); // last line; empty when the text ends with a delimiter
        contentLengths.add(text.length() - start);
    }

    @Override
    public int lineCount() {
        return starts.size();
    }

    @Override
    public int lineOffset(int line) {
        return starts.get(line);
    }

    @Override
    public int lineContentLength(int line) {
        return contentLengths.get(line);
    }

    @Override
    public int length() {
        return text.length();
    }

    @Override
    public String text(int offset, int length) {
        return text.substring(offset, offset + length);
    }
}
