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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/**
 * {@link NeovimDownload} against a local HTTP server (the JDK's own), so no test touches GitHub: the
 * archive, a redirect as GitHub sends one, a proxy's 407, a missing file, a connection that stops
 * sending, and a body longer than the archive.
 */
class NeovimDownloadTest {

    @TempDir
    Path tmp;

    HttpServer server;
    byte[] body = "an archive".getBytes();
    /** Holds {@code /stall}'s handler after the first bytes, until the test ends. */
    final CountDownLatch release = new CountDownLatch(1);

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
        server.createContext("/stall", x -> {
            x.sendResponseHeaders(200, body.length);
            OutputStream out = x.getResponseBody();
            out.write(body, 0, 3);
            out.flush();
            try {
                release.await(); // then nothing more, with the connection open
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            x.close();
        });
        server.createContext("/longer", x -> {
            byte[] longer = new byte[body.length * 2];
            x.sendResponseHeaders(200, longer.length);
            try (OutputStream out = x.getResponseBody()) {
                out.write(longer);
            }
        });
        server.createContext("/proxy", x -> {
            x.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"test\"");
            x.sendResponseHeaders(407, -1);
            x.close();
        });
        server.setExecutor(Executors.newCachedThreadPool()); // a held /stall must not block the others
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private NeovimRelease.Archive archive(String path) {
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        return new NeovimRelease.Archive(NeovimPlatform.byCode("la").orElseThrow(), "nvim-linux-arm64.tar.gz", url,
                body.length, "0".repeat(64), "0".repeat(64));
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

    @Test
    void aConnectionThatStopsSendingIsGivenUp() throws IOException {
        // The stall clock starts with the request and is looked at every 250 ms (POLL_MILLIS), so
        // the stall must leave room for the connection and the first 3 bytes, even when this is the
        // run's first HTTP use (class loading, thread start). 300 ms once failed on Windows
        // "after 0 of 10 bytes": the first look came after the stall, before any byte had arrived.
        NeovimInstallException e = assertThrows(NeovimInstallException.class,
                () -> NeovimDownload.download(archive("/stall"), NeovimDownload.client(ProxySelector.of(null), null),
                        tmp, InstallProgress.NONE, Duration.ofSeconds(1)));
        assertEquals(NeovimInstallException.Reason.NETWORK, e.reason());
        assertTrue(e.getMessage().contains("stalled"), e.getMessage());
        assertTrue(e.getMessage().contains("after 3 of " + body.length + " bytes"), e.getMessage());
        assertNoLeftovers();
    }

    @Test
    void cancelEndsADownloadThatWaitsForBytes() throws IOException {
        // The default stall time (a minute) is far off: only Cancel can end this download now.
        InstallProgress cancelAfterTheFirstBytes = new InstallProgress() {
            private long received;

            @Override
            public void step(String name, long total) {
            }

            @Override
            public void worked(long amount) {
                received += amount;
            }

            @Override
            public boolean cancelled() {
                return received > 0;
            }
        };
        NeovimInstallException e = assertThrows(NeovimInstallException.class, () -> NeovimDownload.download(
                archive("/stall"), NeovimDownload.client(ProxySelector.of(null), null), tmp,
                cancelAfterTheFirstBytes));
        assertEquals(NeovimInstallException.Reason.CANCELLED, e.reason());
        assertNoLeftovers();
    }

    @Test
    void aBodyLongerThanTheArchiveIsStopped() throws IOException {
        NeovimInstallException e = assertThrows(NeovimInstallException.class, () -> download("/longer"));
        assertEquals(NeovimInstallException.Reason.NETWORK, e.reason());
        assertTrue(e.getMessage().contains("more than its " + body.length + " bytes"), e.getMessage());
        assertNoLeftovers();
    }

    /**
     * Only Java's own tunnel message for a 407 means a proxy password; any other error whose text
     * happens to hold "407" stays a network error and keeps its own words.
     */
    @Test
    void onlyTheTunnelsOwn407IsAProxyPassword() {
        NeovimRelease.Archive a = archive("/asset");
        NeovimInstallException e = assertThrows(NeovimInstallException.class,
                () -> NeovimDownload.networkMessage(a, new IOException("Tunnel failed, got: 407")));
        assertEquals(NeovimInstallException.Reason.PROXY_PASSWORD, e.reason());
        for (String other : new String[] { "Connection reset after 14070 bytes", "Tunnel failed, got: 502",
                "proxy-407.example.org: closed" }) {
            String message = NeovimDownload.networkMessage(a, new IOException(other));
            assertTrue(message.contains(other), message);
        }
    }

    private void assertNoLeftovers() throws IOException {
        try (Stream<Path> files = Files.list(tmp)) {
            assertEquals(0, files.count(), "the temporary file is deleted");
        }
    }
}
