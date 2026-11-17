// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.RepositoryFiles;

/**
 * Every source file starts with the two-line SPDX header (CONTRIBUTING.md, "Style"), in the comment
 * syntax of its language:
 * <pre>
 *   // SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
 *   // SPDX-License-Identifier: EPL-2.0
 * </pre>
 * SPDX (spdx.dev) is a standard way to say a file's license in the file itself, so a file copied out
 * of the repository still says under which terms.
 *
 * <h2>Every file in a known group</h2>
 * Each file git lists ({@link RepositoryFiles#listedByGit}) must belong to one of two groups, by its extension
 * or its path: {@link #NEED_HEADER} or {@link #NEED_HEADER_FILES} (checked), or {@link #EXEMPT_KINDS} / {@link #EXEMPT_FILES}
 * (not checked: no comment syntax, a third party's text, data a program reads whole, or Markdown,
 * which the project leaves without one). A file in neither group fails the test. Why: a list of
 * kinds to check alone would let the first file of a new kind (a Python script, say) slip through
 * unchecked; with this rule its author decides once, by adding one line here.
 *
 * <p>Only git's list says which files belong to the repository: without git (an unpacked ZIP) the
 * test is skipped, since a walk of the folders also finds what git ignores (the copied
 * {@code lib/*.jar}, Eclipse's {@code .project}, a {@code local.properties}).
 */
class SpdxHeaderTest {

    /** The extensions of files that need the header (after the last dot, case as written). */
    private static final Set<String> NEED_HEADER = Set.of("java", "lua", "xml", "sh", "yml", "css", "html", "ini",
            "svg", "properties", "txt", "example");

    /**
     * Files with no extension that need the header: Git's and the editors' settings, which take
     * {@code #} comments. Paths relative to the root, with {@code /}.
     */
    private static final Set<String> NEED_HEADER_FILES = Set.of(".editorconfig", ".gitattributes", ".gitignore");

    /**
     * Kinds that have no header, and why: Markdown by the project's rule; pictures but SVG (binary);
     * {@code MANIFEST.MF} (OSGi manifests have no comments); armored keys and checksum lists, which
     * tools read whole; {@code path-cases.tsv}, the table both path rules read line by line.
     */
    private static final Set<String> EXEMPT_KINDS = Set.of("md", "png", "gif", "MF", "asc", "sha256", "tsv");

    /**
     * Single files without the header, and why: the Maven wrapper's own files ({@code mvnw},
     * {@code mvnw.cmd}, its {@code .properties}: written by the Maven wrapper plug-in and replaced
     * whole by {@code MavenUpgrade}, which checks them against the release); {@code LICENSE} and the
     * third-party license and notice texts (others' words, kept as published); {@code .java-version}
     * (one version number that version managers read whole); {@code .mvn/maven.config} (Maven reads
     * every line of it as command-line arguments). Paths relative to the root, with {@code /}.
     */
    private static final Set<String> EXEMPT_FILES = Set.of(
            "mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties", ".mvn/maven.config", ".java-version",
            "LICENSE", "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/licenses/Apache-2.0.txt",
            "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/licenses/msgpack-java-NOTICE.txt");

    /**
     * How many lines may come before the header: one opening line, such as a shebang
     * ({@code #!/usr/bin/env bash}), the XML declaration, {@code <!DOCTYPE html>}, an SVG's opening
     * tag or a CSS comment's {@code /*}.
     */
    private static final int MOST_LINES_BEFORE = 1;

    private static final Pattern COPYRIGHT = Pattern.compile("SPDX-FileCopyrightText: 20\\d\\d Jm6CnDDXBwFTakS\\b");
    /** The license line: exactly EPL-2.0, then at most a comment's end ({@code -->}, {@code * /}), not {@code EPL-2.0 OR MIT}. */
    private static final Pattern LICENSE = Pattern.compile("SPDX-License-Identifier: EPL-2\\.0\\s*(-->|\\*/)?\\s*$");

