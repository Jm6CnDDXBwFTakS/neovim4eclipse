// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package ci;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs the tests the way GitHub's Build workflow ({@code .github/workflows/build.yml}) runs them, on
 * this machine, so a failure that only CI would see shows up before a push. The check to run before
 * pushing (AGENTS.md, CONTRIBUTING.md).
 *
 * <h2>Usage</h2>
 * <pre>
 *   java releng/tools/ci/LikeCi.java          what CI runs: -Pui, then the integration tests
 *   java releng/tools/ci/LikeCi.java --all    every test group (-Pall), what a release needs
 * </pre>
 *
 * <h2>What it does, and why each step</h2>
 * <ol>
 *   <li><b>Lints the shell scripts</b> with {@link #SHELLCHECK}, as the build job does, if shellcheck
 *       is installed (it runs on Linux CI; the scripts run only there).</li>
 *   <li><b>Installs the pinned Neovim into {@code target/like-ci/}</b> with
 *       {@code java releng/UpdateNeovimDownloads.java install auto}, the command CI uses: the release
 *       Get Neovim installs, checked. Not the developer's own dedicated Neovim, whose path the
 *       plug-in would treat differently; CI's Neovim is a program outside the plug-in's folder.</li>
 *   <li><b>Runs CI's Maven commands</b> ({@link #UI}, then {@link #INTEGRATION}) with
 *       {@code -Dnvim.path} set to it, from the repository's root, so everything is compiled first,
 *       as in CI (a change that does not compile fails here, not on GitHub). On Linux under
 *       {@code xvfb-run -a}, as the Linux job: the UI tests then get CI's virtual screen and no
 *       window manager, and the desktop stays free. The test modules run in folders of their own
 *       under {@code target/} (the root and UI test {@code pom.xml}), as on a runner.</li>
 *   <li><b>Prints what was skipped</b>, per module, from the test reports: a skip hides a test, and
 *       one that is skipped here but not in CI (or the other way round) is a difference to know.</li>
 * </ol>
 * {@code LikeCiTest} checks that {@code build.yml} still runs {@link #UI}, {@link #INTEGRATION} and
 * {@link #INSTALL}, so the two cannot drift apart unnoticed.
 *
 * <p>What it cannot copy: the runner's own image (its tools, fonts, screen and speed). On Windows
 * the Maven wrapper runs through {@code cmd} here, while CI runs it from Git Bash.
 */
public final class LikeCi {

    /** The Neovim install, as each CI job runs it (the folder follows). */
    public static final String INSTALL = "java releng/UpdateNeovimDownloads.java install auto";

    /** The build job's Maven arguments (the platforms job runs the same). */
    public static final String UI = "-B -ntp verify -Pui";

    /** The integration job's Maven arguments. */
    public static final String INTEGRATION = "-B -ntp test -f tests/unit/pom.xml -Pintegration";

    /** The Linux build job's virtual screen ({@code xvfb-run -s}), given rather than left to the script's default. */
    public static final String SCREEN = "-screen 0 1280x1024x24";

    /** The build job's lint of the shell scripts (the glob as the shell expands it: every {@code releng/*.sh}). */
    public static final String SHELLCHECK = "shellcheck releng/*.sh mvnw";

    /** Every test group, for {@code --all}. */
    public static final String ALL = "-B -ntp verify -Pall";

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

    private LikeCi() {
    }

    public static void main(String[] args) throws Exception {
        boolean all = List.of(args).contains("--all");
        if (args.length > (all ? 1 : 0)) {
            System.err.println("Usage: java releng/tools/ci/LikeCi.java [--all]");
            System.exit(2);
        }
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(root.resolve("releng/UpdateNeovimDownloads.java"))) {
            fail("run this from the repository's root folder");
        }
        List<String> failed = new ArrayList<>();
        if (lint(root) != 0) {
            failed.add(SHELLCHECK);
        }
        String nvim = installNeovim(root.resolve("target/like-ci"));
        long started = System.currentTimeMillis();
        for (String maven : all ? List.of(ALL) : List.of(UI, INTEGRATION)) {
            if (run(root, maven, nvim) != 0) {
                failed.add(maven);
            }
        }
        printSkips(root, started);
        if (!failed.isEmpty()) {
            fail("failed: " + String.join("; ", failed));
        }
        System.out.println("LikeCi: everything passed, as CI would run it.");
    }

    /**
     * Runs {@link #SHELLCHECK} as the build job does, if shellcheck is installed (CI has it; on
     * Windows and macOS the scripts are not run, so it is not needed there); its exit status.
     */
    private static int lint(Path root) throws Exception {
        if (!onPath("shellcheck")) {
            System.out.println("LikeCi: no shellcheck here, so the shell scripts are not linted (CI lints them)");
            return 0;
        }
        List<String> command = new ArrayList<>(List.of("shellcheck"));
        try (Stream<Path> files = Files.list(root.resolve("releng"))) {
            files.map(f -> "releng/" + f.getFileName()).filter(n -> n.endsWith(".sh")).sorted().forEach(command::add);
        }
        command.add("mvnw");
        System.out.println("LikeCi: " + String.join(" ", command));
        return new ProcessBuilder(command).directory(root.toFile()).inheritIO().start().waitFor();
    }

    /** Runs {@link #INSTALL}; the program's path it prints. */
    private static String installNeovim(Path folder) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(List.of(INSTALL.substring("java ".length()).split(" ")));
        command.add(folder.toString());
        Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        if (p.waitFor() != 0 || out.isEmpty()) {
            fail("the Neovim install failed");
        }
        return out.lines().reduce((a, b) -> b).orElseThrow(); // the last line: the program's path
    }

    /** Runs the Maven wrapper with {@code arguments} and {@code -Dnvim.path}; its exit status. */
    private static int run(Path root, String arguments, String nvim) throws Exception {
        List<String> command = new ArrayList<>();
        if (WINDOWS) {
            command.addAll(List.of("cmd", "/c", "mvnw.cmd"));
        } else {
            // As the Linux build job: the UI tests (and -Pall, which has them) under xvfb-run; the
            // integration job has no display and needs none.
            boolean ui = !arguments.equals(INTEGRATION);
            if (ui && System.getProperty("os.name").startsWith("Linux") && onPath("xvfb-run")) {
                command.addAll(List.of("xvfb-run", "-a", "-s", SCREEN));
            } else if (ui && System.getProperty("os.name").startsWith("Linux")) {
                System.err.println("LikeCi: no xvfb-run, so the UI tests use this desktop (CI uses xvfb-run)");
            }
            command.add("./mvnw");
        }
        command.addAll(List.of(arguments.split(" ")));
        command.add("-Dnvim.path=" + nvim);
        System.out.println("LikeCi: " + String.join(" ", command));
        return new ProcessBuilder(command).directory(root.toFile()).inheritIO().start().waitFor();
    }

    private static boolean onPath(String program) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, program))) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern SUITE = Pattern.compile("<testsuite\\b[^>]*\\btests=\"(\\d+)\"[^>]*\\bskipped=\"(\\d+)\"");
    private static final Pattern SKIPPED = Pattern.compile("<testcase\\b[^>]*\\bname=\"([^\"]*)\"[^>]*\\bclassname=\"([^\"]*)\"[^>]*>\\s*<skipped");

    /**
     * One line per test module: tests run and skipped, and the skipped tests' names (at most 20).
     * Only reports written by this run: a module's folder keeps those of earlier runs (another test
     * group, a class since renamed) until {@code clean}.
     */
    private static void printSkips(Path root, long started) throws IOException {
        for (String module : List.of("tests/unit", "tests/text", "tests/swt", "tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests")) {
            Path reports = root.resolve(module).resolve("target/surefire-reports");
            if (!Files.isDirectory(reports)) {
                continue;
            }
            int tests = 0;
            List<String> skipped = new ArrayList<>();
            try (Stream<Path> files = Files.list(reports)) {
                for (Path report : files.filter(f -> f.getFileName().toString().matches("TEST-.*\\.xml"))
                        .filter(f -> f.toFile().lastModified() >= started).toList()) {
                    String xml = Files.readString(report);
                    Matcher suite = SUITE.matcher(xml);
                    if (suite.find()) {
                        tests += Integer.parseInt(suite.group(1));
                    }
                    Matcher skip = SKIPPED.matcher(xml);
                    while (skip.find()) {
                        String className = skip.group(2);
                        skipped.add(className.substring(className.lastIndexOf('.') + 1) + "." + skip.group(1));
                    }
                }
            }
            System.out.println("LikeCi: " + module + ": " + tests + " tests, " + skipped.size() + " skipped"
                    + (skipped.isEmpty() ? "" : ": " + String.join(", ", skipped.subList(0, Math.min(20, skipped.size())))
                            + (skipped.size() > 20 ? ", ..." : "")));
        }
    }

    private static void fail(String message) {
        System.err.println("LikeCi: " + message);
        System.exit(1);
    }
}
