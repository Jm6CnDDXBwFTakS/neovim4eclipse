// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package eclipse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import common.EclipseCopy;
import common.Shell;
import common.ToolFailure;

/**
 * The smoke install: the plug-in, as this checkout builds it, installed into an unpacked Eclipse
 * Platform with p2's <i>director</i>, the way <i>Help &gt; Install New Software</i> installs it
 * but without a window. It is what CI's {@code smoke} job does with {@code releng/smoke-install.sh}
 * after each deployment, written in Java so that it also runs on macOS and Windows without bash.
 *
 * <p>Only the locally built update site is given to p2
 * ({@code releng/…updatesite/target/repository}), nothing from download.eclipse.org: if the
 * plug-in needed anything that Eclipse lacks (a bundle newer than it has, a Java it does not run
 * on), the install fails. Example of what it runs on Linux:
 * <pre>
 *   eclipse/eclipse -vm /usr/lib/jvm/java-21/bin/java --launcher.suppressErrors -nosplash
 *     -application org.eclipse.equinox.p2.director
 *     -repository file:///…/updatesite/target/repository/ -installIU io.github…feature.feature.group -data @none
 * </pre>
 * then {@code -listInstalledRoots}, which must name the feature at this checkout's version.
 */
final class Smoke {

    /** Where Maven leaves the update site it builds, from the repository's root. */
    static final String SITE = "releng/io.github.jm6cnddxbwftaks.neovim4eclipse.updatesite/target/repository";

    /** The p2 director's application, run inside the unpacked Eclipse. */
    private static final List<String> DIRECTOR = List.of("--launcher.suppressErrors", "-nosplash", "-application",
            "org.eclipse.equinox.p2.director");

    private Smoke() {
    }

    /**
     * The {@code -vm} an Eclipse launcher takes for a Java home: {@code bin\server\jvm.dll} on
     * Windows, {@code bin/java} on Linux and macOS (on macOS the home is {@code …/Contents/Home}).
     * On Windows the JVM's library runs inside {@code eclipsec.exe}, whose console the director's
     * output then reaches; {@code javaw.exe} would be a process of its own, without a console.
     * A Platform download brings no Java, and without {@code -vm} the launcher takes whatever Java
     * it finds first, which may be one the Eclipse does not start on (4.34 refuses Java 24+).
     */
    static String vm(Path javaHome, String os) {
        return switch (os) {
            case "windows" -> javaHome.resolve("bin").resolve("server").resolve("jvm.dll").toString();
            default -> javaHome.resolve("bin").resolve("java").toString();
        };
    }

    /**
     * Installs the built site into {@code eclipse} with Java {@code javaHome}; {@code version} is
     * this checkout's ({@code 0.2.10}, without {@code -SNAPSHOT}), which p2 has with a qualifier
     * ({@code 0.2.10.v20261110-0812}). The director's whole output goes to {@code log}.
     */
    static void install(Path root, Platform.Installation eclipse, Path javaHome, String os, String version, Path log) {
        Path site = root.resolve(SITE);
        if (!Files.isRegularFile(site.resolve("content.jar")) && !Files.isRegularFile(site.resolve("content.xml"))) {
            throw new ToolFailure("no update site at " + site + "; build it first: ./mvnw verify -DskipTests");
        }
        Path director = EclipseCopy.director(eclipse.program());
        List<String> start = List.of(director.toString(), "-vm", vm(javaHome, os));
        try {
            Files.createDirectories(log.getParent());
            List<String> install = new ArrayList<>(start);
            install.addAll(DIRECTOR);
            install.addAll(List.of("-repository", site.toUri().toString(), "-installIU", EclipseCopy.FEATURE, "-data", "@none"));
            System.out.println("Installing into " + eclipse.folder() + " with p2's director (output in " + log + ")");
            String output = EclipseCopy.run(install, log, Map.of("JAVA_HOME", javaHome.toString()));
            if (!output.contains("Operation completed")) {
                throw new ToolFailure("the install into " + eclipse.folder() + " failed:\n" + tail(output) + "\n(all of it in " + log + ")");
            }
            List<String> list = new ArrayList<>(start);
            list.addAll(DIRECTOR);
            list.addAll(List.of("-listInstalledRoots", "-data", "@none"));
            Optional<String> installed = EclipseCopy.installedVersion(EclipseCopy.run(list, log, Map.of("JAVA_HOME", javaHome.toString())));
            if (installed.isEmpty() || !isVersion(installed.get(), version)) {
                throw new ToolFailure("after the install " + eclipse.folder() + " has "
                        + installed.map(v -> "version " + v).orElse("no plug-in") + ", not " + version + " (" + log + ")");
            }
            System.out.println("ok: " + EclipseCopy.FEATURE + " " + installed.get() + " installs into " + eclipse.folder().getFileName()
                    + " (" + eclipse.folder() + ")");
        } catch (IOException e) {
            throw new ToolFailure("cannot run the director: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted");
        }
    }

    /** Whether p2's version is {@code version}: {@code 0.2.10}, or {@code 0.2.10.<qualifier>}. */
    static boolean isVersion(String p2Version, String version) {
        return p2Version.equals(version) || p2Version.startsWith(version + ".");
    }

    /** The last lines of the director's output, where it says what went wrong. */
    private static String tail(String output) {
        List<String> lines = output.lines().filter(l -> !l.isBlank()).toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 15), lines.size()));
    }

    /** The Maven command that builds the site {@link #install} installs: the whole reactor, no tests. */
    static List<String> buildSite() {
        return Shell.maven("-q", "-B", "-ntp", "verify", "-DskipTests");
    }
}
