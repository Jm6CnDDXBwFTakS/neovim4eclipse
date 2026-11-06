// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The fingerprint of an OpenPGP public key, without gpg: the release signing key's
 * ({@code releng/signing-key.asc}), which {@code checks/InstallCheckTool} tells p2's director to
 * trust and {@code ReleaseSigningKeyTest} finds in the docs. Computing it from the key file keeps
 * that file the one source: no tool holds a copy of the 40 digits that could go stale when the
 * key is replaced.
 *
 * <p>OpenPGP defines a version 4 key's fingerprint (RFC 4880, section 12.2) as the SHA-1 of the
 * byte 0x99, the public-key packet's body length as two bytes, and the body. Example:
 * {@code gpg --show-keys releng/signing-key.asc} prints the same 40 hex digits,
 * {@code A91F53D8931B7FD0D14686186CC9E446147B063C}.
 */
public final class PgpKey {

    private PgpKey() {
    }

    /** The fingerprint of the first (primary) key in an armored public key block: 40 upper-case hex digits. */
    public static String fingerprint(String armored) {
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
        if (tag != 6) {
            throw new IllegalArgumentException("the key block should start with a public-key packet (tag 6), not tag " + tag);
        }
        if (data[bodyStart] != 4) {
            throw new IllegalArgumentException("only version 4 keys are handled, not version " + data[bodyStart]);
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update((byte) 0x99);
            sha1.update((byte) (length >> 8));
            sha1.update((byte) length);
            sha1.update(data, bodyStart, length);
            return HexFormat.of().withUpperCase().formatHex(sha1.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-1", e);
        }
    }

    /** "A91F53D8..." to gpg's "A91F 53D8 ... D146  8618 ...": groups of four, two spaces halfway. */
    public static String grouped(String hex) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 4) {
            if (i > 0) {
                out.append(i == 20 ? "  " : " ");
            }
            out.append(hex, i, i + 4);
        }
        return out.toString();
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
}
