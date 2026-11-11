// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs other programs (git, gh, the Maven wrapper, the {@code releng} scripts) from the repository's
 * root, the same way on Linux, macOS and Windows. Two forms:
 * <ul>
 *   <li>{@link #capture}: for a question, such as {@code git rev-parse HEAD}; the output is kept and
 *       returned, nothing is shown.</li>
 *   <li>{@link #show}: for work a person should watch, such as a test run or {@code gh run watch};
 *       the program writes straight to this terminal.</li>
 * </ul>
 * Each command is a list of words, never one string for a shell, so a path with a space or a
 * version read from a file cannot turn into a second command.
 */
public final class Shell {

    /** What a captured command did: its exit status and what it wrote, without trailing line breaks (leading spaces are kept: {@code git diff --stat} aligns with them). */
    public record Result(int status, String out, String err) {
        public boolean ok() {
            return status == 0;
        }
    }

    public static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

    private final Path root;

    public Shell(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /**
     * {@code command}, checked for what Windows cannot pass: there Java joins the words into one
     * command line, quoting a word with a space but not escaping a double quote inside it, and the
     * program then takes that quote away. {@code --jq '"\(.a)\t\(.b)"'} reaches {@code gh} as
     * {@code \(.a)\t\(.b)}, a {@code git commit -m} message loses its quotes. Such a word stops
     * the tool on Windows instead of running changed; elsewhere it is passed on as it is. Write
     * jq filters with {@code @tsv} instead (common/GitHub).
     */
    public static List<String> command(List<String> command) {
        return command(command, WINDOWS);
    }

    /** {@link #command(List)} as on Windows or not, for the tests. */
    public static List<String> command(List<String> command, boolean windows) {
        if (windows) {
            for (String word : command) {
                if (word.indexOf('"') >= 0) {
                    throw new ToolFailure("on Windows, " + command.get(0) + " would get this argument without its double quotes: "
                            + word + " (Java does not escape them; see common/Shell)");
                }
            }
        }
        return command;
    }

    /** Runs {@code command} and returns what it wrote; a missing program counts as status 127, as in a shell. */
    public Result capture(List<String> command) {
        return capture(command, Map.of());
    }

    /** {@link #capture(List)} with extra environment variables, such as {@code TZ=UTC} for a commit. */
    public Result capture(List<String> command, Map<String, String> env) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command(command)).directory(root.toFile());
            pb.environment().putAll(env);
            Process p = pb.start();
            p.getOutputStream().close();
            // Both streams at once: a program that fills one pipe while the other is read would block.
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
            String out = read(p.getInputStream());
            return new Result(p.waitFor(), out.stripTrailing(), err.join().strip());
        } catch (IOException e) {
            return new Result(127, "", command.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted while running " + String.join(" ", command));
        }
    }

    /**
     * {@link #capture(List)} that gives up after {@code limit}: the program is stopped and the
     * result has status 124, as {@code timeout(1)} reports it. For a question that should take
     * moments, where a hung program (a lock, a credential prompt) must not hang the caller.
     */
    public Result capture(List<String> command, Duration limit) {
        try {
            Process p = new ProcessBuilder(command(command)).directory(root.toFile()).start();
            p.getOutputStream().close();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
            if (!p.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new Result(124, "", command.get(0) + ": no answer within " + limit.toSeconds() + " s");
            }
            return new Result(p.exitValue(), out.join().stripTrailing(), err.join().strip());
        } catch (IOException e) {
            return new Result(127, "", command.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted while running " + String.join(" ", command));
        }
    }

    /** Runs {@code command} on this terminal (its output shows as it comes); its exit status. */
    public int show(List<String> command) {
        try {
            return new ProcessBuilder(command(command)).directory(root.toFile()).inheritIO().start().waitFor();
        } catch (IOException e) {
            System.err.println(command.get(0) + ": " + e.getMessage());
            return 127;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted while running " + String.join(" ", command));
        }
    }

    /** {@link #capture} that must succeed: its output, or a {@link ToolFailure} with what the program said. */
    public String out(List<String> command) {
        Result r = capture(command);
        if (!r.ok()) {
            throw new ToolFailure(String.join(" ", command) + " failed (" + r.status() + "): " + (r.err().isEmpty() ? r.out() : r.err()));
        }
        return r.out();
    }

    /** The command that runs a Java source file with the JDK this tool runs on (not whatever {@code java} is on PATH). */
    public static List<String> java(String sourceFile, String... args) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add(sourceFile);
        command.addAll(List.of(args));
        return command;
    }

    /**
     * The command that runs the Maven wrapper. On Windows {@code cmd /c .\mvnw.cmd}: {@code .\} as
     * in a shell, since cmd does not look in the current folder when
     * {@code NoDefaultCurrentDirectoryInExePath} is set (as in {@code releng/tools/ci/LikeCi.java}).
     */
    public static List<String> maven(String... args) {
        List<String> command = new ArrayList<>(WINDOWS ? List.of("cmd", "/c", ".\\mvnw.cmd") : List.of("./mvnw"));
        command.addAll(List.of(args));
        return command;
    }

    /**
     * Whether {@code program} is on {@code PATH}, as the shell would find it: on Windows also with
     * each of {@code PATHEXT}'s extensions ({@code gpg} is {@code gpg.exe} there).
     *
     * <p>{@code releng/tools/ci/LikeCi.java} has a copy of this and of {@link #maven}, on purpose:
     * it is a single source file that runs on Java 21, which cannot load this class (see
     * {@code release/Release.java}). A fix to one belongs in both; once the tools need Java 22
     * anyway, LikeCi can use this class instead.
     */
    public static boolean onPath(String program) {
        return which(program).isPresent();
    }

    /** The file {@link #onPath} finds for {@code program}, or empty. */
    public static Optional<Path> which(String program) {
        List<String> names = new ArrayList<>(List.of(program));
        if (WINDOWS) {
            for (String ext : System.getenv().getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD").split(";")) {
                if (!ext.isBlank()) {
                    names.add(program + ext.toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            for (String name : names) {
                if (!dir.isEmpty() && Files.isRegularFile(Path.of(dir, name)) && Files.isExecutable(Path.of(dir, name))) {
                    return Optional.of(Path.of(dir, name).toAbsolutePath());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The bash that runs the {@code releng} scripts, as an absolute path, or empty when there is
     * none. On Linux and macOS the first {@code bash} on {@code PATH}. On Windows Git for Windows'
     * own ({@code <Git>\bin\bash.exe}, found from {@code git --exec-path}), never a plain
     * {@code bash}: Windows looks in {@code System32} before {@code PATH}, and with WSL installed
     * that is WSL's {@code bash.exe}, which starts Linux and cannot read the Windows paths the
     * scripts are given ({@code C:\...\neovim4eclipse-0.2.3.zip}).
     */
    public Optional<Path> bash() {
        if (!WINDOWS) {
            return which("bash");
        }
        Result exec = capture(List.of("git", "--exec-path")); // e.g. C:/Program Files/Git/mingw64/libexec/git-core
        if (!exec.ok() || exec.out().isBlank()) {
            return Optional.empty();
        }
        // git-core -> libexec -> mingw64 -> the Git folder
        for (Path dir = Path.of(exec.out().strip()); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("bin").resolve("bash.exe");
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** A command as a person would type it: words with a space or quote in single quotes. */
    public static String display(List<String> command) {
        List<String> words = new ArrayList<>();
        for (String w : command) {
            words.add(w.isEmpty() || w.matches(".*[\\s'\"$`\\\\*?;&|<>()].*") ? "'" + w.replace("'", "'\\''") + "'" : w);
        }
        return String.join(" ", words);
    }

    private static String read(java.io.InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
