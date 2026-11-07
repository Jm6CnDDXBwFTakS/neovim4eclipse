// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The public update site as the tools read it: the Release tool waits for a release to appear
 * there ({@code tag}), {@code InstallCheck} installs the newest from it. One way to fetch a file
 * and one way to read the site's list of releases, so the two tools never disagree about what the
 * site holds.
 *
 * <p>The site is a p2 <i>composite</i> repository: {@code compositeContent.xml} names one child
 * repository per release, for example {@code <child location='releases/0.2.4'/>}.
 */
public final class UpdateSite {

    /** A child repository of a release; p2 writes single quotes, double ones are read too. */
    private static final Pattern CHILD = Pattern.compile("<child\\s+location=['\"]releases/(\\d+\\.\\d+\\.\\d+)/?['\"]");

    private UpdateSite() {
    }

    /** The releases {@code compositeContent} lists, in the file's order. */
    public static List<Version> releases(String compositeContent) {
        List<Version> found = new ArrayList<>();
        Matcher m = CHILD.matcher(compositeContent);
        while (m.find()) {
            found.add(Version.parse(m.group(1)));
        }
        return found;
    }

    /**
     * The body of a GET of {@code url}, or {@code null} unless the answer is 200 (a network error
     * counts as no answer). A query of its own goes with each request ({@code ?fresh=<time>}):
     * GitHub Pages answers through a cache that keeps a file up to ten minutes, and a URL the cache
     * has never seen is asked of GitHub itself, so a release just deployed is seen at once.
     */
    public static String get(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "?fresh=" + System.currentTimeMillis()))
                    .timeout(Duration.ofSeconds(60)).build();
            HttpResponse<byte[]> r = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return r.statusCode() == 200 ? new String(r.body(), StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted");
        }
    }
}
