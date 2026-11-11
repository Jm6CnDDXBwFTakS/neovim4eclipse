// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package dependencies;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import common.Http;
import common.Settings;
import common.Shell;
import common.ToolFailure;

/**
 * The checks a library the plug-in embeds needs before it moves (docs/UPGRADING.md, "Embedded
 * library"). The plug-in copies msgpack-core's jar into {@code lib/} and ships it, so its license
 * files ship too, in {@code plugins/.../licenses/}; a new version may change them, or bring a
 * dependency of its own that nothing copies. Before the move the tool checks, and stops on
 * anything new:
 * <ol>
 *   <li>the new jar (from Central, against its SHA-1) carries no license file of its own
 *       (msgpack-core's carries none: its {@code NOTICE} comes from the source repository);</li>
 *   <li>each license file the settings name, fetched from the library's source at the new
 *       version's tag, is the same as its copy in {@code licenses/};</li>
 *   <li>the new version's POM brings no run-time dependency the old one did not.</li>
 * </ol>
 * The settings, in {@code releng/tools/dependencies/settings.properties}, per artifact id:
 * <pre>
 *   embedded=org.msgpack:msgpack-core
 *   embedded.msgpack-core.source=https://raw.githubusercontent.com/msgpack/msgpack-java/v{version}/
 *   embedded.msgpack-core.licenses=NOTICE=msgpack-java-NOTICE.txt LICENSE.txt=Apache-2.0.txt
 * </pre>
 */
final class Embedded {

    /** Where the license files that ship with the plug-in are. */
    static final String LICENSES = "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/licenses";

    private Embedded() {
    }

    /** The artifacts the settings name as embedded. */
    static List<String> artifacts(Settings settings) {
        List<String> out = new ArrayList<>();
        for (String a : settings.get("embedded", "").split("[,\\s]+")) {
            if (!a.isEmpty()) {
                out.add(a);
            }
        }
        return out;
    }

    /** {@code NOTICE=msgpack-java-NOTICE.txt LICENSE.txt=Apache-2.0.txt} as source file to copy in {@code licenses/}. */
    static Map<String, String> licenseFiles(String setting) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : setting.strip().split("\\s+")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] p = pair.split("=", -1);
            if (p.length != 2 || !p[0].matches("[\\w.-]+") || !p[1].matches("[\\w.-]+")) {
                throw new ToolFailure("embedded.*.licenses should be source=copy pairs such as NOTICE=msgpack-java-NOTICE.txt: " + pair);
            }
            out.put(p[0], p[1]);
        }
        return out;
    }

    /** The entries of a jar that look like license files: {@code META-INF/LICENSE}, {@code NOTICE.txt}. */
    static List<String> licenseEntries(List<String> entries) {
        return entries.stream().filter(e -> {
            String name = e.substring(e.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
            return name.startsWith("license") || name.startsWith("licence") || name.startsWith("notice");
        }).toList();
    }

    /** Whether two license texts say the same, line endings and trailing blank lines aside. */
    static boolean sameText(String a, String b) {
        return normal(a).equals(normal(b));
    }

    private static String normal(String text) {
        return text.replace("\r\n", "\n").stripTrailing();
    }

    /**
     * Runs the three checks for {@code artifact} moving from {@code from} to {@code to}; stops the
     * tool with what to do on a difference. {@code work} holds the downloads.
     */
    static void check(Path root, Shell shell, Settings settings, PomVersions.Artifact artifact, String from, String to, Path work) {
        String key = "embedded." + artifact.id();
        String jarUrl = artifact.jar(to);
        Path jar = Http.download(jarUrl, new Http.Digest("SHA-1", Http.digestIn(Http.text(jarUrl + ".sha1"), 40)), work.resolve("downloads"));
        List<String> inJar = licenseEntries(entries(jar));
        if (!inJar.isEmpty()) {
            throw new ToolFailure(artifact + " " + to + " now carries " + inJar + " in its jar: compare them with " + LICENSES
                    + "/ and update it and about.html by hand (docs/UPGRADING.md, \"Embedded library\"), then move it by hand");
        }
        System.out.println("ok: " + jar.getFileName() + " carries no license file of its own");

        String source = settings.get(key + ".source").replace("{version}", to);
        if (!source.startsWith("https://")) {
            throw new ToolFailure(key + ".source should be an https:// address: " + source);
        }
        for (Map.Entry<String, String> f : licenseFiles(settings.get(key + ".licenses")).entrySet()) {
            String upstream = Http.text(source + f.getKey());
            Path copy = root.resolve(LICENSES).resolve(f.getValue());
            String ours;
            try {
                ours = Files.readString(copy);
            } catch (IOException e) {
                throw new ToolFailure("cannot read " + LICENSES + "/" + f.getValue() + ": " + e.getMessage());
            }
            if (!sameText(upstream, ours)) {
                Path fetched = work.resolve(artifact.id() + "-" + to + "-" + f.getKey());
                try {
                    Files.createDirectories(work);
                    Files.writeString(fetched, upstream, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new ToolFailure("cannot write " + fetched + ": " + e.getMessage());
                }
                shell.show(List.of("git", "--no-pager", "diff", "--no-index", "--", LICENSES + "/" + f.getValue(), root.relativize(fetched).toString()));
                throw new ToolFailure(f.getKey() + " of " + artifact.id() + " " + to + " differs from " + LICENSES + "/" + f.getValue()
                        + " (the diff is above): update the copy (and about.html if it says more), commit that, then run the tool again");
            }
            System.out.println("ok: " + f.getKey() + " at " + to + " is the same as " + LICENSES + "/" + f.getValue());
        }

        List<PomVersions.Artifact> before = PomVersions.runtimeDependencies(Http.text(artifact.pom(from)), artifact + " " + from);
        List<PomVersions.Artifact> after = PomVersions.runtimeDependencies(Http.text(artifact.pom(to)), artifact + " " + to);
        List<PomVersions.Artifact> added = after.stream().filter(a -> !before.contains(a)).toList();
        if (!added.isEmpty()) {
            throw new ToolFailure(artifact + " " + to + " needs " + added + " at run time, which the plug-in does not copy into lib/:"
                    + " add it by hand (docs/UPGRADING.md, \"Embedded library\", adding a library)");
        }
        System.out.println("ok: " + artifact.id() + " " + to + " brings no new run-time dependency");
    }

    private static List<String> entries(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream().map(ZipEntry::getName).toList();
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + jar + " as a jar: " + e.getMessage());
        }
    }
}
