// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

/**
 * Test text with the caret and selection drawn in: a real JFace {@link Document}, built in one line.
 *
 * <p>Markers, removed from the text:
 * <ul>
 *   <li>{@code |} the caret;</li>
 *   <li>{@code «} and {@code »} the start and end of a selection. Without a {@code |}, the caret is at
 *       the {@code »} end, as after a drag to the right.</li>
 * </ul>
 *
 * <pre>{@code
 * VimText t = VimText.parse("al|pha\n\tbeta");   // caret before "pha"
 * t.document().get();                           // "alpha\n\tbeta"
 * t.caret();                                    // 2
 * VimText s = VimText.parse("«alpha»\nbeta");     // "alpha" selected, caret after it
 * }</pre>
 *
 * <p>Text that contains a {@code |} itself takes another caret marker:
 * {@code VimText.parse("a || b#", '#')}. {@link #SAMPLE} is a ready-made text with the cases that
 * break position code: tabs, multi-byte characters, an empty line and a final line break.
 *
 * @param text           the text without markers
 * @param caret          the caret offset (0 when no marker)
 * @param selectionStart start of the selection; the caret when nothing is selected
 * @param selectionLength number of selected characters; 0 when nothing is selected
 */
public record VimText(String text, int caret, int selectionStart, int selectionLength) {

    /**
     * Five lines, each a known trap for column code: a tab ({@code \t} is 1 char and 1 byte but
     * several screen columns), {@code é} and {@code ü} (1 char, 2 UTF-8 bytes), {@code 😀} (2 UTF-16
     * chars, 4 bytes), an empty line, and a final line break (Neovim sees no extra line).
     */
    public static final String SAMPLE = "first line\n\tindented with a tab\naé ü\nsmile 😀 end\n\nlast\n";

    private static final char SELECTION_START = '«';
    private static final char SELECTION_END = '»';

    /** Parses {@code marked} with {@code |} as the caret marker. */
    public static VimText parse(String marked) {
        return parse(marked, '|');
    }

    /** Parses {@code marked} with {@code caretMarker} as the caret marker. */
    public static VimText parse(String marked, char caretMarker) {
        StringBuilder text = new StringBuilder(marked.length());
        int caret = -1;
        int start = -1;
        int end = -1;
        for (int i = 0; i < marked.length(); i++) {
            char c = marked.charAt(i);
            if (c == caretMarker) {
                caret = once(caret, text.length(), "caret marker " + caretMarker, marked);
            } else if (c == SELECTION_START) {
                start = once(start, text.length(), "selection start «", marked);
            } else if (c == SELECTION_END) {
                end = once(end, text.length(), "selection end »", marked);
            } else {
                text.append(c);
            }
        }
        if ((start < 0) != (end < 0) || end < start) {
            throw new IllegalArgumentException("« and » must both be present, « first: " + marked);
        }
        if (start < 0) {
            int at = Math.max(caret, 0);
            return new VimText(text.toString(), at, at, 0);
        }
        return new VimText(text.toString(), caret < 0 ? end : caret, start, end - start);
    }

    private static int once(int previous, int offset, String what, String marked) {
        if (previous >= 0) {
            throw new IllegalArgumentException("more than one " + what + ": " + marked);
        }
        return offset;
    }

    /** A new document holding {@link #text()}; each call gives a fresh one. */
    public IDocument document() {
        return new Document(text);
    }

    /** The caret as a 1-based line and 0-based UTF-16 column, e.g. {@code {2, 1}}. */
    public int[] caretLineCol() {
        IDocument doc = document();
        try {
            int line = doc.getLineOfOffset(caret);
            return new int[] { line + 1, caret - doc.getLineOffset(line) };
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A {@link FakeCaret} over {@code document} with this caret and selection; no listeners fire. */
    FakeCaret fakeCaret(IDocument document) {
        FakeCaret fake = new FakeCaret(document);
        fake.place(caret, selectionStart, selectionLength);
        return fake;
    }
}
