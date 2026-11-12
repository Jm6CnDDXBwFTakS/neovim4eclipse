// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

/**
 * What a repair's report needs from the editor ({@link TextRepairs}): {@code sync} knows the
 * document and Neovim's buffer, not the editor showing them, the versions, or how to tell the user.
 * The editor gives the synchronizer one of these ({@link BufferSynchronizer#setRepairReporting});
 * tests and documents without an editor use {@link #NONE}.
 */
public interface RepairReporting {

    /** What the file is, without its name ({@code util/RepairReport.fileKind}). */
    String fileKind();

    /** The running Neovim's version, or {@code ""}. */
    String neovimVersion();

    /** This plug-in's version, or {@code ""}. */
    String pluginVersion();

    /**
     * A repair was reported as a warning. The editor tells the user, once per Neovim session, that a
     * change made just before may have gone to the wrong place. Called on the session's input thread.
     */
    void reported();

    /** No editor: a plain description, no versions, nobody to tell. */
    RepairReporting NONE = new RepairReporting() {
        @Override
        public String fileKind() {
            return "a document";
        }

        @Override
        public String neovimVersion() {
            return "";
        }

        @Override
        public String pluginVersion() {
            return "";
        }

        @Override
        public void reported() {
        }
    };
}
