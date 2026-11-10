// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import common.Http;
import common.ToolFailure;

/**
 * The upgrade tools' download rules ({@code releng/tools/common/Http}) that need no network: only
 * HTTPS, published hash files read as their publishers write them, release dates read from
 * {@code Last-Modified}, and a file kept from an earlier run used only if it still matches.
 */
class HttpTest {

    @TempDir
    Path folder;

    @Test
    void plainHttpIsRefusedBeforeAnyConnection() {
        ToolFailure f = assertThrows(ToolFailure.class, () -> Http.text("http://repo.maven.apache.org/maven2/"));
        assertTrue(f.getMessage().contains("only HTTPS"), f.getMessage());
        assertThrows(ToolFailure.class, () -> Http.lastModified("http://example.org/a.zip"));
        assertThrows(ToolFailure.class, () -> Http.download("file:///etc/passwd", new Http.Digest("SHA-1", "0".repeat(40)), folder));
    }

    @Test
    void aHashFileIsReadWithOrWithoutTheFileNameAfterIt() {
        String sha1 = "a".repeat(40);
        assertEquals(sha1, Http.digestIn(sha1 + "\n", 40)); // Maven Central: the digest alone
        assertEquals(sha1, Http.digestIn(sha1.toUpperCase() + "  maven-wrapper-3.3.4.zip\n", 40)); // sha1sum's form
    }

    @Test
    void aHashFileOfTheWrongLengthOrAnErrorPageIsRefused() {
        assertThrows(ToolFailure.class, () -> Http.digestIn("a".repeat(40), 128)); // a SHA-1 where a SHA-512 belongs
        assertThrows(ToolFailure.class, () -> Http.digestIn("<html><body>Not Found</body></html>", 40));
    }

    @Test
    void lastModifiedIsAnHttpDate() {
        assertEquals(Optional.of(Instant.parse("2025-09-08T13:21:56Z")), Http.parseDate("Mon, 08 Sep 2025 13:21:56 GMT"));
        assertEquals(Optional.empty(), Http.parseDate("yesterday"));
    }

    @Test
    void aFileFromAnEarlierRunIsUsedWhenItStillMatches() throws IOException {
        Path file = Files.writeString(folder.resolve("apache-maven-3.9.16-bin.zip"), "the archive", StandardCharsets.UTF_8);
        String sha = Http.hash(file, "SHA-512");
        // No connection is made (an unreachable host would fail): the kept file matches.
        assertEquals(file, Http.download("https://unreachable.invalid/x/apache-maven-3.9.16-bin.zip", new Http.Digest("SHA-512", sha), folder));
    }

    @Test
    void hashesAreLowercaseHex() throws IOException {
        Path file = Files.writeString(folder.resolve("abc.txt"), "abc", StandardCharsets.UTF_8);
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Http.hash(file, "SHA-1"));
    }
}
