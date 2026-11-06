// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;

/**
 * The one gate in front of every side effect a developer tool has: a push, a tag, a commit, a CI
 * run, a branch delete. Checks and local edits run freely; each of these asks first, showing what
 * it will do:
 * <pre>
 *   Push release/0.2.3 to origin?
 *     git push origin release/0.2.3
 *   [y/N]
 * </pre>
 * Only {@code y} or {@code yes} goes ahead; anything else, an empty line included, stops the tool.
 * Three ways to run, from the command line:
 * <ul>
 *   <li><b>asking</b> (the default): the question above, on the terminal.</li>
 *   <li><b>{@code --yes}</b>: no questions; each side effect is printed and done. For a step the
 *       maintainer has already approved, for example in chat with an AI that runs the tool.</li>
 *   <li><b>{@code --dry-run}</b>: each side effect is printed and skipped; checks still run.</li>
 * </ul>
 * With no terminal to ask on (an AI's shell, a script, input from a pipe) and no {@code --yes}, the
 * tool never waits for an answer that cannot come: it stops before the side effect and prints the
 * command that does it with {@code --yes} ({@link #decide}). Maintenance never needs AI help, and
 * an AI can still run the tools, one approved step at a time.
 */
public final class Ask {

    /** How a run treats side effects: see the class comment. */
    public enum Mode {
        ASK, YES, DRY_RUN
    }

    /** What to do with one side effect. */
    public enum Decision {
        /** Ask on the terminal. */
        PROMPT,
        /** Do it without asking ({@code --yes}). */
        DO,
        /** Print it and go on without doing it ({@code --dry-run}). */
        SKIP,
        /** Stop the tool: there is nobody to ask. */
        STOP
    }

    /**
     * One reader of standard input for every question. A {@link BufferedReader} reads ahead, so a
     * reader made per question would take answers typed ahead for later questions with it.
     */
    private static BufferedReader input;

    private final Mode mode;
    private final boolean terminal;
    private final String rerun;

    /**
     * @param rerun the command that repeats this run with {@code --yes}, printed when the tool has to
     *              stop for want of a terminal, e.g. {@code java releng/tools/release/Release.java promote --yes}
     */
    public Ask(Mode mode, String rerun) {
        this(mode, hasTerminal(), rerun);
    }

    Ask(Mode mode, boolean terminal, String rerun) {
        this.mode = mode;
        this.terminal = terminal;
        this.rerun = rerun;
    }

    public Mode mode() {
        return mode;
    }

    /** The decision for {@code mode}, with or without a terminal to ask on; {@code --dry-run} wins over everything. */
    public static Decision decide(Mode mode, boolean terminal) {
        return switch (mode) {
            case DRY_RUN -> Decision.SKIP;
            case YES -> Decision.DO;
            case ASK -> terminal ? Decision.PROMPT : Decision.STOP;
        };
    }

    /** Whether an answer is a yes: {@code y} or {@code yes}, in any case, with spaces around. */
    public static boolean isYes(String answer) {
        String a = answer == null ? "" : answer.strip().toLowerCase(Locale.ROOT);
        return a.equals("y") || a.equals("yes");
    }

    /**
     * Asks whether to do {@code what}, showing {@code details} (the commands, a diff) below it.
     * Returns {@code true} to go ahead, {@code false} in a dry run (the caller skips the side
     * effect); stops the tool with a {@link ToolFailure} on a no, or when there is nobody to ask.
     */
    public boolean confirm(String what, List<String> details) {
        Decision d = decide(mode, terminal);
        System.out.println();
        System.out.println((d == Decision.SKIP ? "[dry run] would: " : "") + what + (d == Decision.PROMPT ? "?" : ""));
        for (String line : details) {
            System.out.println("  " + line);
        }
        switch (d) {
            case SKIP:
                return false;
            case DO:
                return true;
            case STOP:
                throw new ToolFailure("no terminal to ask on, so nothing was done. To do it, run: " + rerun
                        + "  (or add --dry-run to see every step first)");
            default:
                System.out.print("[y/N] ");
                System.out.flush();
                String answer;
                try {
                    answer = input().readLine();
                } catch (IOException e) {
                    answer = null;
                }
                if (!isYes(answer)) {
                    throw new ToolFailure("stopped: not confirmed. Nothing after this point was done; run the step again to go on.");
                }
                return true;
        }
    }

    private static synchronized BufferedReader input() {
        if (input == null) {
            input = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
        }
        return input;
    }

    /**
     * Whether standard input and output are a terminal. Up to Java 21 {@link System#console} is
     * {@code null} otherwise; from Java 22 it always returns a console, and
     * {@code Console.isTerminal()} (22+, called by reflection so this compiles for 21) tells.
     */
    static boolean hasTerminal() {
        java.io.Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            Method isTerminal = java.io.Console.class.getMethod("isTerminal");
            return (Boolean) isTerminal.invoke(console);
        } catch (ReflectiveOperationException e) {
            return true; // Java 21: a console exists only for a terminal
        }
    }
}
