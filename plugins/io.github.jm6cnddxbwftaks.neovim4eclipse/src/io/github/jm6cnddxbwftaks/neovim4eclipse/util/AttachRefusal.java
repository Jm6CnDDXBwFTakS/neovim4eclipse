// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Why <em>Neovim → Attach Neovim</em> cannot attach, said to the
 * user in a message. Without it, attaching to an image failed with an exception that Eclipse only
 * writes to its Error Log, and the key seemed to do nothing.
 *
 * <p>Example: an image {@code logo.png} is active, so {@code reason("logo.png", false)} returns
 * <em>Neovim works only in text editors, and "logo.png" is not one ...</em>.
 */
public final class AttachRefusal {

    private AttachRefusal() {
    }

    /**
     * @param title  the active editor's title, {@code null} if no editor is open
     * @param isText the editor is a text editor, or a multi-page editor showing its text page
     * @return why Neovim cannot attach, or {@code null} if it can
     */
    public static String reason(String title, boolean isText) {
        if (title == null) {
            return "No editor is open. Open a text file first.";
        }
        if (!isText) {
            return "Neovim works only in text editors, and \"" + title + "\" is not one (an image, or a"
                    + " form page such as a manifest's Overview). In an editor with several tabs, select"
                    + " its source tab first.";
        }
        return null;
    }
}
