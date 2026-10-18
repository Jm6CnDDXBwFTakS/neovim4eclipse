// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class IndentationTest {

    @Test
    void tabIndentedJava() {
        Indentation in = Indentation.detect("/p/A.java",
                List.of("class A {", "\tvoid f() {", "\t\tg();", "\t}", "}"), 4, true);
        assertEquals(new Indentation(true, 4, true), in);
    }

    @Test
    void fourSpaceJavaWithJavadoc() {
        Indentation in = Indentation.detect("A.java", List.of(
                "class A {",
                "    /**",
                "     * Docs, one space further in: not an indent level.",
                "     */",
                "    void f() {",
                "        g();",
                "    }",
                "}"), 4, false);
        assertEquals(new Indentation(false, 4, true), in);
    }

    @Test
    void twoSpaceJavaScriptWithATabWidthOfFour() {
        Indentation in = Indentation.detect("app.js", List.of(
                "function f() {", "  if (a) {", "    g();", "  }", "}"), 4, false);
        assertEquals(new Indentation(false, 2, true), in);
    }

    @Test
    void anUnindentedFileUsesTheEditorSetting() {
        assertEquals(new Indentation(false, 4, true), Indentation.detect("A.java", List.of("class A {}"), 4, true));
        assertEquals(new Indentation(true, 4, false), Indentation.detect("notes.txt", List.of(), 4, false));
    }

    @Test
    void braceLanguagesByExtension() {
        assertTrue(Indentation.isBraceLanguage("C:\\work\\Main.JAVA"));
        assertTrue(Indentation.isBraceLanguage("src/main.rs"));
        assertFalse(Indentation.isBraceLanguage("script.py"));
        assertFalse(Indentation.isBraceLanguage("/home/u/.bashrc"));
        assertFalse(Indentation.isBraceLanguage("Makefile"));
        assertFalse(Indentation.isBraceLanguage(null));
    }

    @Test
    void widthIsKeptToOneToEightColumns() {
        assertEquals(8, new Indentation(false, 40, false).width());
        assertEquals(1, new Indentation(false, 0, false).width());
    }
}
