// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.io.Serializable;
import java.util.Optional;

/**
 * <i>Get Neovim</i> failed: the download, the check, unpacking, or the new Neovim's first run. The
 * message is written for the user and shown under <i>Why</i> in the failure dialog
 * ({@link GetNeovimText#failed}); the {@link Reason} lets that dialog offer what helps (for a proxy
 * that wants a password, the {@code eclipse.ini} line in a field with <em>Copy</em>, and
 * <i>Install from File…</i>).
 */
public final class NeovimInstallException extends NeovimExceptionBase {

    private static final long serialVersionUID = 1L;

    /**
     * The file a {@link Reason#WRONG_ARCHIVE} check refused, so the failure dialog can set its size and
     * SHA-256 beside the expected ones (each hash in a field of its own, rather than wrapped in a sentence).
     *
     * @param fileName the file's name, for example {@code nvim-linux-arm64(1).tar.gz}
     * @param size     its size in bytes
     * @param sha256   its SHA-256, 64 lower-case hex digits
     */
    public record Found(String fileName, long size, String sha256) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    /** What went wrong, for the failure dialog's choices. */
    public enum Reason {
        /** GitHub could not be reached, or answered with an error. */
        NETWORK,
        /** The proxy wants a password that Java will not send through an HTTPS tunnel (HTTP 407). */
        PROXY_PASSWORD,
        /** The archive is not the pinned one: another version, damaged, or changed on the way. */
        WRONG_ARCHIVE,
        /** The archive is the pinned one for another platform. */
        WRONG_PLATFORM,
        /** The unpacked Neovim did not run here, or reported another version. */
        DOES_NOT_RUN,
        /** Another Eclipse is installing into the same folder. */
        BUSY,
        /** The user cancelled. */
        CANCELLED,
        /** A file could not be read or written. */
        FILES
    }

    private final Reason reason;
    private final Found found;

    public NeovimInstallException(Reason reason, String message) {
        super(message);
        this.reason = reason;
        this.found = null;
    }

    /** A {@link Reason#WRONG_ARCHIVE} for a file that is not the pinned archive. */
    public NeovimInstallException(String message, Found found) {
        super(message);
        this.reason = Reason.WRONG_ARCHIVE;
        this.found = found;
    }

    public NeovimInstallException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.found = null;
    }

    /** What went wrong. */
    public Reason reason() {
        return reason;
    }

    /** The refused file's size and hash, for a wrong archive the check read whole. */
    public Optional<Found> found() {
        return Optional.ofNullable(found);
    }
}
