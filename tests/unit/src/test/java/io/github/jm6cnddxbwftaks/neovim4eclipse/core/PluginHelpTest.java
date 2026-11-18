// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plug-in's help page and its tags file. Neovim's own lookup is tested in
 * {@code NeovimSessionIntegrationTest.thePluginsOwnHelpPageIsFound}; here, that the two files
 * agree, without a Neovim.
 */
class PluginHelpTest {

    @Test
    void bothFilesAreWrittenAgainEachStart(@TempDir Path runtime) throws Exception {
        PluginHelp.write(runtime);
        Path file = runtime.resolve("doc").resolve("neovim4eclipse.txt");
        Files.writeString(file, "changed", StandardCharsets.UTF_8);
        PluginHelp.write(runtime);
        assertEquals(PluginHelp.TEXT, Files.readString(file, StandardCharsets.UTF_8), "replaced, not kept");
        assertEquals(PluginHelp.TAGS, Files.readString(runtime.resolve("doc").resolve("tags"), StandardCharsets.UTF_8));
    }

    /** Every {@code *tag*} in the page has a line in the tags file, and the file is sorted, as :help needs. */
    @Test
    void theTagsAreThePagesAndSorted() {
        List<String> inPage = new ArrayList<>();
        Matcher m = Pattern.compile("\\*([^*\\s|]+)\\*").matcher(PluginHelp.TEXT);
        while (m.find()) {
            inPage.add(m.group(1));
        }
        List<String> lines = PluginHelp.TAGS.lines().toList();
        List<String> inTags = lines.stream().map(l -> l.split("\t")[0]).toList();
        assertEquals(inPage.stream().sorted().toList(), inTags);
        assertEquals(lines.stream().sorted().toList(), lines, "sorted");
        for (String line : lines) {
            String[] parts = line.split("\t");
            assertEquals(3, parts.length, line);
            assertEquals("neovim4eclipse.txt", parts[1]);
            assertEquals("/*" + parts[0] + "*", parts[2]);
        }
    }

    @Test
    void thePageNamesItsOwnFileFirstAndLinksTheDocs() {
        assertTrue(PluginHelp.TEXT.startsWith("*neovim4eclipse.txt*"), "the LOCAL ADDITIONS line");
        assertTrue(PluginHelp.TEXT.contains("https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#readme"));
        assertTrue(PluginHelp.TEXT.contains("docs/USER_GUIDE.md"));
        assertTrue(PluginHelp.TEXT.lines().filter(l -> !l.contains("https://")).allMatch(l -> l.length() <= 78),
                "within 'textwidth' 78, but for a link, which cannot be wrapped");
    }
}
