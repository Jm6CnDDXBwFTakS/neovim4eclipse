// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

/**
 * A developer tool stopped on purpose: a check failed, a command failed, or a question could not be
 * asked. The message says what to do next, for example
 * {@code "the working tree has changes; commit or stash them first"}. A tool's {@code main} prints
 * it on one line and exits with status 1; a stack trace would only hide it.
 *
 * <p>Not one of the plug-in's exceptions ({@code util/NeovimExceptionBase}): these tools are not part
 * of the plug-in and never run inside Eclipse.
 */
public final class ToolFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ToolFailure(String message) {
        super(message);
    }
}
