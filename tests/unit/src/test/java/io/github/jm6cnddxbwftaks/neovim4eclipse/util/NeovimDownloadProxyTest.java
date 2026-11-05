// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Get Neovim's download goes through the HTTP proxy Eclipse's network settings name, picked by
 * {@link ProxyChoice} the way the Eclipse side's {@code prefs/EclipseProxies} does. A small proxy of
 * the test's own, on a local port, notes every request it gets.
 *
 * <p>This replaces the manual check "HTTP proxy" (gn-http-proxy), which needed a real proxy. The
 * archive's host is {@code neovim.invalid}, a name that never resolves (RFC 2606), so the download
 * can only succeed through the proxy. Two cases:
 * <ul>
 *   <li>An {@code http} URL: the client sends the whole URL to the proxy ({@code GET
 *       http://neovim.invalid/...}), and the proxy answers with the archive.</li>
 *   <li>An {@code https} URL, as GitHub's: the client asks the proxy for a tunnel ({@code CONNECT
 *       neovim.invalid:443}). The test proxy refuses it (a real one would connect to GitHub, and a
 *       TLS server of the test's own would need a certificate the client trusts), so the download
 *       fails, but only after the proxy was asked: the request line proves the route.</li>
 * </ul>
 */
class NeovimDownloadProxyTest {

    private static final byte[] BODY = "an archive".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tmp;

    private ServerSocket proxy;
    /** The request line of each request the proxy got, such as "CONNECT neovim.invalid:443 HTTP/1.1". */
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startTheProxy() throws IOException {
        proxy = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(this::serve, "test proxy");
        accept.setDaemon(true);
        accept.start();
    }

    @AfterEach
    void stopTheProxy() throws IOException {
        proxy.close();
    }

    /** One request per connection: the archive for a plain GET, a refusal for a tunnel. */
    private void serve() {
        while (!proxy.isClosed()) {
            try (Socket client = proxy.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
                String requestLine = in.readLine();
                for (String header = in.readLine(); header != null && !header.isEmpty(); header = in.readLine()) {
                    // the headers are not needed
                }
                if (requestLine == null) {
                    continue;
                }
                requests.add(requestLine);
                OutputStream out = client.getOutputStream();
                if (requestLine.startsWith("GET http://neovim.invalid/")) {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + BODY.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.write(BODY);
                } else {
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.ISO_8859_1));
                }
                out.flush();
            } catch (IOException e) {
                // the proxy was closed, or a client gave up: wait for the next one (or end)
            }
        }
    }

    /** The selector Eclipse's settings give: one HTTP proxy, this test's (as EclipseProxies builds it). */
    private ProxySelector eclipsesSettings() {
        List<Proxy> proxies = ProxyChoice.httpProxies(List.of(new ProxyChoice.Entry(false,
                proxy.getInetAddress().getHostAddress(), proxy.getLocalPort())));
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return proxies;
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException e) {
            }
        };
    }

    private static NeovimRelease.Archive archive(String url) {
        return new NeovimRelease.Archive(NeovimPlatform.byCode("la").orElseThrow(), "nvim-linux-arm64.tar.gz",
                URI.create(url), BODY.length, "0".repeat(64), "0".repeat(64));
    }

    @Test
    void anHttpDownloadIsFetchedThroughTheProxy() throws IOException {
        Path file = NeovimDownload.download(archive("http://neovim.invalid/nvim-linux-arm64.tar.gz"),
                NeovimDownload.client(eclipsesSettings(), null), tmp, InstallProgress.NONE);
        assertArrayEquals(BODY, Files.readAllBytes(file));
        assertEquals("GET http://neovim.invalid/nvim-linux-arm64.tar.gz HTTP/1.1", requests.get(0));
    }

    @Test
    void anHttpsDownloadAsksTheProxyForATunnelToTheServer() {
        NeovimInstallException e = assertThrows(NeovimInstallException.class, () -> NeovimDownload.download(
                archive("https://neovim.invalid/nvim-linux-arm64.tar.gz"), NeovimDownload.client(eclipsesSettings(), null),
                tmp, InstallProgress.NONE));
        assertEquals(NeovimInstallException.Reason.NETWORK, e.reason(), e.getMessage());
        assertEquals("CONNECT neovim.invalid:443 HTTP/1.1", requests.get(0), "the route is the proxy's tunnel");
    }
}
