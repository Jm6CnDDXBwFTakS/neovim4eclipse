// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package eclipse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import common.EclipseRelease;
import common.ToolFailure;

/**
 * The Eclipse tool's reading and editing of text, as plain functions ({@code EclipseUpgradeTest}
 * in {@code tests/unit} checks them): what download.eclipse.org publishes about a release, and
 * the edits to {@code pom.xml} and the changelog. The downloads and the installs only run
 * programs ({@link Platform}, {@link Smoke}), and are tried by running the tool. The Release
 * tool's {@code check} reads {@link #newestDrop} too, for its line about a newer Eclipse.
 *
 * <h2>Where download.eclipse.org says what</h2>
 * <ul>
 *   <li>{@value #DATA_JSON}: the newest Platform releases, newest first; only the last three, so
 *       it serves {@code newest} ({@link #newestDrop}).</li>
 *   <li>{@code https://download.eclipse.org/eclipse/updates/4.34/}: the p2 repository of every
 *       4.x release; its page names the release's drop, {@code R-4.34-202411201800}
 *       ({@link #dropIn}), which serves {@code oldest}.</li>
 *   <li>{@code …/eclipse/downloads/drops4/<drop>/}: the downloads and a checksums file, one line
 *       per file ({@link #sha512In}).</li>
 * </ul>
 */
public final class EclipseFiles {

    /** The Platform releases, newest first, as the downloads page lists them. */
    public static final String DATA_JSON = "https://download.eclipse.org/eclipse/downloads/data.json";

    /** Where the drops are: {@code DROPS + "R-4.41-202608281142/"}. */
    static final String DROPS = "https://download.eclipse.org/eclipse/downloads/drops4/";

    /** The p2 repository of one Platform release, whose page names its drop: {@code UPDATES + "4.34/"}. */
    static final String UPDATES = "https://download.eclipse.org/eclipse/updates/";

    /** A release's drop folder: {@code R-4.41-202608281142}, the Platform number then when it was built. */
    private static final Pattern DROP = Pattern.compile("R-(4\\.\\d+)-(\\d{4})(\\d{2})\\d{6}");

    /**
     * The jars {@code tests/text} and {@code tests/swt} take from Maven Central, by the property in
     * the root {@code pom.xml} that holds each one's version and the bundle it is. They must be the
     * versions the oldest supported release has, the ones the plug-in is built against.
     */
    static final Map<String, String> TEST_JARS = jars();

