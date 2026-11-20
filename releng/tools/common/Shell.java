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
     * What a tool says after its name when it is started from cmd or PowerShell. The single-file
     * programs ({@code ci/LikeCi.java}, {@code checks/Proxy.java}, {@code neovim/ArchiveCheck.java}),
     * which cannot import this class, hold a copy; {@code ShellDisplayTest} checks each.
     */
    public static final String WHERE_TOOLS_RUN = ": runs on Linux, macOS and in Git Bash on Windows, not from cmd or PowerShell."
            + " Run it from Git Bash.";

    /**
     * Stops a tool started from cmd or PowerShell, with a message saying where to run it instead.
     * Every program under {@code releng/tools} calls it, or its copy, first in its {@code main}.
     *
     * <p><b>Why.</b> The tools are supported on Linux, macOS, and in Git Bash on Windows
     * (CONTRIBUTING.md, "Developer tools: where they run"). A tool prints commands to paste back
     * (its {@code --yes} line) in the quoting of a POSIX shell, which cmd and PowerShell read
     * differently: {@code '} is no quote in cmd, and PowerShell runs {@code $(...)} even inside
     * double quotes. Refusing those two shells is one check here, where quoting for them would be a
     * rule for every word of every printed line.
     */
    public static void requireSupportedShell(String tool) {
        String parent = ProcessHandle.current().parent().flatMap(p -> p.info().command())
                .map(c -> Path.of(c).getFileName().toString()).orElse("");
        refusal(tool, WINDOWS, System.getenv("MSYSTEM"), parent).ifPresent(message -> {
            System.err.println(message);
            System.exit(2);
        });
    }

    /**
     * The message {@link #requireSupportedShell} stops with, or empty where the tool may run.
     * {@code parent} is the file name of the program that started this one ({@code bash.exe},
     * {@code cmd.exe}), or "" when Windows does not say.
     *
     * <p>On Windows a tool runs only when both of two signs say Git Bash, and neither alone is
     * enough: {@code MSYSTEM} is set (Git for Windows' shell sets it, {@code MINGW64} or
     * {@code UCRT64}, for every program it starts; cmd and PowerShell do not), and the program that
     * started it is one of a known set ({@link #GIT_BASH_PARENTS}): Git Bash's {@code bash.exe} or
     * {@code sh.exe}, or {@code java.exe}, for a tool another tool starts. Anything else is
     * refused, a parent Windows does not name included: a cmd or PowerShell opened from Git Bash
     * inherits {@code MSYSTEM}, and a list of the shells to refuse would miss one it does not
     * know. Examples: {@code refusal("Release", true, "MINGW64", "bash.exe")} and
     * {@code refusal("Release", false, null, "")} are empty; {@code refusal("Release", true, null,
     * "bash.exe")}, {@code refusal("Release", true, "MINGW64", "pwsh.exe")} and
     * {@code refusal("Release", true, "MINGW64", "")} are the message.
     */
    public static Optional<String> refusal(String tool, boolean windows, String msystem, String parent) {
        if (!windows) {
            return Optional.empty();
        }
        boolean gitBash = msystem != null && !msystem.isBlank();
        boolean knownParent = GIT_BASH_PARENTS.contains(parent.toLowerCase(Locale.ROOT));
        return gitBash && knownParent ? Optional.empty() : Optional.of(tool + WHERE_TOOLS_RUN);
    }

    /** The programs that may start a tool on Windows: Git Bash's shells, and Java for a tool another tool starts. */
    public static final List<String> GIT_BASH_PARENTS = List.of("bash.exe", "sh.exe", "java.exe");

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
            putEnv(pb.environment(), env);
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
            throw new ToolFailure("interrupted while running " + ran(command));
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
            throw new ToolFailure("interrupted while running " + ran(command));
        }
    }

    /**
     * Puts {@code extra} into a child's environment, each under the name it already has there
     * ({@link #nameIn}). The tools set a child's variable through this, never with a plain
     * {@code environment().put}: with {@code Xdg_Data_Home} set in a Windows console, a put of
     * {@code XDG_DATA_HOME} would give the child both.
     */
    public static void putEnv(Map<String, String> environment, Map<String, String> extra) {
        for (Map.Entry<String, String> e : extra.entrySet()) {
            environment.put(nameIn(environment, e.getKey(), WINDOWS), e.getValue());
        }
    }

    /**
     * The name the variable {@code name} has in a child's {@code environment}. On Windows the
     * spelling already there in another case: Windows spells {@code PATH} as {@code Path}, and a
     * second variable {@code PATH} beside it would not replace it. Elsewhere, and for a variable
     * that is not there yet, {@code name} itself: Linux and macOS tell {@code path} from
     * {@code PATH}, two variables.
     *
     * @param windows whether Windows' rule for names applies
     */
    public static String nameIn(Map<String, String> environment, String name, boolean windows) {
        if (!windows || environment.containsKey(name)) {
            return name;
        }
        return environment.keySet().stream().filter(k -> k.equalsIgnoreCase(name)).findFirst().orElse(name);
    }

    /** Runs {@code command} on this terminal (its output shows as it comes); its exit status. */
    public int show(List<String> command) {
        return show(command, Map.of());
    }

    /**
     * {@link #show(List)} with extra environment variables. Example: {@code JAVA_HOME} for a test
     * run on another JDK than this tool's, which {@code ./mvnw} then builds with.
     */
    public int show(List<String> command, Map<String, String> env) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command(command)).directory(root.toFile()).inheritIO();
            putEnv(pb.environment(), env);
            return pb.start().waitFor();
        } catch (IOException e) {
            System.err.println(command.get(0) + ": " + e.getMessage());
            return 127;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("interrupted while running " + ran(command));
        }
    }

    /** {@link #capture} that must succeed: its output, or a {@link ToolFailure} with what the program said. */
    public String out(List<String> command) {
        Result r = capture(command);
        if (!r.ok()) {
            throw new ToolFailure(ran(command) + " failed (" + r.status() + "): " + (r.err().isEmpty() ? r.out() : r.err()));
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

    /** How {@link #maven} starts the wrapper on Windows; {@link #display} shows it as {@code ./mvnw}. */
    static final List<String> WINDOWS_MAVEN = List.of("cmd", "/c", ".\\mvnw.cmd");

    /**
     * The command that runs the Maven wrapper. On Windows {@code cmd /c .\mvnw.cmd}: {@code .\} as
     * in a shell, since cmd does not look in the current folder when
     * {@code NoDefaultCurrentDirectoryInExePath} is set (as in {@code releng/tools/ci/LikeCi.java}).
     */
    public static List<String> maven(String... args) {
        List<String> command = new ArrayList<>(WINDOWS ? WINDOWS_MAVEN : List.of("./mvnw"));
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
            for (String ext : env("PATHEXT", ".COM;.EXE;.BAT;.CMD").split(";")) {
                if (!ext.isBlank()) {
                    names.add(program + ext.toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String dir : env("PATH", "").split(File.pathSeparator)) {
            for (String name : names) {
                if (!dir.isEmpty() && Files.isRegularFile(Path.of(dir, name)) && Files.isExecutable(Path.of(dir, name))) {
                    return Optional.of(Path.of(dir, name).toAbsolutePath());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The environment variable {@code name}, or {@code orElse} when it is not set. The tools read
     * every variable through this (or {@code System.getenv(name)}), never from the map
     * {@code System.getenv()} returns: Windows ignores the case of a variable's name and usually
     * spells the search path {@code Path}, {@code System.getenv("PATH")} finds it, and the map's
     * {@code get("PATH")} does not (its keys are compared exactly). Example: in a console opened
     * from the Start menu, {@code System.getenv().getOrDefault("PATH", "")} is empty, and no
     * program is "on PATH". {@code ToolEnvironmentTest} keeps the map out of the tools.
     */
    public static String env(String name, String orElse) {
        String value = System.getenv(name);
        return value == null ? orElse : value;
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

    /**
     * What {@link #display} prints in place of a word it cannot show: one with a control character
     * (a line break, a tab, an escape), which printed raw would make a pasted line run as two
     * commands or move the terminal's cursor, or with an invisible formatting character (a
     * right-to-left override, a zero-width space), which would make the word look like another.
     *
     * <p>The marker is a redirection of input from a file of that name, which does not exist: pasted
     * back, the shell stops with "No such file or directory" before it starts the command, so
     * nothing runs with a word left out, whatever the tool does with its arguments.
     */
    public static final String WORD_NOT_SHOWN = "<'(a word with a control or invisible character, not shown)'";

    /** A word that may be printed without quotes: only characters no POSIX shell reads. */
    private static final java.util.regex.Pattern PLAIN = java.util.regex.Pattern.compile("[A-Za-z0-9_./:@%+,-][A-Za-z0-9_./:@%+,=-]*");

    /**
     * A character {@link #display} does not show: a control character ({@code \\p{Cc}}), a line or
     * paragraph separator, which some terminals break the line at, or a formatting character
     * ({@code \\p{Cf}}: the direction overrides U+202A-202E and U+2066-2069, zero-width U+200B-200D).
     */
    private static final java.util.regex.Pattern CONTROL = java.util.regex.Pattern.compile("[\\p{Cc}\\p{Cf}\\x{2028}\\x{2029}]");

    /**
     * A command as a person would type it in a POSIX shell (sh, bash, zsh, Git Bash), so that a
     * printed rerun line (a tool's {@code --yes} hint) can be pasted back as it is. The tools run
     * only from such a shell ({@link #requireSupportedShell}).
     *
     * <p>A word stays as it is only when it is made of plain characters ({@link #PLAIN}: letters
     * and digits of ASCII, {@code _ . / : @ % + , -}, and {@code =} after the first character);
     * every other word goes in single quotes, inside which no shell reads anything, and a single
     * quote in it becomes {@code '\''}. An allow-list, not a list of the characters a shell reads:
     * a character it forgets is quoted, never run. Examples: {@code --java '/opt/my jdk'},
     * {@code 'HEAD~1'} ({@code ~} would expand at the start of a word), {@code ''} for an empty
     * word. The first word is quoted when it holds an {@code =}, which would make it a variable
     * assignment ({@code a=b echo hi}). A word with a control or invisible character is replaced
     * by {@link #WORD_NOT_SHOWN}. {@link #maven}'s Windows start, {@code cmd /c
     * .\mvnw.cmd}, is shown as {@code ./mvnw}, which Git Bash runs.
     */
    public static String display(List<String> command) {
        if (command.size() >= 3 && command.subList(0, 3).equals(WINDOWS_MAVEN)) {
            // Shell.maven's Windows form, shown as Git Bash takes it: there MSYS rewrites /c to C:/
            // for cmd, which then opens a prompt instead of running the build, and .\ loses its
            // backslash; ./mvnw runs the same wrapper.
            List<String> shown = new ArrayList<>(List.of("./mvnw"));
            shown.addAll(command.subList(3, command.size()));
            return ran(shown);
        }
        return ran(command);
    }

    /**
     * A command as it ran, for a failure message: quoted as {@link #display} quotes, but word for
     * word, so that {@code cmd /c .\mvnw.cmd verify} failed is said as {@code cmd /c '.\mvnw.cmd'
     * verify}, not as the {@code ./mvnw} a person would paste. Example:
     * {@code git push origin release/0.2.31 failed} names exactly what was started.
     */
    public static String ran(List<String> command) {
        List<String> words = new ArrayList<>();
        for (String w : command) {
            if (CONTROL.matcher(w).find()) {
                words.add(WORD_NOT_SHOWN);
            } else if (PLAIN.matcher(w).matches() && !(words.isEmpty() && w.contains("="))) {
                words.add(w);
            } else {
                words.add("'" + w.replace("'", "'\\''") + "'");
            }
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
