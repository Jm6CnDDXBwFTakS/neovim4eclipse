// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RepairReportTest {

    @Test
    void theMessageSaysWhereWhenAndWhatKindOfFileButNoTextOrName() {
        String m = new RepairReport(RepairReport.fileKind("Secret.java", "Java Editor"), "at Esc", 11, 11, 40, 41,
                "0.12.6", "0.2.16").message();
        assertEquals("Neovim's copy of a .java file in the Java Editor differed from Eclipse's, and Eclipse's lines"
                + " were written into Neovim's: Neovim's line 12 (of 40) differed from Eclipse's line 12"
                + " (of 41), and no change explains it. Found at Esc. Neovim 0.12.6, Neovim for Eclipse 0.2.16. Please"
                + " report it, with this message (it holds no text of the file): " + RepairReport.ISSUES_URL, m);
        assertTrue(!m.contains("Secret"), "the file's name is left out: a log is shared");
    }

    @Test
    void anUnknownVersionIsLeftOut() {
        assertTrue(new RepairReport("a file", "after a save", 0, 0, 1, 1, "", "0.2.16").message()
                .contains(" Found after a save. Neovim for Eclipse 0.2.16. Please"));
        assertTrue(new RepairReport("a file", "after a save", 0, 0, 1, 1, "", "").message()
                .contains(" Found after a save. Please"));
    }

    @Test
    void aFilesKindIsItsExtensionAndEditor() {
        assertEquals("a .java file in the Java Editor", RepairReport.fileKind("Main.java", "Java Editor"));
        assertEquals("a .gz file in the Text Editor", RepairReport.fileKind("a.tar.gz", "Text Editor"));
        assertEquals("a file without extension in the Text Editor", RepairReport.fileKind("Makefile", "Text Editor"));
        assertEquals("a file without extension", RepairReport.fileKind(".gitignore", ""), "a leading dot is no extension");
        assertEquals("a file without extension", RepairReport.fileKind("name.", null));
        assertEquals("a file without extension", RepairReport.fileKind(null, null));
    }
}
