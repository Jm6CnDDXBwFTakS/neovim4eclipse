// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package maven;

import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import common.ToolFailure;
import common.Version;

/**
 * What the Maven tool reads and writes, as pure functions the unit tests run without a network:
 * Maven Central's version lists, the archives' addresses, and the wrapper's settings file.
 *
 * <h2>The two things the tool moves</h2>
 * <ul>
 *   <li><b>Maven itself</b>, the build tool {@code ./mvnw} downloads and runs: its archive
 *       {@code apache-maven-3.9.16-bin.zip} is named by {@code distributionUrl} in
 *       {@code .mvn/wrapper/maven-wrapper.properties}, with {@code distributionSha256Sum}, which
 *       {@code ./mvnw} checks before it runs the downloaded Maven.</li>
 *   <li><b>The wrapper</b>, the scripts {@code mvnw} and {@code mvnw.cmd} that do that download;
 *       their version is {@code wrapperVersion} in the same file and the
 *       {@code maven-wrapper-plugin} that writes them has the same number (3.3.4).</li>
 * </ul>
 * Example of the file, which the tool lets the plug-in write and then checks line by line:
 * <pre>
 *   wrapperVersion=3.3.4
 *   distributionType=only-script
 *   distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
 *   distributionSha256Sum=5af3b743…
 * </pre>
 */
public final class MavenFiles {

    /** Maven Central, the one place both are read from. */
    public static final String CENTRAL = "https://repo.maven.apache.org/maven2/";

    /** Maven's folder on Central: {@code maven-metadata.xml} lists every version. */
    public static final String MAVEN = CENTRAL + "org/apache/maven/apache-maven/";

    /** The wrapper's scripts on Central, the files {@code maven-wrapper-plugin} unpacks. */
    public static final String WRAPPER = CENTRAL + "org/apache/maven/wrapper/maven-wrapper-distribution/";

    /** The wrapper's settings, from the repository's root. */
    public static final String PROPERTIES = ".mvn/wrapper/maven-wrapper.properties";

    /** The scripts the wrapper consists of, as in the {@code only-script} archive. */
    public static final List<String> SCRIPTS = List.of("mvnw", "mvnw.cmd");

    /** The plug-in that writes the wrapper: Maven's own way, {@code mvn wrapper:wrapper}. */
    public static final String PLUGIN = "org.apache.maven.plugins:maven-wrapper-plugin";

    /** A release younger than this is refused unless {@code --allow-young}, as the Neovim tool does. */
    public static final Duration MIN_AGE = Duration.ofDays(7);

    private static final Pattern VERSION_TAG = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");
    private static final Pattern MAVEN_IN_URL = Pattern.compile("/apache-maven/(\\d+\\.\\d+\\.\\d+)/apache-maven-\\1-bin\\.zip$");

    private MavenFiles() {
    }

    /**
     * The plain releases a {@code maven-metadata.xml} lists, oldest first: {@code 3.9.16} is one,
     * {@code 4.0.0-rc-7} and {@code 4.0.0-alpha-2} are not (Central's {@code <release>} element
     * names the newest of any kind, a candidate too, so it is not used).
     */
    public static List<Version> releases(String metadata) {
        List<Version> found = new ArrayList<>();
        Matcher m = VERSION_TAG.matcher(metadata);
        while (m.find()) {
            if (m.group(1).matches("\\d+\\.\\d+\\.\\d+")) {
                found.add(Version.parse(m.group(1)));
            }
        }
        if (found.isEmpty()) {
            throw new ToolFailure("Maven Central's version list names no release (no <version>x.y.z</version>)");
        }
        found.sort(null);
        return found;
    }

    /** The newest of {@code releases} on {@code line} ({@code 3.9}: 3.9.x only), or empty. */
    public static Optional<Version> newestOn(List<Version> releases, String line) {
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)").matcher(line.strip());
        if (!m.matches()) {
            throw new ToolFailure("maven.line should be a major and minor version such as 3.9, not " + line);
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        return releases.stream().filter(v -> v.major() == major && v.minor() == minor).max(Comparator.naturalOrder());
    }

    /** Whether {@code version} is on {@code line}: {@code 3.9.16} is on {@code 3.9}. */
    public static boolean isOn(Version version, String line) {
        return line.strip().equals(version.major() + "." + version.minor());
    }

