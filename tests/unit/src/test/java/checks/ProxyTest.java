// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The manual checks' proxy ({@code releng/tools/checks/Proxy}): how it reads a request line, and a
 * tunnel and a forwarded request through it to a server of the test's own on this machine.
 */
class ProxyTest {

    private final List<String> log = new CopyOnWriteArrayList<>();
    private Proxy proxy;
    private ServerSocket host;

    @BeforeEach
    void start() throws IOException {
        proxy = Proxy.start(0, log::add);
        host = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
    }

    @AfterEach
    void stop() throws IOException {
        proxy.close();
        host.close();
    }

    // ---- request lines ----

    @Test
    void aConnectLineIsATunnelToItsHostAndPort() {
        Proxy.Request r = Proxy.Request.parse("CONNECT marketplace.eclipse.org:443 HTTP/1.1");
        assertEquals(new Proxy.Request("CONNECT", "marketplace.eclipse.org", 443, null), r);
        assertTrue(r.isTunnel());
        assertEquals("marketplace.eclipse.org:443", r.authority());
    }

    @Test
    void anIpv6TunnelKeepsItsBracketsOnlyInTheOutput() {
        Proxy.Request r = Proxy.Request.parse("CONNECT [::1]:8443 HTTP/1.1");
        assertEquals("::1", r.host());
        assertEquals("[::1]:8443", r.authority());
    }

    @Test
    void aPlainHttpUrlGoesToItsHostWithThePathAlone() {
        assertEquals(new Proxy.Request("GET", "example.org", 80, "/a/b?c=d"),
                Proxy.Request.parse("GET http://example.org/a/b?c=d HTTP/1.1"));
        assertEquals(new Proxy.Request("HEAD", "example.org", 8080, "/"),
                Proxy.Request.parse("HEAD http://example.org:8080 HTTP/1.1"));
    }

    @Test
    void linesAProxyCannotServeAreRefused() {
        assertNull(Proxy.Request.parse("GET /a HTTP/1.1"), "a path alone is for a server");
        assertNull(Proxy.Request.parse("GET https://example.org/ HTTP/1.1"), "https goes through CONNECT");
        assertNull(Proxy.Request.parse("CONNECT example.org HTTP/1.1"), "a tunnel needs a port");
        assertNull(Proxy.Request.parse("CONNECT example.org:443"), "no HTTP version");
        assertNull(Proxy.Request.parse("nonsense"));
    }

    @Test
    void aForwardedRequestDropsTheProxysHeadersAndClosesAfterItsAnswer() {
        String head = "GET http://example.org/a HTTP/1.1\r\nHost: example.org\r\nProxy-Connection: keep-alive\r\n"
                + "Connection: keep-alive\r\nAccept: */*\r\n\r\n";
        assertEquals("GET /a HTTP/1.1\r\nHost: example.org\r\nAccept: */*\r\nConnection: close\r\n\r\n",
                Proxy.Request.forwarded(head, Proxy.Request.parse("GET http://example.org/a HTTP/1.1")));
    }

    @Test
    void aHeadEndsAtTheFirstBlankLine() throws IOException {
        InputStream in = new ByteArrayInputStream("CONNECT h:1 HTTP/1.1\r\nHost: h\r\n\r\nbody".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("CONNECT h:1 HTTP/1.1\r\nHost: h\r\n\r\n", Proxy.readHead(in));
        assertEquals("body", new String(in.readAllBytes(), StandardCharsets.ISO_8859_1), "the rest stays unread");
        assertNull(Proxy.readHead(new ByteArrayInputStream("CONNECT h:1 HTTP/1.1\r\n".getBytes(StandardCharsets.ISO_8859_1))));
    }

    // ---- through the proxy ----

    /** A tunnel copies bytes both ways unread, as TLS needs: here a host that answers "pong" to "ping". */
    @Test
    void aTunnelCarriesBytesBothWays() throws Exception {
        Thread server = Thread.ofVirtual().start(() -> {
            try (Socket s = host.accept()) {
                byte[] ping = s.getInputStream().readNBytes(4);
                s.getOutputStream().write(new String(ping, StandardCharsets.ISO_8859_1).equals("ping")
                        ? "pong".getBytes(StandardCharsets.ISO_8859_1) : "????".getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                // the test fails on the client side
            }
        });
        try (Socket client = toProxy()) {
            send(client, "CONNECT 127.0.0.1:" + host.getLocalPort() + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            assertEquals("HTTP/1.1 200 Connection established\r\n\r\n", Proxy.readHead(client.getInputStream()));
            send(client, "ping");
            assertEquals("pong", new String(client.getInputStream().readNBytes(4), StandardCharsets.ISO_8859_1));
        }
        server.join();
        assertEquals(List.of("1  CONNECT 127.0.0.1:" + host.getLocalPort()), log);
    }

    @Test
    void aPlainRequestIsForwardedWithThePathAlone() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        Thread server = Thread.ofVirtual().start(() -> {
            try (Socket s = host.accept()) {
                got.add(Proxy.readHead(s.getInputStream()));
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                // the test fails on the client side
            }
        });
        try (Socket client = toProxy()) {
            send(client, "GET http://127.0.0.1:" + host.getLocalPort() + "/a HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Proxy-Connection: keep-alive\r\n\r\n");
            assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok",
                    new String(client.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1));
        }
        server.join();
        assertEquals(List.of("GET /a HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"), got);
    }

    @Test
    void aHostThatCannotBeReachedIsA502() throws Exception {
        int closed = host.getLocalPort();
        host.close(); // nothing listens there now
        try (Socket client = toProxy()) {
            send(client, "CONNECT 127.0.0.1:" + closed + " HTTP/1.1\r\n\r\n");
            assertTrue(Proxy.readHead(client.getInputStream()).startsWith("HTTP/1.1 502 Bad Gateway\r\n"));
        }
        assertTrue(log.get(0).startsWith("1  CONNECT 127.0.0.1:" + closed + " failed: "), log::toString);
    }

    private Socket toProxy() throws IOException {
        return new Socket(InetAddress.getLoopbackAddress(), proxy.port());
    }

    private static void send(Socket socket, String text) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }
}