    private static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("AGENTS.md"))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "no AGENTS.md above the working folder");
        return dir;
    }

    /** The part after the last dot of the file's name, or {@code ""} for {@code mvnw} and {@code .gitignore}. */
    static String kind(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1);
    }

    /**
     * Whether {@code lines} start with the header: the copyright line within the first
     * {@link #MOST_LINES_BEFORE} + 1 lines, and the license line right after it.
     */
    static boolean hasHeader(List<String> lines) {
        for (int i = 0; i <= MOST_LINES_BEFORE && i + 1 < lines.size(); i++) {
            if (COPYRIGHT.matcher(lines.get(i)).find()) {
                return LICENSE.matcher(lines.get(i + 1)).find();
            }
        }
        return false;
    }

    @Test
    void everySourceFileStartsWithTheHeader() throws IOException {
        Path root = root();
        List<String> missing = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        Optional<List<String>> files = RepositoryFiles.listedByGit(root);
        assumeTrue(files.isPresent(), "no git list of the repository's files (no git, or no .git here)");
        for (String file : files.get()) {
            String kind = kind(file);
            if (EXEMPT_FILES.contains(file) || EXEMPT_KINDS.contains(kind)) {
                continue;
            }
            if (!NEED_HEADER.contains(kind) && !NEED_HEADER_FILES.contains(file)) {
                unknown.add(file);
                continue;
            }
            Path path = root.resolve(file);
            if (!Files.isRegularFile(path)) {
                continue; // deleted but not committed: RepositoryFiles drops those, a link to a folder is no source
            }
            List<String> head;
            try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
                head = lines.limit(MOST_LINES_BEFORE + 2L).toList();
            } catch (UncheckedIOException e) {
                missing.add(file + " (not UTF-8 text in its first lines)");
                continue;
            }
            if (!hasHeader(head)) {
                missing.add(file);
            }
        }
        assertEquals(List.of(), unknown, "a kind of file this test does not know: add its extension to NEED_HEADER"
                + " (it holds source) or EXEMPT_KINDS, or the file to EXEMPT_FILES, with the reason");
        assertEquals(List.of(), missing, "files without the two-line SPDX header near the top (see CONTRIBUTING.md, \"Style\")");
    }

    @Test
    void theHeaderMayFollowOneOpeningLineOnly() {
        assertTrue(hasHeader(List.of("// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS", "// SPDX-License-Identifier: EPL-2.0")));
        assertTrue(hasHeader(List.of("#!/usr/bin/env bash", "# SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS",
                "# SPDX-License-Identifier: EPL-2.0")));
        assertTrue(hasHeader(List.of("<?xml version=\"1.0\"?>", "<!-- SPDX-FileCopyrightText: 2027 Jm6CnDDXBwFTakS -->",
                "<!-- SPDX-License-Identifier: EPL-2.0 -->")), "a later year");
        assertFalse(hasHeader(List.of("package x;", "import y;", "// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS",
                "// SPDX-License-Identifier: EPL-2.0")), "two lines before it: too far down");
        assertFalse(hasHeader(List.of("// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS", "// other")), "the license line missing");
        assertFalse(hasHeader(List.of("// SPDX-FileCopyrightText: 2026 Someone Else", "// SPDX-License-Identifier: EPL-2.0")));
        assertFalse(hasHeader(List.of("// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS", "// SPDX-License-Identifier: MIT")));
        assertFalse(hasHeader(List.of("// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS", "// SPDX-License-Identifier: EPL-2.0 OR MIT")),
                "another license besides");
        assertTrue(hasHeader(List.of(" * SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS", " * SPDX-License-Identifier: EPL-2.0")));
        assertFalse(hasHeader(List.of()));
    }

    @Test
    void aKindIsTheLastExtension() {
        assertEquals("java", kind("a/b/C.java"));
        assertEquals("MF", kind("META-INF/MANIFEST.MF"));
        assertEquals("example", kind("releng/tools/common/local.properties.example"));
        assertEquals("", kind("mvnw"));
        assertEquals("", kind(".gitignore"));
        assertEquals("", kind("a.b/mvnw"), "a dot in a folder's name is no extension");
    }
}
