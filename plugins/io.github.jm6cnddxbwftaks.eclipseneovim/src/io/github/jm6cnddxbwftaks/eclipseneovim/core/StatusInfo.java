// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

/**
 * What Neovim currently reports for the status bar besides the mode.
 *
 * @param statusline Neovim's evaluated {@code 'statusline'}, or "" if not requested
 * @param cmd        the pending command ({@code showcmd}), e.g. {@code 2d}
 * @param message    the latest message or error, kept until the next key
 */
public record StatusInfo(String statusline, String cmd, String message) {

    public static final StatusInfo EMPTY = new StatusInfo("", "", "");
}
