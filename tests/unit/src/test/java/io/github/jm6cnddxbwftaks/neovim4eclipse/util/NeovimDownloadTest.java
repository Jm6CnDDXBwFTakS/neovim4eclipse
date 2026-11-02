// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/**
 * {@link NeovimDownload} against a local HTTP server (the JDK's own), so no test touches GitHub: the
 * archive, a redirect as GitHub sends one, a proxy's 407, and a missing file.
 */
class NeovimDownloadTest {

    @TempDir
    Path tmp;

    HttpServer server;
    byte[] body = "an archive".getBytes();

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asset", x -> {
            x.sendResponseHeaders(200, body.length);
            try (OutputStream out = x.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/redirect", x -> {
            x.getResponseHeaders().add("Location", "/asset");
            x.sendResponseHeaders(302, -1);
            x.close();
        });
        server.createContext("/proxy", x -> {
            x.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"test\"");
            x.sendResponseHeaders(407, -1);
            x.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private NeovimRelease.Archive archive(String path) {
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        return new NeovimRelease.Archive(NeovimPlatform.byCode("la").orElseThrow(), "nvim-linux-arm64.tar.gz", url,
                body.length, "0".repeat(64));
    }

    private Path download(String path) {
        return NeovimDownload.download(archive(path), NeovimDownload.client(ProxySelector.of(null), null), tmp,
                InstallProgress.NONE);
    }

    @Test
    void downloadsFollowingTheRedirect() throws IOException {
        Path file = download("/redirect");
        assertArrayEquals(body, Files.readAllBytes(file));
        assertTrue(file.getFileName().toString().startsWith(PluginNeovim.DOWNLOAD_PREFIX), file.toString());
    }

    @Test
    void aProxyThatWantsAPasswordNamesTheEclipseIniLine() throws IOException {
        NeovimInstallException e = assertThrows(NeovimInstallException.class, () -> download("/proxy"));
        assertEquals(NeovimInstallException.Reason.PROXY_PASSWORD, e.reason());
        assertTrue(e.getMessage().contains(NeovimDownload.TUNNEL_AUTH_LINE), e.getMessage());
        assertNoLeftovers();
    }

    @Test
    void anErrorStatusIsANetworkError() throws IOException {
        NeovimInstallException e = assertThrows(NeovimInstallException.class, () -> download("/missing"));
        assertEquals(NeovimInstallException.Reason.NETWORK, e.reason());
        assertTrue(e.getMessage().contains("HTTP 404"), e.getMessage());
        assertNoLeftovers();
    }

    @Test
    void anUnreachableServerSaysWhatToCheck() throws IOException {
        server.stop(0);
        NeovimInstallException e = assertThrows(NeovimInstallException.class, () -> download("/asset"));
        assertEquals(NeovimInstallException.Reason.NETWORK, e.reason());
        assertTrue(e.getMessage().contains("Network Connections"), e.getMessage());
        assertNoLeftovers();
    }

    private void assertNoLeftovers() throws IOException {
        try (Stream<Path> files = Files.list(tmp)) {
            assertEquals(0, files.count(), "the temporary file is deleted");
        }
    }
}
