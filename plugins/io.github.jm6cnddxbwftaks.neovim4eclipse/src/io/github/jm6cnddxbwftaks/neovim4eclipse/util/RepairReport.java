// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * The Error Log's report of a repair: Neovim's copy of a document differed from Eclipse's in a line
 * no recorded change explains, and a push wrote Eclipse's lines into Neovim's buffer
 * ({@code sync/TextRepairs}).
 *
 * <h2>What it says, and what it leaves out</h2>
 * Enough to reproduce and fix the cause: where the copies differed (line numbers, line counts),
 * when the repair ran ({@code at Esc}, {@code after a save}), what kind of file it was, and the
 * versions. Nothing of the file itself: no text, and not even its name or folder, since a log is
 * meant to be shared in an issue, and a folder holds the account's name. Example:
 *
 * <pre>
 * Neovim's copy of a .java file in the Java Editor differed from Eclipse's, and Eclipse's lines were
 * written into Neovim's: Neovim's line 12 (of 40) differed from Eclipse's line 12 (of 41), and no
 * change explains it. Found at Esc. Neovim 0.12.6, Neovim for Eclipse 0.2.16. Please report it,
 * with this message (it holds no text of the file): https://…/issues
 * </pre>
 *
 * @param fileKind      what the file is, from {@link #fileKind} ({@code "a .java file in the Java Editor"})
 * @param when          when the push ran, as a phrase ({@code "at Esc"}, {@code "after a save"})
 * @param neovimLine    the first differing line in Neovim's copy, 0-based
 * @param documentLine  the same line in Eclipse's copy, 0-based
 * @param neovimLines   how many lines Neovim's copy had
 * @param documentLines how many lines Eclipse's copy has
 * @param neovimVersion the running Neovim's version, or {@code ""} if unknown
 * @param pluginVersion this plug-in's version, or {@code ""} if unknown
 */
public record RepairReport(String fileKind, String when, int neovimLine, int documentLine, int neovimLines,
        int documentLines, String neovimVersion, String pluginVersion) {

    /** Where users report a problem. */
    public static final String ISSUES_URL = "https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse/issues";

    /** The Error Log's message. */
    public String message() {
        StringBuilder m = new StringBuilder("Neovim's copy of ").append(fileKind)
                .append(" differed from Eclipse's, and Eclipse's lines were written into Neovim's: Neovim's line ")
                .append(neovimLine + 1).append(" (of ").append(neovimLines).append(") differed from Eclipse's line ")
                .append(documentLine + 1).append(" (of ").append(documentLines).append("), and no change explains it.")
                .append(" Found ").append(when).append('.');
        String versions = versions();
        if (!versions.isEmpty()) {
            m.append(' ').append(versions).append('.');
        }
        return m.append(" Please report it, with this message (it holds no text of the file): ").append(ISSUES_URL)
                .toString();
    }

    private String versions() {
        String neovim = neovimVersion.isBlank() ? "" : "Neovim " + neovimVersion;
        String plugin = pluginVersion.isBlank() ? "" : "Neovim for Eclipse " + pluginVersion;
        return neovim.isEmpty() || plugin.isEmpty() ? neovim + plugin : neovim + ", " + plugin;
    }

    /**
     * What a file is, without its name: its extension and the editor showing it. Examples:
     * {@code ("Main.java", "Java Editor")} is {@code "a .java file in the Java Editor"},
     * {@code ("Makefile", "Text Editor")} {@code "a file without extension in the Text Editor"},
     * and with no editor name {@code "a .java file"}.
     */
    public static String fileKind(String fileName, String editorName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        String kind = dot > 0 && dot < name.length() - 1 ? "a " + name.substring(dot) + " file" : "a file without extension";
        return editorName == null || editorName.isBlank() ? kind : kind + " in the " + editorName;
    }
}
