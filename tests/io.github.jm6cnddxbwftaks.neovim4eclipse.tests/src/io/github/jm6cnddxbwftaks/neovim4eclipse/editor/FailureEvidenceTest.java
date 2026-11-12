// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * {@link FailureEvidence}, in the running test Eclipse: what a wait that ran out leaves behind,
 * and that without the folder's property nothing is written.
 */
class FailureEvidenceTest {

    @Test
    void aReportNamesTheWaitAndHoldsTheWindowsTheThreadsAndEclipsesLog() throws Exception {
        // A folder of this test's own under target/ (test.isolated.root): JUnit's @TempDir is not
        // among the packages the fragment imports.
        Path folder = Path.of(System.getProperty("test.isolated.root", "target"), "failure-evidence-test");
        deleteFolder(folder);
        String before = System.getProperty(FailureEvidence.DIR_PROPERTY);
        int reports = FailureEvidence.reportCount();
        FailureEvidence.setReportCount(0); // a run that already wrote its last report still writes this one
        System.setProperty(FailureEvidence.DIR_PROPERTY, folder.toString());
        try {
            String message = FailureEvidence.record("the : box to open");
            assertTrue(message.contains("evidence: "), message);
            Path report;
            try (var files = Files.list(folder)) {
                report = files.filter(p -> p.toString().endsWith("-the-box-to-open.txt")).findFirst().orElseThrow();
            }
            String text = Files.readString(report, StandardCharsets.UTF_8);
            assertTrue(text.startsWith("Timed out waiting for: the : box to open\n"), text);
            assertTrue(text.contains("Windows (shells)"), "the windows");
            assertTrue(text.contains("\nFocus: "), "the focus");
            assertTrue(text.contains("Threads:\n") && text.contains("\" id="), "the threads, each with its stack");
            assertTrue(text.contains("End of Eclipse's log"), "the log");
            // A picture of the workbench window, unless the screen cannot be read here (then the text says why).
            Path picture = Path.of(report.toString().replaceAll("\\.txt$", "-1.png"));
            assertTrue(Files.isRegularFile(picture) || text.contains("': none (") && !text.contains("-1.png"), text);
        } finally {
            if (before == null) {
                System.clearProperty(FailureEvidence.DIR_PROPERTY);
            } else {
                System.setProperty(FailureEvidence.DIR_PROPERTY, before);
            }
            FailureEvidence.setReportCount(reports); // this report takes none of the run's slots
            deleteFolder(folder);
        }
    }

    private static void deleteFolder(Path folder) throws IOException {
        if (Files.isDirectory(folder)) {
            try (var files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
            Files.delete(folder);
        }
    }

    @Test
    void withoutTheFolderNothingIsWritten() {
        String before = System.getProperty(FailureEvidence.DIR_PROPERTY);
        System.clearProperty(FailureEvidence.DIR_PROPERTY);
        try {
            assertEquals("", FailureEvidence.record("anything"));
        } finally {
            if (before != null) {
                System.setProperty(FailureEvidence.DIR_PROPERTY, before);
            }
        }
    }

    @Test
    void aWaitBecomesAShortFileName() {
        assertEquals("the-box-to-open", FailureEvidence.slug("the : box to open"));
        assertEquals("the-neovim-menu-is-shown-by-default", FailureEvidence.slug("the Neovim menu is shown by default"));
        assertEquals("wait", FailureEvidence.slug(": ..."));
        assertEquals(60, FailureEvidence.slug("a".repeat(80)).length());
    }
}
