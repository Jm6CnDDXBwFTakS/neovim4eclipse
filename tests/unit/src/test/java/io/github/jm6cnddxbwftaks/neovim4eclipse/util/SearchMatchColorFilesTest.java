// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The search match color's two defaults are written three times: in {@code plugin.xml} (the color
 * definition, the light themes' value), in the dark theme's stylesheet
 * ({@code css/e4-dark_neovim4eclipse.css}), and in {@link OverlayColors}, which picks between them
 * by the editor's background. If the files and the constants disagree, the pick stops working
 * (a value that is neither default counts as the user's own); if the stylesheet's selector does not
 * name the color definition, Eclipse's dark theme silently keeps the light amber. Eclipse reports
 * neither, so this test reads the files.
 *
 * <p>Example: the id {@code io.github.jm6cnddxbwftaks.neovim4eclipse.searchMatch} is the selector
 * {@code ColorDefinition#io-github-jm6cnddxbwftaks-neovim4eclipse-searchMatch} (CSS ids cannot
 * hold dots, so Eclipse writes them as dashes).
 */
class SearchMatchColorFilesTest {

    private static final String PLUGIN = "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/";

    private static final String COLOR_ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.searchMatch";

    @Test
    void pluginXmlDeclaresTheLightDefault() throws IOException {
        String xml = Files.readString(repoRoot().resolve(PLUGIN + "plugin.xml"));
        Matcher definition = Pattern.compile("<colorDefinition\\s[^>]*id=\"" + Pattern.quote(COLOR_ID) + "\"[^>]*>")
                .matcher(xml);
        assertTrue(definition.find(), "plugin.xml has no colorDefinition " + COLOR_ID);
        Matcher value = Pattern.compile("value=\"(\\d+),(\\d+),(\\d+)\"").matcher(definition.group());
        assertTrue(value.find(), "the colorDefinition has no value=\"r,g,b\"");
        int rgb = (Integer.parseInt(value.group(1)) << 16) | (Integer.parseInt(value.group(2)) << 8)
                | Integer.parseInt(value.group(3));
        assertEquals(hex(OverlayColors.SEARCH_MATCH_LIGHT), hex(rgb), "plugin.xml's value and OverlayColors.SEARCH_MATCH_LIGHT");
    }

    @Test
    void theDarkStylesheetNamesTheColorWithTheDarkDefault() throws IOException {
        String css = Files.readString(repoRoot().resolve(PLUGIN + "css/e4-dark_neovim4eclipse.css"));
        String selector = "ColorDefinition#" + COLOR_ID.replace('.', '-');
        Matcher rule = Pattern.compile(Pattern.quote(selector) + "\\s*\\{\\s*color:\\s*#([0-9A-Fa-f]{6})\\s*;").matcher(css);
        assertTrue(rule.find(), "the stylesheet has no rule " + selector + " { color: #RRGGBB; }");
        assertEquals(hex(OverlayColors.SEARCH_MATCH_DARK), "0x" + rule.group(1).toUpperCase(),
                "the stylesheet's color and OverlayColors.SEARCH_MATCH_DARK");
    }

    private static String hex(int rgb) {
        return String.format("0x%06X", rgb);
    }

    /** The repository root: the first folder above the working directory that holds {@code AGENTS.md}. */
    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("AGENTS.md"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("AGENTS.md not found above " + System.getProperty("user.dir"));
        }
        return dir;
    }
}
