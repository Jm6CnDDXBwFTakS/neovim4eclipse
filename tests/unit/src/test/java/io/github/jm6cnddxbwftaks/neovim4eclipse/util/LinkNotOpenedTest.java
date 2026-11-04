// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

/** The dialog for a link Eclipse's web browser could not open ({@code prefs/ExternalBrowser}). */
class LinkNotOpenedTest {

    private static final URI LINK = URI.create("https://www.virustotal.com/gui/file/2e58d259");

    @Test
    void theLinkIsThereToCopyWithEclipsesReason() {
        HeadedMessage message = LinkNotOpened.message(LINK,
                "Could not launch external web browser for /Applications/Safari.app. Check the Web Browser preferences.",
                true);
        assertEquals(LINK.toString(), message.point("Link").orElseThrow().copy());
        assertEquals("Could not launch external web browser for /Applications/Safari.app. Check the Web Browser "
                + "preferences.", message.point("Reason").orElseThrow().text());
        assertTrue(message.point("Fix").orElseThrow().text().startsWith(
                "Choose a browser in Window > Preferences > General > Web Browser."));
    }

    @Test
    void onMacOSTheFixSaysHowToAddABrowserThatStarts() {
        String fix = LinkNotOpened.message(LINK, "x", true).point("Fix").orElseThrow().text();
        assertTrue(fix.contains("/usr/bin/open") && fix.contains("-a Safari %URL%"), fix);
        String elsewhere = LinkNotOpened.message(LINK, "x", false).point("Fix").orElseThrow().text();
        assertFalse(elsewhere.contains("/usr/bin/open"), "macOS only: " + elsewhere);
    }

    @Test
    void noReasonSaysSoInsteadOfNull() {
        assertEquals("Eclipse gave no reason.", LinkNotOpened.message(LINK, null, false).point("Reason")
                .orElseThrow().text());
        assertEquals("Eclipse gave no reason.", LinkNotOpened.message(LINK, "  ", false).point("Reason")
                .orElseThrow().text());
    }

    @Test
    void theButtonsAreTheWebBrowserPreferencesThenClose() {
        assertEquals("Web Browser Preferences", LinkNotOpened.BUTTONS.get(0));
        assertEquals("Close", LinkNotOpened.BUTTONS.get(1));
    }
}
