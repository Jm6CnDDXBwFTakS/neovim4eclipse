// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Text for an SWT label that shows it as it is.
 *
 * <h2>The concept: mnemonics</h2>
 * SWT's {@code Label} and {@code CLabel} (and so JFace's {@code MessageDialog}, whose message is a
 * Label) read a single {@code &} as a mnemonic marker: the {@code &} is not drawn, the next letter
 * is underlined, and Alt with that letter moves the focus. That suits a button's own label
 * ({@code &Apply}), not text from outside: a file named {@code R&D.txt} in a message or the status
 * bar showed as "RD.txt" with the D underlined. {@code &&} shows one {@code &}. Tooltips take no
 * mnemonics, so their text needs nothing.
 */
public final class LabelText {

    private LabelText() {
    }

    /**
     * {@code text} with each {@code &} doubled, so a label shows it unchanged; {@code null} gives "".
     * Example: {@code "R&D.txt" 3L written} becomes {@code "R&&D.txt" 3L written}.
     */
    public static String literal(String text) {
        return text == null ? "" : text.replace("&", "&&");
    }
}
