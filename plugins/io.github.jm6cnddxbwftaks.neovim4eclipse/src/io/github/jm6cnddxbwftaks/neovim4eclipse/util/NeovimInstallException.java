// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * <i>Get Neovim</i> failed: the download, the check, unpacking, or the new Neovim's first run. The
 * message is written for the user and shown as it is in the failure dialog; the {@link Reason} lets
 * that dialog offer what helps (for a proxy that wants a password, the {@code eclipse.ini} line and
 * <i>Install from File…</i>).
 */
public final class NeovimInstallException extends NeovimExceptionBase {

    private static final long serialVersionUID = 1L;

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

    public NeovimInstallException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public NeovimInstallException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /** What went wrong. */
    public Reason reason() {
        return reason;
    }
}
