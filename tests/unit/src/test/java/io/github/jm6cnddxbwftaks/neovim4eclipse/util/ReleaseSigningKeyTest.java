// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/**
 * Keeps the copies of the release signing key, and the fingerprint the docs print, in step.
 *
 * <p>Releases are PGP-signed with one key (docs/design/MARKETPLACE.md, "Phase 4"). Its public half
 * lives in two places: {@code releng/signing-key.asc}, which the release workflow checks every
 * signature against, and a copy inside the plug-in, which {@code plugin.xml} declares as trusted so
 * that updates install without asking. Its fingerprint is printed in the README, SECURITY.md and on
 * the update site's page, where users compare it with the one Eclipse shows. If the key is ever
 * replaced, this test lists every place that still has the old one.
 *
 * <p>The fingerprint is computed here, without gpg, the way OpenPGP defines it for a version 4 key
 * (RFC 4880, section 12.2): the SHA-1 of the byte 0x99, the public-key packet's body length as two
 * bytes, and the body. Example: {@code gpg --show-keys releng/signing-key.asc} prints the same 40
 * hex digits.
 */
class ReleaseSigningKeyTest {

    private static final String PLUGIN = "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/";
    private static final String RELENG_KEY = "releng/signing-key.asc";
    private static final String PLUGIN_KEY_PATH = "keys/release-signing-key.asc";
    /** Where users are told the fingerprint, to compare with what Eclipse's trust dialog shows. */
    private static final String[] FINGERPRINT_DOCS = { "README.md", "SECURITY.md", "releng/update-site.sh" };

    @Test
    void pluginCarriesTheSameKeyAsReleng() throws IOException {
        assertArrayEquals(bytes(RELENG_KEY), bytes(PLUGIN + PLUGIN_KEY_PATH),
                PLUGIN + PLUGIN_KEY_PATH + " differs from " + RELENG_KEY + "; copy " + RELENG_KEY + " over it");
    }

    @Test
    void keyIsPublicOnly() throws IOException {
        String key = text(RELENG_KEY);
        assertTrue(key.startsWith("-----BEGIN PGP PUBLIC KEY BLOCK-----"), RELENG_KEY + " is not an armored public key");
        assertFalse(key.contains("PRIVATE KEY"), RELENG_KEY + " contains a secret key: remove it and revoke the key");
    }

    @Test
    void pluginDeclaresTheKeyAsTrustedAndShipsIt() throws IOException {
        String pluginXml = text(PLUGIN + "plugin.xml");
        assertTrue(pluginXml.contains("<extension point=\"org.eclipse.equinox.p2.engine.pgp\">"),
                "plugin.xml lacks the org.eclipse.equinox.p2.engine.pgp extension");
        assertTrue(pluginXml.contains("<trustedKeys path=\"" + PLUGIN_KEY_PATH + "\"/>"),
                "plugin.xml's trustedKeys should point to " + PLUGIN_KEY_PATH);
        // Without this, the key works when launched from the IDE but is missing from the built jar.
        assertTrue(text(PLUGIN + "build.properties").matches("(?s).*bin\\.includes\\s*=.*\\bkeys/.*"),
                "build.properties' bin.includes lacks keys/");
    }

    @Test
    void docsPrintTheKeysFingerprint() throws Exception {
        String fingerprint = grouped(fingerprint(text(RELENG_KEY)));
        for (String doc : FINGERPRINT_DOCS) {
            assertTrue(text(doc).contains(fingerprint), doc + " should print the signing key's fingerprint " + fingerprint);
        }
    }

    @Test
    void groupsLikeGpg() {
        assertEquals("A91F 53D8 931B 7FD0 D146  8618 6CC9 E446 147B 063C",
                grouped("A91F53D8931B7FD0D14686186CC9E446147B063C"));
    }

    // ---- OpenPGP, just enough for a fingerprint ----

    /** The fingerprint of the first (primary) key in an armored public key block, 40 hex digits. */
    static String fingerprint(String armored) throws Exception {
        byte[] data = dearmor(armored);
        int tag;
        int length;
        int bodyStart;
        int header = data[0] & 0xff;
        if ((header & 0xc0) == 0xc0) { // new packet format: tag in the low 6 bits
            tag = header & 0x3f;
            int first = data[1] & 0xff;
            if (first < 192) {
                length = first;
                bodyStart = 2;
            } else if (first < 224) {
                length = ((first - 192) << 8) + (data[2] & 0xff) + 192;
                bodyStart = 3;
            } else if (first == 255) {
                length = ((data[2] & 0xff) << 24) | ((data[3] & 0xff) << 16) | ((data[4] & 0xff) << 8) | (data[5] & 0xff);
                bodyStart = 6;
            } else {
                throw new IllegalArgumentException("partial body length in a public-key packet");
            }
        } else if ((header & 0x80) == 0x80) { // old format: tag in bits 5-2, length type in bits 1-0
            tag = (header >> 2) & 0x0f;
            int lengthBytes = 1 << (header & 0x03);
            length = 0;
            for (int i = 0; i < lengthBytes; i++) {
                length = (length << 8) | (data[1 + i] & 0xff);
            }
            bodyStart = 1 + lengthBytes;
        } else {
            throw new IllegalArgumentException("not an OpenPGP packet");
        }
        assertEquals(6, tag, "the key block should start with a public-key packet (tag 6)");
        assertEquals(4, data[bodyStart], "only version 4 keys are handled here");
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update((byte) 0x99);
        sha1.update((byte) (length >> 8));
        sha1.update((byte) length);
        sha1.update(data, bodyStart, length);
        return HexFormat.of().withUpperCase().formatHex(sha1.digest());
    }

    /**
     * The binary inside ASCII armor: the base64 lines after the header lines and the blank line,
     * up to the checksum line (which starts with '=') or the END line.
     */
    private static byte[] dearmor(String armored) {
        ByteArrayOutputStream base64 = new ByteArrayOutputStream();
        boolean inBody = false;
        for (String line : armored.split("\\R")) {
            if (!inBody) {
                inBody = line.isBlank() && base64.size() == 0;
                continue;
            }
            if (line.startsWith("=") || line.startsWith("-----END")) {
                break;
            }
            base64.writeBytes(line.strip().getBytes(StandardCharsets.US_ASCII));
        }
        return Base64.getDecoder().decode(base64.toByteArray());
    }

    /** "A91F53D8..." to gpg's "A91F 53D8 ... D146  8618 ...": groups of four, two spaces halfway. */
    static String grouped(String hex) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 4) {
            if (i > 0) {
                out.append(i == 20 ? "  " : " ");
            }
            out.append(hex, i, i + 4);
        }
        return out.toString();
    }

    // ---- files ----

    private static String text(String relative) throws IOException {
        return new String(bytes(relative), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String relative) throws IOException {
        return Files.readAllBytes(repoFile(relative).toPath());
    }

    /** A file of this repository, found by walking up from the working directory. */
    private static File repoFile(String relative) {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, relative);
            if (candidate.isFile() && new File(dir, PLUGIN).isDirectory()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException(relative + " not found above " + System.getProperty("user.dir"));
    }
}
