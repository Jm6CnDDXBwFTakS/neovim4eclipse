// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;

/**
 * A write of a document buffer, asked by Neovim ({@code :w}, {@code :update}, {@code :bufdo update};
 * the {@code eclipse_write} request of {@link LuaScripts#DOCUMENT_BUFFER}), and Eclipse's answer.
 *
 * <p>The request carries {@code v:cmdarg}, the {@code ++opt} arguments of the write
 * ({@code " ++p"} for {@code :w ++p}). The answer is either an error, which fails the write as a
 * real failed write does, or the message Neovim shows, as Vim's own after a write:
 * {@code "a.txt" 12L, 140B written}. An empty message shows nothing (a new file's editor shows its
 * own {@code [New]} message).
 */
public final class DocumentWrite {

    private DocumentWrite() {
    }

    /** What Eclipse answers Neovim. */
    public record Result(String error, String message) {
        public Result {
            error = error == null ? "" : error;
            message = message == null ? "" : message;
        }

        /** Saved; {@code message} is shown, as Vim shows {@code "a.txt" 3L, 12B written}. */
        public static Result saved(String message) {
            return new Result("", message);
        }

        /** Not saved; Neovim fails the write with {@code error}. */
        public static Result failed(String error) {
            return new Result(error, "");
        }

        public boolean ok() {
            return error.isEmpty();
        }

        /** As sent to Neovim: {@code [error, message]}, each "" when there is none. */
        List<String> toRpc() {
            return List.of(error, message);
        }
    }

    /** Saves the document of a buffer Neovim writes. Called on the RPC thread while Neovim waits. */
    @FunctionalInterface
    public interface Handler {
        /**
         * @param buffer        the document's buffer
         * @param createFolders {@code :w ++p}: a new file's missing folders may be created
         */
        Result save(DocumentBuffer buffer, boolean createFolders);
    }

    /**
     * Whether a write's {@code v:cmdarg} has {@code ++p} ({@code :help ++p}). Example:
     * {@code " ++p"} yes, {@code " ++enc=latin1"} no.
     */
    static boolean createsFolders(String cmdarg) {
        if (cmdarg == null) {
            return false;
        }
        for (String word : cmdarg.strip().split("\\s+")) {
            if (word.equals("++p")) {
                return true;
            }
        }
        return false;
    }
}
