// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package marketplace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import common.EclipseRelease;
import common.PgpKey;
import common.Settings;
import common.ToolFailure;
import dependencies.PomVersions;

/**
 * The facts a listing names ({@code {neovim}}, {@code {java}}, ...), each read from its one source
 * of truth, the same source {@code VersionConsistencyTest} checks the docs against
 * ({@code releng/version-copies.txt} explains the rule). So the listing cannot say "Neovim 0.12.6"
 * once the plug-in needs 0.13.0.
 */
public final class Facts {

    static final String NEOVIM_PINS = "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/src/io/github/jm6cnddxbwftaks/neovim4eclipse/util/neovim-downloads.properties";
    static final String UPDATE_SITE_YML = ".github/workflows/update-site.yml";
    /** The release name at the end of {@code <eclipse-repo.url>}: {@code .../releases/2024-12}. */
    private static final Pattern REPO_RELEASE = Pattern.compile("/releases/(\\d{4}-\\d{2})/?$");

    private Facts() {
    }

    /** Every fact, by name, read from the checkout at {@code root}. */
    public static Map<String, String> read(Path root) {
        Map<String, String> pom = PomVersions.properties(text(root, "pom.xml"));
        String repo = require(pom, "eclipse-repo.url");
        Matcher m = REPO_RELEASE.matcher(repo.strip());
        if (!m.find()) {
            throw new ToolFailure("<eclipse-repo.url> should end in /releases/<release>, such as /releases/2024-12: " + repo);
        }
        EclipseRelease oldest = EclipseRelease.ofName(m.group(1));
        List<EclipseRelease.SmokeEntry> smoke = EclipseRelease.smokeEntries(text(root, UPDATE_SITE_YML));
        if (smoke.size() != 2) {
            throw new ToolFailure(UPDATE_SITE_YML + "'s smoke matrix should have two entries, the oldest and the newest Eclipse, not " + smoke.size());
        }
        EclipseRelease newest = EclipseRelease.ofNumber(smoke.get(1).eclipse());
        Properties pins = new Properties();
        try {
            pins.load(new java.io.StringReader(text(root, NEOVIM_PINS)));
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + NEOVIM_PINS + ": " + e.getMessage());
        }
        String keyFile = Settings.load(root.resolve(Settings.COMMON), Map.of()).get("signing.key");
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("neovim", requireProperty(pins.getProperty("version"), NEOVIM_PINS + " version="));
        facts.put("eclipse.oldest", oldest.name());
        facts.put("eclipse.oldest.number", oldest.number());
        facts.put("eclipse.newest", newest.name());
        facts.put("eclipse.newest.number", newest.number());
        facts.put("java", require(pom, "java.release"));
        facts.put("fingerprint", PgpKey.grouped(PgpKey.fingerprint(text(root, keyFile))));
        return facts;
    }

    private static String require(Map<String, String> pom, String name) {
        return requireProperty(pom.get(name), "<" + name + "> in pom.xml");
    }

    private static String requireProperty(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new ToolFailure("no " + what);
        }
        return value.strip();
    }

    static String text(Path root, String file) {
        try {
            return Files.readString(root.resolve(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + file + ": " + e.getMessage());
        }
    }
}
