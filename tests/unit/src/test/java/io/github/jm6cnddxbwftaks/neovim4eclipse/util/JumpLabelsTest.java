// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.JumpLabels.Label;

/** {@link JumpLabels}: the built-in prefixes, and reading the labels Neovim sends. */
class JumpLabelsTest {

    /** The built-in prefixes are the one list that names the plug-ins; nobody can change it. */
    @Test
    void theBuiltInPrefixesCannotBeChanged() {
        assertEquals(List.of("Flash", "Leap", "Hop", "MiniJump2d"), JumpLabels.BUILT_IN_PREFIXES);
        assertThrows(UnsupportedOperationException.class, () -> JumpLabels.BUILT_IN_PREFIXES.add("Mine"));
        assertThrows(UnsupportedOperationException.class, () -> JumpLabels.BUILT_IN_PREFIXES.set(0, "Mine"));
    }

    @Test
    void aLabelIsReadWithItsColorsAndWeight() {
        assertEquals(List.of(new Label(1, 1, 0, "a", 0x6B5300, 0xEEF1F8, false), new Label(2, 0, 1, "bc", -1, 0xFF00FC, true)),
                JumpLabels.parse(List.of(List.of(1, 1, 0, "a", 0x6B5300, 0xEEF1F8, false),
                        List.of(2L, (short) 0, (byte) 1, "bc", -1, 0xFF00FC, true))),
                "whatever integer type MessagePack decoded a number to");
    }

    /** No list (an older script sends no fourth argument), or entries of another shape: no label, no exception. */
    @Test
    void whatIsNoLabelIsLeftOut() {
        assertEquals(List.of(), JumpLabels.parse(null));
        assertEquals(List.of(), JumpLabels.parse("labels"));
        Label good = new Label(1, 0, 0, "a", 1, 2, false);
        List<Object> entries = new ArrayList<>();
        entries.add(List.of(1, 0, 0, "a", 1, 2, false));
        entries.add("text");                                         // no list
        entries.add(List.of(1, 0, 0, "a", 1, 2));                    // too short
        entries.add(List.of(0, 0, 0, "a", 1, 2, false));             // line 0
        entries.add(List.of(1, -1, 0, "a", 1, 2, false));            // a negative column
        entries.add(List.of(1, 0, -1, "a", 1, 2, false));            // a negative cell
        entries.add(List.of(1, 0, 0, "", 1, 2, false));              // nothing to draw
        entries.add(List.of(1, 0, 0, 7, 1, 2, false));               // the text is no string
        entries.add(List.of(1, 0, 0, "a", 0x1000000, 2, false));     // no color
        entries.add(List.of(1, 0, 0, "a", 1, -2, false));            // no color either
        entries.add(List.of(1.5, 0, 0, "a", 1, 2, false));           // no whole number
        entries.add(List.of(1L << 40, 0, 0, "a", 1, 2, false));      // does not fit an int
        entries.add(Arrays.asList(1, 0, 0, "a", null, 2, false));    // a missing value
        assertEquals(List.of(good), JumpLabels.parse(entries));
    }

    /** A text is cut to a few characters, at a code point's edge, and loses its control characters. */
    @Test
    void aLabelsTextIsCutAndCleaned() {
        String smiley = "😀"; // one code point, two chars
        assertEquals("abcdefgh", text("abcdefghij"));
        assertEquals("abcdefg", text("abcdefg" + smiley), "the pair would be split: left out whole");
        assertEquals("ab", text("a\u001Bb\n"), "an Esc and a line break are no label text");
        assertEquals("ab", text("a\u202E\u200Db\u2028"), "nor a right-to-left override, a zero-width joiner, a line separator");
        assertEquals(List.of(), JumpLabels.parse(List.of(List.of(1, 0, 0, "\u0007", 1, 2, false))), "nothing left: no label");
    }

    @Test
    void noMoreLabelsThanTheLimitAreTaken() {
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < JumpLabels.MAX_LABELS + 50; i++) {
            many.add(List.of(1, i, 0, "a", 1, 2, false));
        }
        assertEquals(JumpLabels.MAX_LABELS, JumpLabels.parse(many).size());
    }

    /** The bold flag is {@code true} only for a real {@code true}. */
    @Test
    void boldNeedsATrue() {
        assertEquals(false, JumpLabels.parse(List.of(List.of(1, 0, 0, "a", 1, 2, 1))).get(0).bold());
        assertEquals(true, JumpLabels.parse(List.of(List.of(1, 0, 0, "a", 1, 2, true))).get(0).bold());
    }

    private static String text(String raw) {
        return JumpLabels.parse(List.of(List.of(1, 0, 0, raw, 1, 2, false))).get(0).text();
    }
}
