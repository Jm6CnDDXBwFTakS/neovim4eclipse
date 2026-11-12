// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.testing;

import org.eclipse.swt.widgets.Text;

/**
 * Reads an SWT {@link Text} field the same way on every platform. A multi-line field keeps its
 * lines apart with {@link Text#DELIMITER}, the platform's line break: {@code "\r\n"} on Windows,
 * {@code "\n"} elsewhere. So {@code setText("a\nb")} reads back as {@code "a\r\nb"} on Windows,
 * and a test that compares with the text it set fails there only.
 */
public final class TextFields {

    private TextFields() {
    }

    /**
     * {@code field}'s text with each {@link Text#DELIMITER} turned back into {@code "\n"}, and
     * nothing else changed: no line break is added or removed. Example: on Windows,
     * {@code "a\r\nb\r\n"} becomes {@code "a\nb\n"}.
     */
    public static String text(Text field) {
        return field.getText().replace(Text.DELIMITER, "\n");
    }
}
