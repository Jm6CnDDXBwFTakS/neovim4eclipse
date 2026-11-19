// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package javarelease;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import common.ToolFailure;

/**
 * The Java tool's rules and text edits, as plain functions, so that {@code tests/unit} checks them
 * without a JDK to switch to or a checkout to change ({@code JavaUpgradeTest}).
 *
 * <h2>The Java release</h2>
 * {@code <java.release>21</java.release>} in the root {@code pom.xml} is the <b>minimum</b> Java
 * the plug-in runs on: {@code javac --release 21} makes class files a Java 21 loads, and OSGi
 * refuses to install a bundle whose {@code Bundle-RequiredExecutionEnvironment} (JavaSE-21) the
 * running Java does not provide. Its copies are listed in {@code releng/version-copies.txt},
 * section {@code [java.release]}.
 */
public final class JavaFiles {

    /** The property in the root {@code pom.xml}; the section of {@code releng/version-copies.txt} with its copies. */
    public static final String PROPERTY = "java.release";

    /** The first long-term-support release from which the LTS releases come every four (17, 21, 25, 29, ...). */
    private static final int FIRST_EVERY_FOURTH_LTS = 17;

    private static final Pattern POM_RELEASE = Pattern.compile("<java\\.release>(\\d+)</java\\.release>");
    /** {@code JAVA_VERSION="25.0.1"} in a JDK's {@code release} file; the feature release is the first number. */
    private static final Pattern RELEASE_FILE = Pattern.compile("(?m)^JAVA_VERSION=\"(\\d+)[^\"]*\"");
    /** The entry {@link #changelogEntry} writes, found again when a second raise before a release replaces it. */
    public static final Pattern ENTRY = Pattern.compile("^- \\*\\*Java \\d+ or newer\\*\\* is now required");

    private JavaFiles() {
    }

    /**
     * Whether {@code release} is a long-term-support release: Oracle and the OpenJDK builds
     * (Temurin among them) give those years of updates, the others six months. Since 17 every
     * fourth release is one: 17, 21, 25, 29. Eclipse raises its own minimum only to an LTS, and
     * a plug-in that needs a short-lived Java would lock out users when its updates stop.
     */
    public static boolean isLts(int release) {
        return release >= FIRST_EVERY_FOURTH_LTS && (release - FIRST_EVERY_FOURTH_LTS) % 4 == 0;
    }

    /** The release {@code <java.release>} names; fails unless there is exactly one. */
    public static int releaseIn(String pom) {
        Matcher m = POM_RELEASE.matcher(pom);
        if (!m.find()) {
            throw new ToolFailure("pom.xml has no <java.release>");
        }
        int release = Integer.parseInt(m.group(1));
        if (m.find()) {
            throw new ToolFailure("pom.xml has more than one <java.release>");
        }
        return release;
    }

    /** {@code pom} with {@code <java.release>} set to {@code release}; the rest as it was. */
    public static String withRelease(String pom, int release) {
        releaseIn(pom); // exactly one, or a clear failure
        return POM_RELEASE.matcher(pom).replaceFirst("<java.release>" + release + "</java.release>");
    }

    /**
     * The feature release of a JDK, from its home's {@code release} file. Example:
     * {@code JAVA_VERSION="25.0.1"} is 25; empty when the file has no such line.
     */
    public static OptionalInt featureRelease(String releaseFile) {
        Matcher m = RELEASE_FILE.matcher(releaseFile);
        return m.find() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }

    /**
     * The version a raise to {@code to} may go to, from {@code from} (today's): a long-term-support
     * release ({@link #isLts}) higher than today's. The tool never lowers it: a plug-in built for
     * Java 25 may use Java 25's APIs, which a lower release would lack.
     */
    public static void checkTarget(int from, int to) {
        if (to <= from) {
            throw new ToolFailure("Java " + to + " is not higher than " + from + ", the Java release now; the tool only raises it");
        }
        if (!isLts(to)) {
            throw new ToolFailure("Java " + to + " is not a long-term-support release (17, 21, 25, 29, ...): its updates stop after six"
                    + " months, and users whose Eclipse runs on it would be stuck there");
        }
    }

    /**
     * The changelog entry for the raise: what users notice is that an Eclipse on an older Java
     * cannot install the plug-in any more, and what p2 says then.
     */
    public static String changelogEntry(int to) {
        return "**Java " + to + " or newer** is now required. An Eclipse running on an older Java refuses to install the"
                + " plug-in (\"missing requirement: osgi.ee\").";
    }
}
