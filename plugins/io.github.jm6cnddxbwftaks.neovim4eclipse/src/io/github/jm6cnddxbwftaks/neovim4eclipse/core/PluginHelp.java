// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The plug-in's own Vim help file, so that {@code :help neovim4eclipse} finds something: a short
 * page that points to the README and the user guide, where the documentation lives. It repeats
 * none of it, so it cannot drift from it.
 *
 * <h2>How Neovim finds it</h2>
 * {@code :help} looks up a topic in the {@code doc/tags} file of each folder on
 * {@code 'runtimepath'} ({@code :help help-writing}, {@code :help :helptags}). The plug-in already
 * appends a folder of its own, {@code <plug-in folder>/runtime}, for {@code :checkhealth}
 * ({@link HealthCheck}); the help file and its tags go into that folder's {@code doc/}, written
 * again at every start ({@link #write}). The tags file is written here rather than by
 * {@code :helptags}: two fixed lines, no command to run in Neovim, and the help editor, which gets
 * the editing Neovim's {@code 'runtimepath'} ({@code HelpProfile}), finds the page too. A
 * {@code doc/} folder holds no script, so the runtimepath still gains nothing that runs by itself.
 *
 * <p>Example: {@code :help neovim4eclipse} in an editor opens this page in the help editor.
 */
public final class PluginHelp {

    /** The help file's place below the runtime folder. */
    static final String FILE_PATH = "doc/neovim4eclipse.txt";

    /** The tags file's place below the runtime folder, beside the help file. */
    static final String TAGS_PATH = "doc/tags";

    /**
     * The page. The first line is what {@code :help} lists under "LOCAL ADDITIONS" in
     * {@code help.txt}: the file's own tag and a one-line description. {@code *name*} marks a tag
     * (the target of {@code :help name}), {@code |name|} links to one. The last line is the
     * modeline every help file carries; the plug-in turns modelines off for files you edit
     * (SECURITY.md), and a help buffer gets its settings from the {@code help} filetype anyway.
     */
    static final String TEXT = """
            *neovim4eclipse.txt*  Neovim embedded in Eclipse's text editors

                                                                  *neovim4eclipse*
            Neovim for Eclipse runs a real Neovim inside Eclipse's text editors. Its
            documentation is on its project page:

            README, what it does and its settings:
              https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#readme

            User guide, how each part works, and where it differs from Neovim:
              https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse/blob/main/docs/USER_GUIDE.md

            To check the setup (which Neovim runs, and what is turned off for the files
            you open), run: >
              :checkhealth neovim4eclipse
            <
             vim:tw=78:ts=8:ft=help:norl:
            """;

    /**
     * The tags file: one line per tag, {@code <tag> TAB <file> TAB <search>}, sorted, as
     * {@code :helptags} writes it ({@code :help tags-file-format}); {@code :help} searches it in
     * that order.
     */
    static final String TAGS = """
            neovim4eclipse\tneovim4eclipse.txt\t/*neovim4eclipse*
            neovim4eclipse.txt\tneovim4eclipse.txt\t/*neovim4eclipse.txt*
            """;

    private PluginHelp() {
    }

    /** Writes {@link #TEXT} and {@link #TAGS} below {@code runtimeFolder}, replacing what was there. */
    static void write(Path runtimeFolder) throws IOException {
        Path file = runtimeFolder.resolve(FILE_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, TEXT, StandardCharsets.UTF_8);
        Files.writeString(runtimeFolder.resolve(TAGS_PATH), TAGS, StandardCharsets.UTF_8);
    }
}