    private static Map<String, String> jars() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("eclipse.text.version", "org.eclipse.text");
        m.put("eclipse.equinox.common.version", "org.eclipse.equinox.common");
        m.put("eclipse.core.commands.version", "org.eclipse.core.commands");
        m.put("eclipse.swt.version", "org.eclipse.swt");
        m.put("eclipse.jface.version", "org.eclipse.jface");
        m.put("eclipse.jface.text.version", "org.eclipse.jface.text");
        m.put("eclipse.core.runtime.version", "org.eclipse.core.runtime");
        m.put("eclipse.osgi.version", "org.eclipse.osgi");
        return java.util.Collections.unmodifiableMap(m);
    }

    private EclipseFiles() {
    }

    // ------------------------------------------------------------ what download.eclipse.org says

    /**
     * The first entry of {@code data.json}'s {@code "releases"}, the newest Platform release: its
     * drop folder, for example {@code R-4.41-202608281142} from
     * {@code {"path": "drops4/R-4.41-202608281142", "label": "4.41", …}}.
     */
    public static String newestDrop(String dataJson) {
        Matcher list = Pattern.compile("\"releases\"\\s*:\\s*\\[\\s*\\{([^}]*)\\}").matcher(dataJson);
        if (!list.find()) {
            throw new ToolFailure(DATA_JSON + " lists no releases");
        }
        Matcher path = Pattern.compile("\"path\"\\s*:\\s*\"drops4/(" + DROP.pattern() + ")\"").matcher(list.group(1));
        if (!path.find()) {
            throw new ToolFailure(DATA_JSON + ": the newest release has no path drops4/R-4.x-<time>: {" + list.group(1) + "}");
        }
        return path.group(1);
    }

    /**
     * The drop of {@code release} that the page of its p2 repository names: the newest one if it
     * names several (a respin). Example: {@code R-4.34-202411201800} for 4.34.
     */
    static String dropIn(String updatesPage, EclipseRelease release) {
        TreeSet<String> found = new TreeSet<>();
        Matcher m = DROP.matcher(updatesPage);
        while (m.find()) {
            if (m.group(1).equals(release.number())) {
                found.add(m.group());
            }
        }
        if (found.isEmpty()) {
            throw new ToolFailure(UPDATES + release.number() + "/ names no drop R-" + release.number() + "-<time>; is "
                    + release + " released?");
        }
        return found.last();
    }

    /** The release a drop is of: {@code R-4.41-202608281142} is 4.41, 2026-09. */
    public static EclipseRelease releaseOf(String drop) {
        Matcher m = DROP.matcher(drop);
        if (!m.matches()) {
            throw new ToolFailure("not a release drop R-4.x-<time>: " + drop);
        }
        return EclipseRelease.ofNumber(m.group(1));
    }

    /**
     * Checks that a drop was built within two months of its release's month, which is what holds
     * the name-to-number count in {@link EclipseRelease} true. The Platform is built a few weeks
     * before the release date, a respin can come a few weeks after it, and releases are three
     * months apart, so a drop built further away belongs to another quarter. Example:
     * {@code R-4.41-202608281142} (August 2026) is 2026-09; if Eclipse ever skipped a quarter, 4.42
     * would be built in March, not November, and this stops the tool instead of writing a wrong
     * name into the docs.
     */
    static void checkDropDate(String drop) {
        Matcher m = DROP.matcher(drop);
        if (!m.matches()) {
            throw new ToolFailure("not a release drop R-4.x-<time>: " + drop);
        }
        EclipseRelease r = EclipseRelease.ofNumber(m.group(1));
        int built = Integer.parseInt(m.group(2)) * 12 + Integer.parseInt(m.group(3));
        int due = r.year() * 12 + r.month();
        if (Math.abs(built - due) > 2) {
            throw new ToolFailure(drop + " was built in " + m.group(2) + "-" + m.group(3) + ", but " + r.number()
                    + " should be " + r.name() + " by the count in common/EclipseRelease: has Eclipse's release cycle changed?");
        }
    }

    /** The checksums file of a drop, by its current name; drops before about 2025 have {@link #oldChecksums}. */
    static String checksums(String drop) {
        return DROPS + drop + "/eclipse-" + releaseOf(drop).number() + "-checksums";
    }

    /** The checksums file of an older drop: {@code checksum/eclipse-4.34-SUMSSHA512}. */
    static String oldChecksums(String drop) {
        return DROPS + drop + "/checksum/eclipse-" + releaseOf(drop).number() + "-SUMSSHA512";
    }

    /**
     * The SHA-512 of {@code file} in a checksums file, whose lines are {@code <hex> *<file>} (or
     * with two spaces, as {@code sha512sum} writes them).
     */
    static Optional<String> sha512In(String checksums, String file) {
        for (String line : checksums.lines().toList()) {
            Matcher m = Pattern.compile("^([0-9a-fA-F]{128}) [ *](\\S+)$").matcher(line.strip());
            if (m.matches() && m.group(2).equals(file)) {
                return Optional.of(m.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------ the Platform download

    /**
     * The Eclipse Platform download for an operating system and processor, as each drop names
     * it. Examples for 4.41: {@code eclipse-platform-4.41-linux-gtk-x86_64.tar.gz} (the one CI's
     * smoke job installs into), {@code eclipse-platform-4.41-macosx-cocoa-aarch64.dmg},
     * {@code eclipse-platform-4.41-win32-win32-x86_64.zip}.
     */
    static String platformFile(EclipseRelease release, String os, String arch) {
        String n = release.number();
        return switch (os) {
            case "linux" -> "eclipse-platform-" + n + "-linux-gtk-" + arch + ".tar.gz";
            case "macos" -> "eclipse-platform-" + n + "-macosx-cocoa-" + arch + ".dmg";
            case "windows" -> "eclipse-platform-" + n + "-win32-win32-" + arch + ".zip";
            default -> throw new ToolFailure("no Eclipse Platform download for the operating system " + os);
        };
    }

    /** The download CI's smoke job installs into: Linux, x86_64 (GitHub's ubuntu-latest). */
    static String ciPlatformFile(EclipseRelease release) {
        return platformFile(release, "linux", "x86_64");
    }

    /** This machine's operating system as {@link #platformFile} names it, from {@code os.name}. */
    static String os(String osName) {
        String n = osName.toLowerCase(Locale.ROOT);
        return n.startsWith("windows") ? "windows" : n.startsWith("mac") ? "macos" : n.startsWith("linux") ? "linux" : n;
    }

    /** This machine's processor as {@link #platformFile} names it, from {@code os.arch}: {@code amd64} is x86_64, {@code arm64} aarch64. */
    static String arch(String osArch) {
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> osArch;
        };
    }

    // ------------------------------------------------------------ what the Platform holds

    /**
     * The Java release an Eclipse needs, from {@code -Dosgi.requiredJavaVersion=21} in its
     * {@code eclipse.ini}; empty if the file does not say.
     */
    static OptionalInt requiredJava(String eclipseIni) {
        Matcher m = Pattern.compile("(?m)^-Dosgi\\.requiredJavaVersion=(\\d+)").matcher(eclipseIni);
        return m.find() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }

    /**
     * The {@link #TEST_JARS} properties and the version each bundle has in a Platform's
     * {@code plugins/} folder, whose entries are {@code <bundle>_<major>.<minor>.<micro>.<qualifier>}
     * jars or folders: {@code org.eclipse.text_3.14.200.v20240915-0736.jar} gives
     * {@code eclipse.text.version} 3.14.200. Maven Central publishes them without the qualifier.
     * The per-platform SWT fragment ({@code org.eclipse.swt.gtk.linux.x86_64_3.128.0…}) is a
     * bundle of another name, so the host's {@code org.eclipse.swt} is the one read.
     */
    static Map<String, String> testJarVersions(List<String> pluginEntries) {
        Map<String, String> versions = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> jar : TEST_JARS.entrySet()) {
            Pattern p = Pattern.compile(Pattern.quote(jar.getValue()) + "_(\\d+\\.\\d+\\.\\d+)(?:\\.[^/]*)?(?:\\.jar)?");
            TreeSet<String> found = new TreeSet<>();
            for (String entry : pluginEntries) {
                Matcher m = p.matcher(entry);
                if (m.matches()) {
                    found.add(m.group(1));
                }
            }
            if (found.size() != 1) {
                missing.add(jar.getValue() + (found.isEmpty() ? " (none)" : " (" + found + ")"));
            } else {
                versions.put(jar.getKey(), found.first());
            }
        }
        if (!missing.isEmpty()) {
            throw new ToolFailure("the Platform's plugins/ folder should hold one version of each test jar: " + missing);
        }
        return versions;
    }

    /**
     * A Java's feature release from its {@code release} file: {@code JAVA_VERSION="21.0.5"} gives
     * 21, {@code JAVA_VERSION="1.8.0_412"} 8.
     */
    static int javaRelease(String releaseFile) {
        Matcher m = Pattern.compile("(?m)^JAVA_VERSION=\"(?:1\\.)?(\\d+)").matcher(releaseFile);
        if (!m.find()) {
            throw new ToolFailure("the Java's release file has no JAVA_VERSION=\"…\"");
        }
        return Integer.parseInt(m.group(1));
    }

    // ------------------------------------------------------------ the edits

    /** {@code pom} with property {@code name} set to {@code value}: {@code <name>value</name>}, once. */
    static String withProperty(String pom, String name, String value) {
        Pattern p = Pattern.compile("(<" + Pattern.quote(name) + ">)[^<]*(</" + Pattern.quote(name) + ">)");
        long count = p.matcher(pom).results().count();
        if (count != 1) {
            throw new ToolFailure("pom.xml should have <" + name + "> once, not " + count + " times");
        }
        Matcher m = p.matcher(pom);
        m.find();
        return pom.substring(0, m.start()) + m.group(1) + value + m.group(2) + pom.substring(m.end());
    }

    /** The p2 repository of a simultaneous release, which the plug-in is built against: {@code …/releases/2024-12}. */
    static String repoUrl(EclipseRelease release) {
        return "https://download.eclipse.org/releases/" + release.name();
    }

    /** How the {@code newest} entry starts, to find an earlier one before the release. */
    static final Pattern NEWEST_ENTRY = Pattern.compile("^- Each release is now also installed into Eclipse ");

    /** How the {@code oldest} entry starts. */
    static final Pattern OLDEST_ENTRY = Pattern.compile("^- Needs Eclipse ");

    /** The changelog entry for a newest tested release, without its "- ". */
    static String newestEntry(EclipseRelease to) {
        return "Each release is now also installed into Eclipse " + to + " before it is published, the newest Eclipse it is tested with.";
    }

    /** The changelog entry for a new oldest supported release, without its "- ". */
    static String oldestEntry(EclipseRelease was, EclipseRelease to) {
        return "Needs Eclipse " + to + " or newer (it was " + was + "). An older Eclipse no longer installs updates of the plug-in.";
    }

    /** What an earlier {@link #oldestEntry} names as "it was", if it does: users still have that release's floor. */
    static Optional<EclipseRelease> wasIn(String entry) {
        Matcher m = Pattern.compile("\\(it was (20\\d\\d-\\d\\d) \\(4\\.\\d+\\)\\)").matcher(entry);
        return m.find() ? Optional.of(EclipseRelease.ofName(m.group(1))) : Optional.empty();
    }
}
