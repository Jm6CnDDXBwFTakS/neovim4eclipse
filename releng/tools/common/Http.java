// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * The upgrade tools' downloads: HTTPS only, and every file checked against the hash its publisher
 * gives before anything uses it. Shared by the Eclipse tool (Platform downloads and their SHA-512
 * from download.eclipse.org) and the Maven tool (Maven's archive and its SHA-512, the wrapper's
 * archive and its SHA-1, from Maven Central).
 *
 * <p>Why HTTPS only: the hash says the file is the one the publisher meant only if the hash itself
 * came over a connection nobody in between could change. A hash fetched over plain HTTP proves
 * nothing, so {@link #request} refuses any other scheme.
 *
 * <p>Example: {@code Http.download(url, new Http.Digest("SHA-512", "a3464f0b…"), cache)} returns
 * {@code cache/eclipse-platform-4.34-linux-gtk-x86_64.tar.gz} once its SHA-512 matches; a file
 * already there from an earlier run is kept if it still matches.
 */
public final class Http {

    /**
     * A hash a publisher gives for a file: the {@link MessageDigest} algorithm name
     * ({@code SHA-512}, {@code SHA-1}) and the lowercase hex digest.
     */
    public record Digest(String algorithm, String hex) {
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private Http() {
    }

    /** The body of a GET of {@code url}, which must answer 200. */
    public static String text(String url) {
        HttpResponse<String> r = send(url, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) {
            throw new ToolFailure("GET " + url + " answered " + r.statusCode());
        }
        return r.body();
    }

    /** {@link #text}, or {@code null} for an answer other than 200: used to try a file under two names. */
    public static String textOrNull(String url) {
        HttpResponse<String> r = send(url, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return r.statusCode() == 200 ? r.body() : null;
    }

    /**
     * When {@code url} was last changed, from its {@code Last-Modified} header (a HEAD request), or
     * empty if the server gives none. On Maven Central a release's files are written once, when it
     * is published, so this is the release's date: the Maven tool's "a week old" rule reads it.
     */
    public static Optional<Instant> lastModified(String url) {
        HttpRequest head = HttpRequest.newBuilder(uri(url)).timeout(Duration.ofMinutes(1))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<Void> r = send(head, HttpResponse.BodyHandlers.discarding(), url);
        if (r.statusCode() != 200) {
            throw new ToolFailure("HEAD " + url + " answered " + r.statusCode());
        }
        return r.headers().firstValue("Last-Modified").flatMap(Http::parseDate);
    }

    /** An HTTP date ({@code Mon, 08 Sep 2025 13:21:56 GMT}), or empty if it is not one. */
    public static Optional<Instant> parseDate(String value) {
        try {
            return Optional.of(ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * {@code url} in {@code cache}, checked against {@code expected}: a file already there is kept
     * if it matches, else fetched again. The download goes to a {@code .part} file first, so a
     * stopped run never leaves a half file under the real name, and a file that does not match is
     * deleted, never used.
     */
    public static Path download(String url, Digest expected, Path cache) {
        Path file = cache.resolve(url.substring(url.lastIndexOf('/') + 1));
        if (Files.isRegularFile(file) && hash(file, expected.algorithm()).equals(expected.hex())) {
            System.out.println("ok: " + file.getFileName() + " from an earlier run, " + expected.algorithm() + " as published");
            return file;
        }
        Path part = file.resolveSibling(file.getFileName() + ".part");
        try {
            Files.createDirectories(cache);
            System.out.println("Downloading " + url + " ...");
            HttpResponse<Path> r = send(url, HttpResponse.BodyHandlers.ofFile(part));
            if (r.statusCode() != 200) {
                throw new ToolFailure("GET " + url + " answered " + r.statusCode());
            }
            String got = hash(part, expected.algorithm());
            if (!got.equals(expected.hex())) {
                throw new ToolFailure(url + " has " + expected.algorithm() + " " + got + ", but its publisher gives "
                        + expected.hex() + "; it was not used");
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ToolFailure("download of " + url + " failed: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(part); // gone after the move; left only by a failure: an error page, half a file
            } catch (IOException e) {
                System.out.println("  (could not delete " + part + ": " + e.getMessage() + ")");
            }
        }
        System.out.println("ok: " + file.getFileName() + ", " + expected.algorithm() + " as published");
        return file;
    }

    /** The lowercase hex {@code algorithm} hash of {@code file}. */
    public static String hash(Path file, String algorithm) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0;) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + file + ": " + e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java has SHA-1, SHA-256 and SHA-512
        }
    }

    /**
     * The hex digest at the start of a published hash file: Maven Central's {@code .sha512} holds
     * only the digest, other publishers add the file name after it ({@code a3464f0b…  file.zip}).
     */
    public static String digestIn(String hashFile, int hexLength) {
        String first = hashFile.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!first.matches("[0-9a-f]{" + hexLength + "}")) {
            throw new ToolFailure("not a hash of " + hexLength + " hex digits: " + abbreviate(hashFile));
        }
        return first;
    }

    private static String abbreviate(String text) {
        String t = text.strip();
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }

    private static <T> HttpResponse<T> send(String url, HttpResponse.BodyHandler<T> body) {
        return send(request(url), body, url);
    }

    private static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body, String url) {
        try {
            return CLIENT.send(request, body);
        } catch (IOException e) {
            throw new ToolFailure(request.method() + " " + url + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted");
        }
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(uri(url)).timeout(Duration.ofMinutes(10)).build();
    }

    private static URI uri(String url) {
        if (!url.startsWith("https://")) {
            throw new ToolFailure("only HTTPS is fetched: " + url); // a hash fetched over HTTP proves nothing
        }
        return URI.create(url);
    }
}
