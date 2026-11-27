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
import java.util.Optional;
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
 *   java releng/tools/ci/LikeCi.java --clean  first deletes the build output; goes with --all too
 *   java releng/tools/ci/LikeCi.java --text-stack   only prints this machine's fonts and text libraries
 * </pre>
 *
 * <p>{@code --text-stack} runs {@link #TEXT_STACK}, the command the Linux build job runs in its
 * step "Runner's text stack", and nothing else: the fonts {@code monospace} and
 * {@code sans-serif} resolve to, every font family installed, and the GTK, Pango, fontconfig,
 * FreeType and cairo versions. The UI tests measure drawn text, so a test that passes here and fails
 * on GitHub (or the other way round) often differs in exactly these; put the two prints side by
 * side. Not printed in a normal run: on a desktop the family list runs to hundreds of lines.
 *
 * <p>Without {@code --clean} a run builds on the {@code target/} folders already there, which is
 * faster but can test stale code: javac does not recompile a class whose source did not change,
 * so a caller of a changed method fails with {@code NoSuchMethodError}, and it keeps the old text
 * of a changed {@code static final String}. A runner always starts from a fresh checkout, so
 * {@code --clean} is also the closer copy of CI. Not while a runtime Eclipse started from the IDE
 * is open: {@code clean} deletes the plug-in's {@code lib/*.jar}, which that Eclipse uses.
 *
 * <h2>What it does, and why each step</h2>
 * <ol>
 *   <li><b>Lints the shell scripts</b> with {@link #SHELLCHECK}, as the build job does, if shellcheck
 *       is installed (it runs on Linux CI; the scripts run only there).</li>
 *   <li><b>Installs the pinned Neovim into {@code .tool-cache/like-ci/}</b> with
 *       {@code java releng/UpdateNeovimDownloads.java install auto}, the command CI uses: the release
 *       Get Neovim installs, checked. Not the developer's own dedicated Neovim, whose path the
 *       plug-in would treat differently; CI's Neovim is a program outside the plug-in's folder.
 *       Kept between runs while its files have the pinned tree hash, so only the first run (and the
 *       first after a Neovim upgrade) downloads it.</li>
 *   <li><b>Fetches the pinned plug-ins the tests run</b> ({@link #PLUGINS}) into
 *       {@code .tool-cache/test-plugins/}: each plug-in {@code releng/test-plugins.properties}
 *       lists, at the commit and archive hash it pins. A wrong hash fails the run.
 *       Without a network, or with a Java older than that tool needs, they are left out with a
 *       note and the tests that need them are skipped; the list of skips below names them. CI
 *       does not fetch them, so there those tests are skipped.</li>
 *   <li><b>With {@code --clean}, runs {@link #CLEAN}</b>, after the steps above so that a failed
 *       download leaves the last build in place. If it fails the run stops: tests on half-deleted
 *       output would say nothing.</li>
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
    /** The fetch of the plug-ins the tests run (docs/UPGRADING.md, "The plug-ins the tests run"). */
    public static final String PLUGINS = "java releng/tools/plugins/TestPlugins.java fetch";

    /** The build job's Maven arguments (the platforms job runs the same). */
    public static final String UI = "-B -ntp verify -Pui";

    /** The integration job's Maven arguments. */
    public static final String INTEGRATION = "-B -ntp test -f tests/unit/pom.xml -Pintegration";

    /** The Linux build job's virtual screen ({@code xvfb-run -s}), given rather than left to the script's default. */
    public static final String SCREEN = "-screen 0 1280x1024x24";

    /**
     * The Linux build job's step "Runner's text stack", word for word ({@code --text-stack}): which
     * font {@code monospace} and {@code sans-serif} become, every font family, and the versions of
     * the libraries that draw text. The whole list in braces with {@code || true}: a print for
     * comparing must never fail the Build, whatever a new image lacks ({@code fc-match} missing, a
     * package another Ubuntu names differently), and GitHub runs a step with {@code bash -e -o
     * pipefail}, where one failing command would end it; {@code -e} does not act inside a list
     * whose status {@code ||} tests. Debian and Ubuntu only ({@code dpkg-query}).
     */
    public static final String TEXT_STACK = "{ fc-match monospace; fc-match sans-serif; fc-list : family | sort -u;"
            + " dpkg-query -W -f='${Package} ${Version}\\n' 'libgtk-3-0*' 'libpango-1.0-0*' 'libfontconfig1*'"
            + " 'libfreetype6*' 'libcairo2*'; } || true";

    /** The build job's lint of the shell scripts (the glob as the shell expands it: every {@code releng/*.sh}). */
    public static final String SHELLCHECK = "shellcheck releng/*.sh mvnw";

    /** Every test group, for {@code --all}. */
    public static final String ALL = "-B -ntp verify -Pall";

    /** Deletes every module's build output, for {@code --clean}; not a CI command (a runner starts empty). */
    public static final String CLEAN = "-B -ntp clean";

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

    /**
     * Where the pinned Neovim is installed: {@code common.ToolCache.LIKE_CI}, copied here because
     * this file runs alone on JDK 21 and cannot import it ({@code ToolCacheTest} checks the copy).
     * Not under {@code target/}, so {@code ./mvnw clean} does not make the next run download again.
     */
    public static final String LIKE_CI = ".tool-cache/like-ci";

    private LikeCi() {
    }

    /**
     * What the command line asked for.
     *
     * @param all   run every test group ({@code --all})
     * @param clean delete the build output first ({@code --clean})
     */
    public record Options(boolean all, boolean clean) {

        /**
         * The options in {@code args}, in any order; empty for an unknown or repeated argument, so
         * that a typo such as {@code --cleam} stops the run instead of testing stale output.
         */
        public static Optional<Options> parse(String... args) {
            List<String> given = List.of(args);
            boolean all = given.contains("--all");
            boolean clean = given.contains("--clean");
            return given.size() == (all ? 1 : 0) + (clean ? 1 : 0) ? Optional.of(new Options(all, clean)) : Optional.empty();
        }
    }

    /**
     * Stops this program when started from cmd or PowerShell: a copy of
     * {@code common.Shell.requireSupportedShell}, since this file runs alone and cannot import it
     * ({@code ShellDisplayTest} checks the copy). The tools are supported on Linux, macOS, and in
     * Git Bash on Windows: both {@code MSYSTEM} set (Git Bash sets it) and the program that
     * started this one {@code bash.exe}, {@code sh.exe} or {@code java.exe}, or it stops.
     * CONTRIBUTING.md, "Developer tools: where they run".
     */
    private static void requireSupportedShell(String tool) {
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            return;
        }
        String msystem = System.getenv("MSYSTEM");
        String parent = ProcessHandle.current().parent().flatMap(p -> p.info().command())
                .map(c -> java.nio.file.Path.of(c).getFileName().toString().toLowerCase(java.util.Locale.ROOT)).orElse("");
        if (msystem == null || msystem.isBlank() || !java.util.List.of("bash.exe", "sh.exe", "java.exe").contains(parent)) {
            System.err.println(tool + ": runs on Linux, macOS and in Git Bash on Windows, not from cmd or PowerShell."
                    + " Run it from Git Bash.");
            System.exit(2);
        }
    }

    public static void main(String[] args) throws Exception {
        requireSupportedShell("LikeCi");
        if (args.length == 1 && args[0].equals("--text-stack")) {
            System.exit(printTextStack());
        }
        Optional<Options> parsed = Options.parse(args);
        if (parsed.isEmpty()) {
            System.err.println("Usage: java releng/tools/ci/LikeCi.java [--all] [--clean] | --text-stack");
            System.exit(2);
        }
        Options options = parsed.get();
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(root.resolve("releng/UpdateNeovimDownloads.java"))) {
            fail("run this from the repository's root folder");
        }
        List<String> failed = new ArrayList<>();
        if (lint(root) != 0) {
            failed.add(SHELLCHECK);
        }
        String nvim = installNeovim(root.resolve(LIKE_CI));
        if (fetchPlugins(root) != 0) {
            failed.add(PLUGINS);
        }
        if (options.clean() && run(root, CLEAN, nvim) != 0) {
            fail("failed: " + CLEAN);
        }
        long started = System.currentTimeMillis();
        for (String maven : options.all() ? List.of(ALL) : List.of(UI, INTEGRATION)) {
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
     * Runs {@link #TEXT_STACK} with {@code bash}, as the build job's step does (its workflow's default
     * shell); its exit status. Only where {@code fc-match} and {@code dpkg-query} are (Debian,
     * Ubuntu): elsewhere the list would compare with nothing on the runner.
     */
    private static int printTextStack() throws Exception {
        if (!onPath("fc-match") || !onPath("dpkg-query")) {
            System.err.println("LikeCi: --text-stack needs fc-match and dpkg-query (Debian or Ubuntu, as CI's runner)");
            return 2;
        }
        System.out.println("LikeCi: " + TEXT_STACK);
        return new ProcessBuilder("bash", "-c", TEXT_STACK).inheritIO().start().waitFor();
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

    /**
     * Runs {@link #PLUGINS}; its exit status, except that the status of a Java too old for that
     * tool (2, with the tool's own message) counts as done: the plug-ins are then not there, and
     * their tests are skipped, as without a network.
     */
    private static int fetchPlugins(Path root) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(List.of(PLUGINS.substring("java ".length()).split(" ")));
        System.out.println("LikeCi: " + PLUGINS);
        int status = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start().waitFor();
        return status == 2 ? 0 : status;
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
            // .\ as in a shell: cmd does not look in the current folder when
            // NoDefaultCurrentDirectoryInExePath is set, as some shells and tools set it (Claude
            // Code's does). Not root.resolve("mvnw.cmd"): cmd would quote it once the path has a space.
            command.addAll(List.of("cmd", "/c", ".\\mvnw.cmd"));
        } else {
            // As the Linux build job: the UI tests (and -Pall, which has them) under xvfb-run; the
            // integration job and clean open no window and need no display.
            boolean ui = arguments.equals(UI) || arguments.equals(ALL);
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

    /**
     * Whether {@code program} is on {@code PATH}, as the shell would find it: on Windows also with
     * each of {@code PATHEXT}'s extensions ({@code shellcheck} is {@code shellcheck.exe} there).
     * {@code releng/tools/common/Shell.java} has a copy (this file runs alone, on Java 21, and
     * cannot use it); a fix to one belongs in both.
     */
    private static boolean onPath(String program) {
        List<String> names = new ArrayList<>(List.of(program));
        if (WINDOWS) {
            for (String ext : env("PATHEXT", ".COM;.EXE;.BAT;.CMD").split(";")) {
                if (!ext.isBlank()) {
                    names.add(program + ext.toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String dir : env("PATH", "").split(File.pathSeparator)) {
            for (String name : names) {
                if (!dir.isEmpty() && Files.isRegularFile(Path.of(dir, name)) && Files.isExecutable(Path.of(dir, name))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The environment variable {@code name}, or {@code orElse} when it is not set; a copy of
     * {@code Shell.env}. By name, never from the map {@code System.getenv()} returns: Windows
     * usually spells the search path {@code Path}, which {@code System.getenv("PATH")} finds and
     * the map's {@code get("PATH")} does not.
     */
    private static String env(String name, String orElse) {
        String value = System.getenv(name);
        return value == null ? orElse : value;
    }

    private static final Pattern SUITE =Pattern.compile("<testsuite\\b[^>]*\\btests=\"(\\d+)\"[^>]*\\bskipped=\"(\\d+)\"");
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
