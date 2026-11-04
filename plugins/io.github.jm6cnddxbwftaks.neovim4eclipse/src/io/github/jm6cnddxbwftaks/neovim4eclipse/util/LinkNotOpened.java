// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.net.URI;
import java.util.List;

/**
 * The words of the dialog {@code prefs/ExternalBrowser} shows when Eclipse cannot open a link, as a
 * {@link HeadedMessage} for {@code dialogs/HeadedMessageDialog}. Kept apart from the dialog so the
 * unit tests can read them.
 *
 * <h2>Why a dialog</h2>
 * The plug-in's links (Get Neovim's GitHub, VirusTotal and download links, the welcome's, the
 * "Neovim not started" notice's) open in the browser chosen in Eclipse's Web Browser preferences.
 * When that browser cannot start, the click used to do nothing visible: the failure went only to
 * Java's log, which Eclipse's Error Log does not show. Now the link is shown to copy, with Eclipse's
 * reason. Example, a browser added on macOS by its app folder (seen 2026-11-04). Eclipse starts the
 * location as a program, and an {@code .app} is a folder: Safari's, which <i>Browse…</i> gives as
 * {@code /System/Volumes/Preboot/Cryptexes/App/System/Applications/Safari.app} (macOS keeps Safari
 * there), fails as {@code /Applications/Safari.app} does. {@code /usr/bin/open -a Safari %URL%}
 * asks macOS to open the link in it:
 *
 * <pre>
 *   Eclipse could not open its web browser for this link.
 *
 *   Link     Copy it into a browser.
 *            [https://www.virustotal.com/gui/file/2e58d259...] (Copy)
 *   Reason   Could not launch external web browser for /Applications/Safari.app. Check the Web
 *            Browser preferences.
 *   Fix      Choose a browser in Window &gt; Preferences &gt; General &gt; Web Browser. ...
 * </pre>
 *
 * <p>Not covered: a browser that starts but ignores the link. Eclipse cannot tell that from a link
 * that opened, so nothing is shown then.
 */
public final class LinkNotOpened {

    public static final String TITLE = "Link Not Opened";

    /** The buttons, in order: the Web Browser preference page, then Close (the default). */
    public static final List<String> BUTTONS = List.of("Web Browser Preferences", "Close");

    private LinkNotOpened() {
    }

    /**
     * The dialog's message.
     *
     * @param url    the link that did not open
     * @param reason Eclipse's message, or null or blank for none
     * @param mac    whether Eclipse runs on macOS, where a browser added by its {@code .app} folder
     *               cannot open links and {@code /usr/bin/open} can
     */
    public static HeadedMessage message(URI url, String reason, boolean mac) {
        String fix = "Choose a browser in Window > Preferences > General > Web Browser. \"Default system web "
                + "browser\" uses the one your system opens links with."
                + (mac ? " A browser added by its .app folder does not start: add one with the location "
                        + "/usr/bin/open and the parameters -a Safari %URL% instead." : "");
        String why = reason == null || reason.isBlank() ? "Eclipse gave no reason." : reason.strip();
        return new HeadedMessage("Eclipse could not open its web browser for this link.", List.of(
                new HeadedMessage.Point("Link", "Copy it into a browser.").withCopy(url.toString()),
                new HeadedMessage.Point("Reason", why),
                new HeadedMessage.Point("Fix", fix)), "");
    }
}
