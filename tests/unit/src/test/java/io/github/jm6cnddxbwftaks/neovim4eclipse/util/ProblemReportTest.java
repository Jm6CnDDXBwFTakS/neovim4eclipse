// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.Test;

class ProblemReportTest {

    private static ProblemReport report(String neovim, String running, Map<String, String> init, List<String> warnings) {
        return new ProblemReport("0.2.16.v20261112", "4.41.0 (Eclipse IDE for Java Developers)", "21.0.5 (Eclipse Adoptium)",
                "Linux 6.12 aarch64, SWT gtk", neovim, running, false, false, init,
                List.of("statusStatusline = true", "flashOnError = true"), "a .java file in the Java Editor, Neovim mode on",
                warnings);
    }

    @Test
    void theReportListsEveryFactWithTheHomeFolderAsATilde() {
        String text = report("Found: dedicated Neovim 0.12.6, checked, /home/pat/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim",
                "0.12.6", Map.of("vim.g.neovim4eclipse_viewport", "0", "vim.g.neovim4eclipse_folds", "0"),
                List.of("14:03:12 WARNING TextRepairs: in /home/pat/ws/p")).text("/home/pat");
        assertEquals("""
                ### Neovim for Eclipse problem report

                - Neovim for Eclipse: 0.2.16.v20261112
                - Eclipse: 4.41.0 (Eclipse IDE for Java Developers)
                - Java: 21.0.5 (Eclipse Adoptium)
                - System: Linux 6.12 aarch64, SWT gtk
                - Neovim: Found: dedicated Neovim 0.12.6, checked, ~/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim
                - Running: Neovim 0.12.6
                - NVIM_APPNAME: not set
                - Errors in the config at start: none
                - init.lua settings: vim.g.neovim4eclipse_folds = 0, vim.g.neovim4eclipse_viewport = 0
                - Preferences changed: flashOnError = true, statusStatusline = true
                - Active editor: a .java file in the Java Editor, Neovim mode on

                Recent warnings (the last 20 of this run):
                ```text
                14:03:12 WARNING TextRepairs: in ~/ws/p
                ```
                """, text);
        assertFalse(text.contains("pat"), "the account's name is not in it");
    }

    @Test
    void withoutNeovimRunningItSaysSo() {
        String text = report("Found: no dedicated Neovim yet", "", null, List.of()).text("/home/pat");
        assertTrue(text.contains("- Running: no Neovim is running\n"), text);
        assertTrue(text.contains("- Errors in the config at start: (no Neovim running)\n"), text);
        assertTrue(text.contains("- init.lua settings: (Neovim could not be asked)\n"), text);
        assertTrue(text.contains("Recent warnings (the last 20 of this run):\nnone\n"), text);
        assertTrue(report("x", "0.12.6", Map.of(), List.of()).text("").contains("- init.lua settings: none set\n"));
    }

    @Test
    void theHomeFolderIsReplacedInEverySpellingButOnlyAsAWholeFolder() {
        assertEquals("~/a.txt and ~", ProblemReport.withoutHome("/home/pat/a.txt and /home/pat", "/home/pat"));
        assertEquals("/home/patricia/a.txt", ProblemReport.withoutHome("/home/patricia/a.txt", "/home/pat"), "another folder");
        assertEquals("~\\AppData\\x and ~/y and ~\\z", ProblemReport.withoutHome(
                "C:\\Users\\Pat\\AppData\\x and c:/users/pat/y and C:\\USERS\\PAT\\z", "C:\\Users\\Pat"), "Windows, any case");
        assertEquals("~/x", ProblemReport.withoutHome("/Users/pat/x", "/Users/pat/"), "a home with a final slash");
        assertEquals("(~)", ProblemReport.withoutHome("(/home/pat)", "/home/pat"));
        assertEquals("/a", ProblemReport.withoutHome("/a", "/"), "a home of / is left alone");
        assertEquals("/a", ProblemReport.withoutHome("/a", null));
    }

    @Test
    void recentWarningsKeepTheLastTwentyWarningsAndErrorsAsOneLineEach() {
        RecentWarnings recent = new RecentWarnings();
        recent.publish(new LogRecord(Level.INFO, "not kept"));
        for (int i = 1; i <= RecentWarnings.CAPACITY + 2; i++) {
            LogRecord r = new LogRecord(i % 2 == 0 ? Level.SEVERE : Level.WARNING, "line {0}\nwrapped");
            r.setParameters(new Object[] {i});
            r.setLoggerName("io.github.jm6cnddxbwftaks.neovim4eclipse.sync.TextRepairs");
            if (i == RecentWarnings.CAPACITY + 2) {
                r.setThrown(new IllegalStateException("broken\npipe"));
            }
            recent.publish(r);
        }
        List<String> lines = recent.lines();
        assertEquals(RecentWarnings.CAPACITY, lines.size());
        assertTrue(lines.get(0).matches("\\d\\d:\\d\\d:\\d\\d WARNING TextRepairs: line 3 wrapped"), lines.get(0));
        assertTrue(lines.get(lines.size() - 1).endsWith("SEVERE TextRepairs: line 22 wrapped (IllegalStateException: broken pipe)"),
                lines.get(lines.size() - 1));
    }
}
