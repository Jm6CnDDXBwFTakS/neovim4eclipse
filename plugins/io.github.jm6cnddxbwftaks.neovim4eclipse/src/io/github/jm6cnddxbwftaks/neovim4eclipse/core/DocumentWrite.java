// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.List;

/**
 * A write of a document buffer, asked by Neovim ({@code :w}, {@code :update}, {@code :bufdo update},
 * {@code :w other.txt}; the {@code eclipse_write} request of {@link LuaScripts#DOCUMENT_BUFFER}), and
 * Eclipse's answer.
 *
 * <p>The request ({@link Request}) says what to write where: the document to its own file (a save),
 * or a copy of it to another file ({@code :w other.txt}, which leaves the editor on its own file, as
 * in Vim). It carries {@code v:cmdarg}, the {@code ++opt} arguments of the write ({@code " ++p"} for
 * {@code :w ++p}). The answer is either an error, which fails the write as a real failed write does,
 * or the message Neovim shows, as Vim's own after a write: {@code "a.txt" 12L, 140B written}. An
 * empty message shows nothing (a new file's editor shows its own {@code [New]} message).
 */
public final class DocumentWrite {

    private DocumentWrite() {
    }

    /** What a write does; the Lua decides it (see {@link LuaScripts#DOCUMENT_BUFFER}). */
    public enum Kind {
        /** The document to its own file: Eclipse saves the editor ({@code :w}, {@code :update}). */
        OWN,
        /** The whole document to another file; the editor stays as it is ({@code :w other.txt}). */
        COPY;

        /** The kind named {@code name} in the request ({@code "copy"}); anything else is {@link #OWN}. */
        static Kind named(String name) {
            return "copy".equals(name) ? COPY : OWN;
        }
    }

    /**
     * One write Neovim asks for.
     *
     * @param kind          what to write where
     * @param target        the file written, a full path as Neovim names it ({@code /ws/p/other.txt});
     *                      for {@link Kind#OWN} the buffer's own name
     * @param createFolders {@code ++p}: a new file's missing folders may be created
     */
    public record Request(Kind kind, String target, boolean createFolders) {
        public Request {
            target = target == null ? "" : target;
        }

        /** A save of the document to its own file, as {@code :w} (with {@code ++p} when asked). */
        public static Request own(boolean createFolders) {
            return new Request(Kind.OWN, "", createFolders);
        }

        /**
         * The request from {@code eclipse_write}'s arguments after the buffer number:
         * {@code [cmdarg, kind, target]}, the last two left out by a plain save. Example:
         * {@code [" ++p", "copy", "/ws/p/other.txt"]}.
         */
        static Request of(String cmdarg, String kind, String target) {
            return new Request(Kind.named(kind), target, createsFolders(cmdarg));
        }
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
         * @param buffer  the document's buffer
         * @param request what to write where
         */
        Result save(DocumentBuffer buffer, Request request);
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
