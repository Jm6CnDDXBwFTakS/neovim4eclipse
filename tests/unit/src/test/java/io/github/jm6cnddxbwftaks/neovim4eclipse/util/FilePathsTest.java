// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one rule for comparing file paths ({@link FilePaths}): every case of the table Neovim's rule
 * passes too ({@code src/test/resources/path-cases.tsv}, {@code paths_spec.lua}), on the Linux,
 * macOS and Windows rules with the table's fake links, so this runs the Windows and macOS rules on
 * any machine. Then this machine's rule on real files.
 */
class FilePathsTest {

    /** The table's fake links (see its header), by platform. */
    private static final Map<String, Map<String, String>> LINKS = Map.of(
            "linux", Map.of("/lnk", "/ws"),
            "macos", Map.of("/var", "/private/var"),
            "windows", Map.of("C:/link", "C:/ws", "C:/PROGRA~1", "C:/Program Files"));

    /** The table's fake realpath: the longest matching link replaced; nothing for a last name starting with "new". */
    private static UnaryOperator<String> fakeRealPath(String platform) {
        return path -> {
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.startsWith("new")) {
                return null;
            }
            String best = null;
            for (String from : LINKS.get(platform).keySet()) {
                if ((path.equals(from) || path.startsWith(from + "/")) && (best == null || from.length() > best.length())) {
                    best = from;
                }
            }
            return best == null ? path : LINKS.get(platform).get(best) + path.substring(best.length());
        };
    }

    private static FilePaths.Platform platform(String name) {
        return new FilePaths.Platform(name.equals("windows"), !name.equals("linux"), fakeRealPath(name));
    }

    private record Case(String op, String platform, String a, String b, boolean yes, String why) {
    }

    private static List<Case> cases() throws IOException {
        List<Case> list = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("src/test/resources/path-cases.tsv"), StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            list.add(new Case(f[0], f[1], f[2], f[3], f[4].equals("yes"), f[5]));
        }
        return list;
    }

    @Test
    void everyCaseOfTheSharedTableOnItsPlatformsRule() throws IOException {
        List<Case> list = cases();
        assertTrue(list.size() >= 20, "the table was read: " + list.size() + " cases");
        for (Case c : list) {
            FilePaths.Platform p = platform(c.platform());
            boolean got = c.op().equals("same") ? FilePaths.same(c.a(), c.b(), p) : FilePaths.inside(c.a(), c.b(), p);
            assertEquals(c.yes(), got, c.op() + " " + c.platform() + ": " + c.a() + " / " + c.b() + " (" + c.why()
                    + "); canonical " + FilePaths.canonical(c.a(), p) + " and " + FilePaths.canonical(c.b(), p));
        }
    }

    @Test
    void theFormIsNeovims() {
        // As vim.fs.normalize gives it (checked in Neovim 0.12.6, 2026-10-29), so the two rules agree.
        FilePaths.Platform windows = platform("windows");
        assertEquals("c:/ws/a.txt", FilePaths.canonical("C:\\ws\\x\\..\\A.txt", windows), "case folded, .. resolved");
        assertEquals("C:/WS/a.txt", FilePaths.normalize("c:/WS/a.txt/", true), "the drive letter upper-cased first");
        assertEquals("//srv/share/a", FilePaths.normalize("//srv/share/a", true), "a network share keeps its //");
        assertEquals("/", FilePaths.normalize("/", false));
        assertEquals("C:/", FilePaths.normalize("C:/", true));
        assertEquals("/ws/a\\b.txt", FilePaths.normalize("/ws/a\\b.txt", false), "\\ is a name character on Linux");
        assertEquals("", FilePaths.canonical("", windows));
        assertEquals("", FilePaths.canonical(null, windows));
    }

    @Test
    void theWrittenPartBelowAFolderKeepsItsCase() {
        FilePaths.Platform macos = platform("macos");
        assertEquals("src/A.java", FilePaths.below("/ws/app/src/A.java", "/ws/App", macos), "case ignored for the folder");
        assertEquals("folders/a.txt", FilePaths.below("/var/folders/a.txt", "/private/var", macos), "through /var");
        assertNull(FilePaths.below("/ws/app", "/ws/app", macos), "the folder itself");
        assertNull(FilePaths.below("/ws/app2/a", "/ws/app", macos));
        assertNull(FilePaths.below("/ws/App/a", "/ws/app", platform("linux")), "Linux keeps case");
    }

    @Test
    void thisMachinesRuleOnRealFiles(@TempDir Path dir) throws IOException {
        Path real = Files.createDirectories(dir.resolve("real"));
        Files.writeString(real.resolve("a.txt"), "x");
        assertTrue(FilePaths.same(real.resolve("a.txt"), real.resolve("../real/./a.txt")));
        assertTrue(FilePaths.same(real.resolve("new.txt"), dir.resolve("real/new.txt")), "not there yet: its folder resolved");
        assertFalse(FilePaths.same(real.resolve("a.txt"), real.resolve("b.txt")));
        Path link = dir.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException e) {
            return; // no right to make one here (a Windows runner)
        }
        assertTrue(FilePaths.same(link.resolve("a.txt"), real.resolve("a.txt")), "through a link");
    }
}
