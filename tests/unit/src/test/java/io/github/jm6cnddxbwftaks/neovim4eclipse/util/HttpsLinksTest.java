// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Only https pages with a host are opened: anything else could run a program (file: on Windows). */
class HttpsLinksTest {

    @ParameterizedTest
    @ValueSource(strings = { "https://github.com/neovim/neovim/releases/tag/v0.12.6",
            "HTTPS://www.virustotal.com/gui/file/abc", "https://example.com" })
    void httpsPagesPass(String url) {
        assertTrue(HttpsLinks.isHttps(URI.create(url)), url);
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://neovim.io", "file:///C:/x/evil.exe", "file:/tmp/x", "javascript:alert(1)",
            "mailto:a@b.c", "https:relative", "ms-settings:privacy", "C:/x/evil.exe", "/usr/bin/xterm" })
    void anythingElseFails(String url) {
        assertFalse(HttpsLinks.isHttps(URI.create(url)), url);
    }

    @Test
    void nullFails() {
        assertFalse(HttpsLinks.isHttps(null));
    }

    @Test
    void aDialogLinkMustBeHttps() {
        URI page = URI.create("https://neovim.io");
        assertEquals(page, new HeadedMessage.Link("Neovim", page).url());
        assertThrows(IllegalArgumentException.class,
                () -> new HeadedMessage.Link("Program", URI.create("file:///C:/x/evil.exe")));
    }
}
