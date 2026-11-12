// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A small HTTP proxy on this machine, for the manual check {@code mp-setup} (docs/MANUAL_CHECKS.md):
 * Eclipse's <i>Network Connections</i> point at it, and it prints every host Eclipse reaches
 * through it, so the check can see that the Marketplace install, the update site and Get Neovim's
 * download all took the proxy:
 * <pre>
 *   java releng/tools/checks/Proxy.java               # on 127.0.0.1:3128
 *   java releng/tools/checks/Proxy.java --port 8080
 * </pre>
 * Example of its output: {@code 3  CONNECT marketplace.eclipse.org:443}, the third connection,
 * a tunnel for HTTPS.
 *
 * <p><b>How an HTTP proxy works.</b> For an {@code https} address the client asks for a tunnel,
 * {@code CONNECT host:443}; the proxy connects to that host, answers {@code 200}, and from then on
 * copies bytes both ways without reading them: TLS runs end to end, between the client and the
 * server, so the proxy sees the host's name and nothing of what is sent. For a plain {@code http}
 * address the client sends the whole URL ({@code GET http://host/path}); the proxy sends the
 * request on to the host, with the path alone ({@code GET /path}) as a server expects it.
 *
 * <p>It listens on the loopback address only, so no other machine can use it: an open proxy on a
 * network is soon used by strangers. It wants no password, and keeps nothing. One file without
 * the other tools, so that {@code java} runs it on Java 21 too.
 */
public final class Proxy implements AutoCloseable {

    /** The port it listens on unless told otherwise: the usual one for an HTTP proxy (Squid's). */
    static final int DEFAULT_PORT = 3128;

    /** How long to wait for a host to accept the connection, in milliseconds. */
    private static final int CONNECT_TIMEOUT_MS = 15_000;

    /** The most a request's head (request line and headers) may hold; a real one is a few hundred bytes. */
    private static final int MAX_HEAD = 64 * 1024;

    private final ServerSocket server;
    private final Consumer<String> log;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    /** Numbers the connections in the output, so that a reader can count them. */
    private final AtomicInteger count = new AtomicInteger();

    private Proxy(ServerSocket server, Consumer<String> log) {
        this.server = server;
        this.log = log;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = port(args);
        Proxy started;
        try {
            started = start(port, System.out::println);
        } catch (BindException e) {
            // Example: a Squid already listens on 3128, its default port too.
            System.err.println("Proxy: port " + port + " is in use (" + e.getMessage() + "); choose another with --port <number>,"
                    + " and give Eclipse that port.");
            System.exit(1);
            return;
        }
        try (Proxy proxy = started) {
            System.out.println("Proxy on 127.0.0.1:" + proxy.port() + ". In Eclipse: Window > Preferences > General >"
                    + " Network Connections, Active Provider: Manual, HTTP and HTTPS: host 127.0.0.1, port "
                    + proxy.port() + ". Ctrl+C stops it.");
            new CountDownLatch(1).await(); // until Ctrl+C
        }
    }

    /** The port {@code args} ask for ({@code --port <number>}), or {@link #DEFAULT_PORT}; exits on anything else. */
    private static int port(String[] args) {
        if (args.length == 0) {
            return DEFAULT_PORT;
        }
        if (args.length == 2 && args[0].equals("--port")) {
            try {
                int port = Integer.parseInt(args[1]);
                if (port >= 1 && port <= 65_535) {
                    return port;
                }
            } catch (NumberFormatException e) {
                // the usage below says what a port is
            }
        }
        System.err.println("Usage: java releng/tools/checks/Proxy.java [--port <number from 1 to 65535>]   (default "
                + DEFAULT_PORT + ")");
        System.exit(2);
        return DEFAULT_PORT; // not reached
    }

    /**
     * Starts listening on {@code port} of the loopback address (0: any free port) and serving each
     * connection on a thread of its own; {@code log} hears one line per connection.
     */
    static Proxy start(int port, Consumer<String> log) throws IOException {
        Proxy proxy = new Proxy(new ServerSocket(port, 50, InetAddress.getLoopbackAddress()), log);
        Thread accepting = new Thread(proxy::accept, "proxy");
        accepting.setDaemon(true);
        accepting.start();
        return proxy;
    }

    int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        server.close();
        connections.shutdownNow();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                connections.submit(() -> serve(client));
            } catch (IOException e) {
                // closed: the loop ends
            }
        }
    }

    /** One client connection: its request's head, then a tunnel or one forwarded request. */
    private void serve(Socket client) {
        try (client) {
            InputStream in = new BufferedInputStream(client.getInputStream());
            String head = readHead(in);
            if (head == null) {
                return; // the client gave up, or sent no proper request
            }
            Request request = Request.parse(head.substring(0, head.indexOf("\r\n")));
            OutputStream out = client.getOutputStream();
            if (request == null) {
                reply(out, "400 Bad Request");
                return;
            }
            int number = count.incrementAndGet();
            try (Socket target = new Socket()) {
                try {
                    target.connect(new InetSocketAddress(request.host(), request.port()), CONNECT_TIMEOUT_MS);
                } catch (IOException e) {
                    log.accept(number + "  " + request.method() + " " + request.authority() + " failed: " + e.getMessage());
                    reply(out, "502 Bad Gateway");
                    return;
                }
                log.accept(number + "  " + request.method() + " " + request.authority());
                if (request.isTunnel()) {
                    reply(out, "200 Connection established");
                    pump(in, client, target, -1);
                } else {
                    target.getOutputStream().write(Request.forwarded(head, request).getBytes(StandardCharsets.ISO_8859_1));
                    pump(in, client, target, Request.bodyLength(head));
                }
            }
        } catch (IOException e) {
            // a side closed the connection: nothing more to copy
        }
    }

    /**
     * Copies bytes both ways until the host closes: client to host on a thread of its own, host to
     * client on this one. The client's further bytes include what {@code in} buffered after the head.
     *
     * <p>{@code clientBytes} limits what goes to the host: -1 for a tunnel (everything, until the
     * client is done), else a forwarded request's body length, so that only that one request
     * reaches the host. Example: a client that reuses its connection to the proxy sends its next
     * request, perhaps for another host, after the answer; it must not go to this host. The client
     * gets the connection closed instead and asks again on a new one.
     *
     * <p>If the client's side fails (a reset), the host's socket is closed, which ends the copy
     * the other way at once; otherwise it would wait for the host to time out, minutes later.
     */
    private void pump(InputStream in, Socket client, Socket target, long clientBytes) throws IOException {
        connections.submit(() -> {
            try {
                OutputStream toHost = target.getOutputStream();
                if (clientBytes < 0) {
                    in.transferTo(toHost);
                    target.shutdownOutput(); // the client is done sending: tell the host
                } else {
                    copy(in, toHost, clientBytes);
                }
            } catch (IOException e) {
                closeQuietly(target); // the client's side failed: end the other direction too
            }
        });
        target.getInputStream().transferTo(client.getOutputStream());
    }

    /** Copies exactly {@code length} bytes, fewer only if {@code in} ends first. */
    private static void copy(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[8192];
        for (long left = length; left > 0;) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) {
                return;
            }
            out.write(buffer, 0, n);
            left -= n;
        }
        out.flush();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // closing is all that was wanted
        }
    }

    private static void reply(OutputStream out, String status) throws IOException {
        String body = status.startsWith("200") ? "" : "Content-Length: 0\r\nConnection: close\r\n";
        out.write(("HTTP/1.1 " + status + "\r\n" + body + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /**
     * Reads up to and including the blank line that ends a request's headers, as text, or
     * {@code null} if the stream ends first or the head is longer than {@link #MAX_HEAD}.
     */
    static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0; // how much of "\r\n\r\n" was just read
        while (head.size() < MAX_HEAD) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            head.write(b);
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) {
                return head.toString(StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    /**
     * A request's first line, read. {@code path} is for a forwarded {@code http} request only, the
     * part a server expects ({@code /path?query}); a tunnel has none.
     */
    record Request(String method, String host, int port, String path) {

        boolean isTunnel() {
            return method.equals("CONNECT");
        }

        /** {@code host:port} as written in the output, with brackets around an IPv6 address. */
        String authority() {
            return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        }

        /**
         * Reads a request line, or {@code null} for one a proxy cannot serve. Examples:
         * {@code CONNECT github.com:443 HTTP/1.1} is a tunnel to {@code github.com} port 443;
         * {@code GET http://example.org/a?b HTTP/1.1} goes to {@code example.org} port 80, path
         * {@code /a?b}. A path alone ({@code GET /a}) is a request for a server, not a proxy.
         */
        static Request parse(String line) {
            String[] parts = line.split(" ");
            if (parts.length != 3 || !parts[2].startsWith("HTTP/")) {
                return null;
            }
            String method = parts[0];
            if (method.equals("CONNECT")) {
                return hostPort(parts[1], -1).map(a -> new Request(method, a.host(), a.port(), null)).orElse(null);
            }
            String url = parts[1];
            if (!url.regionMatches(true, 0, "http://", 0, 7)) {
                return null; // a path alone is for a server; https goes through CONNECT
            }
            int slash = url.indexOf('/', 7);
            String rest = slash < 0 ? "/" : url.substring(slash);
            int fragment = rest.indexOf('#');
            String path = fragment < 0 ? rest : rest.substring(0, fragment);
            return hostPort(slash < 0 ? url.substring(7) : url.substring(7, slash), 80)
                    .map(a -> new Request(method, a.host(), a.port(), path)).orElse(null);
        }

        /** A host and port, as {@link #hostPort(String, int)} reads them. */
        record HostPort(String host, int port) {
        }

        /**
         * Reads {@code host:port}, {@code [ipv6]:port} or a host alone (then {@code defaultPort};
         * -1: a port is required). Read by hand, not with {@code java.net.URI}, whose host rules
         * refuse an underscore, which DNS names may hold ({@code some_host.example.net}).
         */
        static Optional<HostPort> hostPort(String text, int defaultPort) {
            String hostPort = text.substring(text.lastIndexOf('@') + 1); // no user name is passed on
            String host;
            String port;
            if (hostPort.startsWith("[")) {
                int close = hostPort.indexOf(']');
                if (close < 0) {
                    return Optional.empty();
                }
                host = hostPort.substring(1, close);
                String after = hostPort.substring(close + 1);
                if (!after.isEmpty() && !after.startsWith(":")) {
                    return Optional.empty();
                }
                port = after.isEmpty() ? null : after.substring(1);
            } else {
                int colon = hostPort.lastIndexOf(':');
                host = colon < 0 ? hostPort : hostPort.substring(0, colon);
                port = colon < 0 ? null : hostPort.substring(colon + 1);
                if (host.contains(":")) {
                    return Optional.empty(); // an IPv6 address must be in brackets
                }
            }
            int number = defaultPort;
            if (port != null) {
                try {
                    number = Integer.parseInt(port);
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            }
            if (host.isEmpty() || number < 1 || number > 65_535) {
                return Optional.empty();
            }
            return Optional.of(new HostPort(host, number));
        }

        /**
         * The length of the body that follows {@code head}: its {@code Content-Length}, 0 if it has
         * none, or -1 (all of it, until the client is done) for a body sent in chunks
         * ({@code Transfer-Encoding: chunked}), whose end this proxy does not read.
         */
        static long bodyLength(String head) {
            long length = 0;
            for (String line : head.split("\r\n")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("transfer-encoding:")) {
                    return -1;
                }
                if (lower.startsWith("content-length:")) {
                    try {
                        length = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
            return length;
        }

        /**
         * The head to send the host for a plain {@code http} request: the path alone in the
         * request line, the proxy's own headers left out, and {@code Connection: close}, so that
         * the host ends the connection after its answer (one request per connection keeps this
         * proxy simple). Example: {@code GET http://example.org/a HTTP/1.1} with
         * {@code Proxy-Connection: keep-alive} becomes {@code GET /a HTTP/1.1} with
         * {@code Connection: close}.
         */
        static String forwarded(String head, Request request) {
            String[] lines = head.split("\r\n");
            String version = lines[0].substring(lines[0].lastIndexOf(' ') + 1);
            StringBuilder out = new StringBuilder(request.method() + " " + request.path() + " " + version + "\r\n");
            for (int i = 1; i < lines.length; i++) {
                String name = lines[i].toLowerCase(Locale.ROOT);
                if (!name.isEmpty() && !name.startsWith("proxy-") && !name.startsWith("connection:")) {
                    out.append(lines[i]).append("\r\n");
                }
            }
            return out.append("Connection: close\r\n\r\n").toString();
        }
    }
}
