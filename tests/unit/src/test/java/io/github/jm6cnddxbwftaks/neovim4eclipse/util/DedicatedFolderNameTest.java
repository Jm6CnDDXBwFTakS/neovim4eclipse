// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.RepositoryFiles;

/**
 * Keeps every written copy of the plug-in's own folders ({@link PluginFolders}) in step with the code.
 *
 * <p>The docs, the feature description, Javadoc and test comments spell the folder out, e.g.
 * {@code ~/.local/share/neovim4eclipse-plugin/neovim} or
 * {@code %LOCALAPPDATA%\neovim4eclipse-plugin\neovim}. If the folder is renamed or moved, this test
 * lists every file that still names the old one.
 */
class DedicatedFolderNameTest {

    /**
     * {@code .local/share/neovim4eclipse...}, {@code AppData\Local\neovim4eclipse...} or
     * {@code %LOCALAPPDATA%\neovim4eclipse...}, with either separator; the folder name is the group.
     */
    private static final Pattern MENTION = Pattern.compile(
            "(?:\\.local[/\\\\]share|AppData[/\\\\]Local|%LOCALAPPDATA%)[/\\\\](neovim4eclipse[A-Za-z0-9_.-]*)");
    /**
     * The folder's earlier homes: {@code ~/.local/opt}, before it followed the XDG data home, and on
     * Windows {@code %USERPROFILE%\.local\share} or {@code %HOME%\.local\share}, before it followed
     * Neovim's data home there.
     */
    private static final Pattern OLD = Pattern.compile("\\.local[/\\\\]opt[/\\\\]|%(?:USERPROFILE|HOME)%[/\\\\]\\.local");
    private static final Set<String> TEXT_SUFFIXES = Set.of(".java", ".md", ".xml", ".properties", ".yml",
            ".lua", ".txt", ".html");

    @Test
    void everyMentionNamesTheSameFolder() throws IOException {
        Path root = repoRoot();
        List<String> wrong = new ArrayList<>();
        int mentions = 0;
        for (Path file : textFiles(root)) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (MalformedInputException e) {
                continue; // not UTF-8 text after all
            }
            Matcher m = MENTION.matcher(text);
            while (m.find()) {
                mentions++;
                if (!m.group(1).equals(PluginFolders.NAME)) {
                    wrong.add(root.relativize(file) + ": " + m.group());
                }
            }
            Matcher old = OLD.matcher(text);
            while (old.find()) {
                wrong.add(root.relativize(file) + ": " + old.group());
            }
        }
        assertTrue(mentions > 0, "no mention of the folder found under " + root);
        assertEquals(List.of(), wrong, "these name another folder than " + PluginFolders.NAME);
    }

    /**
     * The repository's text files ({@link RepositoryFiles}: git's list, so a copy of the repository
     * inside the checkout, such as another tool's worktree, is not read), without this file, whose
     * comments quote the patterns.
     */
    private static List<Path> textFiles(Path root) throws IOException {
        Path self = root.resolve("tests/unit/src/test/java/io/github/jm6cnddxbwftaks/neovim4eclipse/util/"
                + DedicatedFolderNameTest.class.getSimpleName() + ".java");
        List<Path> files = new ArrayList<>();
        for (Path file : RepositoryFiles.paths(root)) {
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && TEXT_SUFFIXES.contains(name.substring(dot)) && !file.equals(self)) {
                files.add(file);
            }
        }
        return files;
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
