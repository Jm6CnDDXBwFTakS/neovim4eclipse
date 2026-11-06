// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import common.PgpKey;

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
 * <p>The fingerprint is computed without gpg, by {@code releng/tools/common/PgpKey}, which
 * {@code checks/InstallCheckTool} uses too.
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
        String fingerprint = PgpKey.grouped(PgpKey.fingerprint(text(RELENG_KEY)));
        for (String doc : FINGERPRINT_DOCS) {
            assertTrue(text(doc).contains(fingerprint), doc + " should print the signing key's fingerprint " + fingerprint);
        }
    }

    @Test
    void groupsLikeGpg() {
        assertEquals("A91F 53D8 931B 7FD0 D146  8618 6CC9 E446 147B 063C",
                PgpKey.grouped("A91F53D8931B7FD0D14686186CC9E446147B063C"));
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
