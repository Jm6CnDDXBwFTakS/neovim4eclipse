// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.nio.charset.Charset;

/**
 * The message Vim shows after a write, such as {@code "new.txt" [New] 1L, 6B written}: the file
 * name, {@code [New]} when the write created the file, {@code [noeol]} when the text does not end
 * with a line break, then the number of lines and of bytes written.
 *
 * <p>Vim counts a last line without a line break as a line too: {@code hello} is
 * {@code [noeol] 1L, 5B}. An empty file is {@code 0L, 0B}. Bytes are counted in the file's
 * encoding, so {@code é} is 2 bytes in UTF-8 and 1 in ISO-8859-1. Vim's {@code [dos]} and
 * {@code [unix]} markers, which depend on {@code 'fileformat'}, are left out: Eclipse keeps the
 * line breaks the document has.
 */
public final class WrittenMessage {

    private WrittenMessage() {
    }

    /**
     * @param name    the file name as shown to the user, e.g. as typed after {@code :e}
     * @param created whether this write created the file
     * @param text    the text written
     * @param charset the encoding it was written in
     */
    public static String of(String name, boolean created, String text, Charset charset) {
        StringBuilder message = new StringBuilder();
        message.append('"').append(name).append('"');
        if (created) {
            message.append(" [New]");
        }
        boolean noEol = !text.isEmpty() && !endsWithLineBreak(text);
        if (noEol) {
            message.append(" [noeol]");
        }
        message.append(' ').append(lineCount(text)).append("L, ")
                .append(text.getBytes(charset).length).append("B written");
        return message.toString();
    }

    /** Lines as Vim counts them: each line break ends one; text after the last one is one more. */
    static int lineCount(String text) {
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n'))) {
                lines++; // \n, \r\n (counted at its \n) or a lone \r
            }
        }
        return !text.isEmpty() && !endsWithLineBreak(text) ? lines + 1 : lines;
    }

    private static boolean endsWithLineBreak(String text) {
        char last = text.charAt(text.length() - 1);
        return last == '\n' || last == '\r';
    }
}