    /** Maven's archive on Central: {@code …/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip}. */
    public static String mavenZip(Version v) {
        return MAVEN + v + "/apache-maven-" + v + "-bin.zip";
    }

    /** The wrapper's scripts on Central: {@code …/3.3.4/maven-wrapper-distribution-3.3.4-only-script.zip}. */
    public static String wrapperZip(Version v) {
        return WRAPPER + v + "/maven-wrapper-distribution-" + v + "-only-script.zip";
    }

    /** The wrapper's settings, the four keys {@code ./mvnw} reads. */
    public record WrapperSettings(Version wrapper, String type, String distributionUrl, String sha256) {

        /** The Maven version {@code distributionUrl} names. */
        public Version maven() {
            Matcher m = MAVEN_IN_URL.matcher(distributionUrl);
            if (!m.find()) {
                throw new ToolFailure(PROPERTIES + ": distributionUrl should end in /apache-maven/<v>/apache-maven-<v>-bin.zip: "
                        + distributionUrl);
            }
            return Version.parse(m.group(1));
        }
    }

    /** {@code .mvn/wrapper/maven-wrapper.properties}, read as {@code ./mvnw} reads it. */
    public static WrapperSettings settings(String properties) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(properties));
        } catch (IOException e) {
            throw new IllegalStateException(e); // a StringReader does not fail
        }
        for (String key : List.of("wrapperVersion", "distributionType", "distributionUrl", "distributionSha256Sum")) {
            if (p.getProperty(key, "").isBlank()) {
                throw new ToolFailure(PROPERTIES + " has no " + key + "=");
            }
        }
        return new WrapperSettings(Version.parse(p.getProperty("wrapperVersion").strip()), p.getProperty("distributionType").strip(),
                p.getProperty("distributionUrl").strip(), p.getProperty("distributionSha256Sum").strip());
    }

    /**
     * What the plug-in must have written, else the tool stops: exactly these four keys, with
     * Maven's archive on Central. The check matters because the plug-in takes the download address
     * from the machine's Maven settings: a mirror in {@code ~/.m2/settings.xml} would end up in
     * the file, and every other machine and CI would download Maven from that mirror.
     *
     * @return what differs, or empty
     */
    public static Optional<String> difference(String written, WrapperSettings expected) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(written));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<String> wrong = new ArrayList<>();
        expect(p, "wrapperVersion", expected.wrapper().toString(), wrong);
        expect(p, "distributionType", expected.type(), wrong);
        expect(p, "distributionUrl", expected.distributionUrl(), wrong);
        expect(p, "distributionSha256Sum", expected.sha256(), wrong);
        for (String key : p.stringPropertyNames()) {
            if (!List.of("wrapperVersion", "distributionType", "distributionUrl", "distributionSha256Sum").contains(key)) {
                wrong.add(key + " was added");
            }
        }
        return wrong.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", wrong));
    }

    private static void expect(Properties p, String key, String value, List<String> wrong) {
        String got = p.getProperty(key);
        if (got == null) {
            wrong.add(key + " is missing");
        } else if (!got.strip().equals(value)) {
            wrong.add(key + " is " + got.strip() + ", not " + value);
        }
    }

    /**
     * The arguments that run the plug-in, Maven's own way of setting up a wrapper, at exactly the
     * wrapper's version (no unpinned plug-in runs) on the root POM only ({@code -N}), with checksum
     * mismatches failing the download ({@code -C}) instead of only warning. Example:
     * <pre>
     *   -N -B -ntp -C org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dtype=only-script
     *     -Dmaven=3.9.16 -DdistributionSha256Sum=5af3b743…
     * </pre>
     */
    public static List<String> wrapperGoal(Version wrapper, Version maven, String sha256) {
        return List.of("-N", "-B", "-ntp", "-C", PLUGIN + ":" + wrapper + ":wrapper", "-Dtype=only-script", "-Dmaven=" + maven,
                "-DdistributionSha256Sum=" + sha256);
    }

    /** Whether something published at {@code published} is too young on {@code now} ({@link #MIN_AGE}). */
    public static boolean isYoung(Instant published, Instant now) {
        return Duration.between(published, now).compareTo(MIN_AGE) < 0;
    }
}
