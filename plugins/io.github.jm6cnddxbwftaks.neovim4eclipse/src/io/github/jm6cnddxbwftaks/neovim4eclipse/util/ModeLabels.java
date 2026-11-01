// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.Locale;

/**
 * What the status bar's mode label says for a Neovim mode string ({@code nvim_get_mode().mode}),
 * and its tooltip. Example for a visual block ({@code Ctrl-V}, mode {@code ^V}): the label
 * {@code V-BLOCK}, the tooltip {@code -- VISUAL BLOCK --}.
 *
 * <h2>Why these texts</h2>
 * The labels are the names most Neovim statusline plug-ins use (lualine's: {@code V-LINE},
 * {@code S-BLOCK}, {@code O-PENDING}, {@code V-REPLACE}), so a Neovim user reads them at a glance.
 * They are short on purpose: the label is always as wide as its widest possible text
 * ({@link #texts}), so that it keeps its size while the mode changes, and Vim's own wording made the
 * rarest state, {@code -- (select) VISUAL BLOCK --}, set the width for all of them. Now the widest are
 * {@code (V-REPLACE)} and {@code (S) V-BLOCK}, 11 characters; 18 while a macro records
 * ({@code (S) V-BLOCK rec @a}).
 *
 * <p>The tooltip keeps Vim's own {@code 'showmode'} wording where Vim has one
 * ({@code -- (insert) --} after {@code Ctrl-O} in Insert mode, {@code -- VREPLACE --}), so nothing is
 * lost by the short label. Vim shows nothing in Normal mode, on the command line, in Ex mode and
 * while a shell command runs; the tooltip names those in the same form ({@code -- NORMAL --}).
 *
 * <p>Parentheses follow Vim's rule: they name the mode that comes back after one command.
 * {@code (INSERT)} is Normal mode for one command, then Insert again ({@code Ctrl-O} in Insert);
 * {@code (S) V-BLOCK} is Visual block for one command, then Select block again ({@code Ctrl-O} in
 * Select block). Lualine shows plain {@code V-BLOCK} there; the bar uses Vim's form. The labels and the parentheses were decided then, replacing the three styles (Full,
 * Short, Letters) of the 0.2.0 snapshots: see docs/design/STATUS_BAR.md.
 *
 * <p>Neovim reports a mode more finely than these labels: all four operator-pending forms
 * ({@code no}, {@code nov}, {@code noV}, {@code no^V}) are {@code O-PENDING}, and the completion forms
 * of Insert ({@code ic}, {@code ix}) are Insert's. Two states Vim names cannot be told apart from the
 * mode string: Visual or Select entered with {@code Ctrl-O} from Insert mode report plain {@code v}
 * or {@code s}, so they show as plain Visual or Select (not worth tracking the
 * mode before them).
 *
 * <p>Pure Java; all mode strings are interpreted here or in {@link VimModes}.
 */
public final class ModeLabels {

    /**
     * The family a label belongs to, which picks its color ({@code editor/ModeStatusContribution}):
     * a property of the state, so the color never depends on how its text is spelled.
     */
    public enum Group {
        /** Keys go to Eclipse. */
        ECLIPSE,
        /** Visual mode, from Select too. */
        VISUAL,
        /** Insert, Replace, and Normal for one command from them ({@code (INSERT)}). */
        INSERT,
        /** Waiting for the rest of a command: an operator, the command line, Ex mode. */
        PENDING,
        /** The rest: Normal, Select, prompts, the shell, the terminal. */
        OTHER
    }

    /**
     * One state's texts.
     *
     * @param text  the label ({@code V-BLOCK})
     * @param vim   the tooltip: Vim's {@code 'showmode'} wording ({@code -- VISUAL BLOCK --})
     * @param group the state's family, for the label's color
     */
    public record Label(String text, String vim, Group group) {
    }

    /** Keys go to Eclipse, not Neovim (an editor the plug-in does not drive, or Neovim stopped). */
    public static final Label ECLIPSE = new Label("ECLIPSE", "Keys go to Eclipse, not Neovim", Group.ECLIPSE);

    // The table, in the order of docs/design/STATUS_BAR.md. Ctrl-V and Ctrl-S are written \u0016 and \u0013.
    private static final Label NORMAL = new Label("NORMAL", "-- NORMAL --", Group.OTHER);
    private static final Label OPERATOR = new Label("O-PENDING", "-- OPERATOR PENDING --", Group.PENDING);
    private static final Label CTRL_O_INSERT = new Label("(INSERT)", "-- (insert) --", Group.INSERT);
    private static final Label CTRL_O_REPLACE = new Label("(REPLACE)", "-- (replace) --", Group.INSERT);
    private static final Label CTRL_O_VREPLACE = new Label("(V-REPLACE)", "-- (vreplace) --", Group.INSERT);
    private static final Label INSERT = new Label("INSERT", "-- INSERT --", Group.INSERT);
    private static final Label REPLACE = new Label("REPLACE", "-- REPLACE --", Group.INSERT);
    private static final Label VREPLACE = new Label("V-REPLACE", "-- VREPLACE --", Group.INSERT);
    private static final Label VISUAL = new Label("VISUAL", "-- VISUAL --", Group.VISUAL);
    private static final Label VISUAL_LINE = new Label("V-LINE", "-- VISUAL LINE --", Group.VISUAL);
    private static final Label VISUAL_BLOCK = new Label("V-BLOCK", "-- VISUAL BLOCK --", Group.VISUAL);
    private static final Label SELECT_VISUAL = new Label("(S) VISUAL", "-- (select) VISUAL --", Group.VISUAL);
    private static final Label SELECT_VISUAL_LINE = new Label("(S) V-LINE", "-- (select) VISUAL LINE --", Group.VISUAL);
    private static final Label SELECT_VISUAL_BLOCK = new Label("(S) V-BLOCK", "-- (select) VISUAL BLOCK --", Group.VISUAL);
    private static final Label SELECT = new Label("SELECT", "-- SELECT --", Group.OTHER);
    private static final Label SELECT_LINE = new Label("S-LINE", "-- SELECT LINE --", Group.OTHER);
    private static final Label SELECT_BLOCK = new Label("S-BLOCK", "-- SELECT BLOCK --", Group.OTHER);
    private static final Label COMMAND = new Label("COMMAND", "-- COMMAND --", Group.PENDING);
    private static final Label EX = new Label("EX", "-- EX --", Group.PENDING);
    private static final Label MORE = new Label("MORE", "-- More --", Group.OTHER);
    /** Rarely seen: the plug-in shows Neovim's messages itself, without Vim's prompt. */
    private static final Label HIT_ENTER = new Label("HIT-ENTER", "Press ENTER", Group.OTHER);
    private static final Label CONFIRM = new Label("CONFIRM", "-- CONFIRM --", Group.OTHER);
    /** Practically never seen: Neovim answers nothing while a shell command runs (STATUS_BAR.md). */
    private static final Label SHELL = new Label("SHELL", "-- SHELL --", Group.OTHER);
    /** Neovim's terminal; Eclipse editors never show one, but the mode exists. */
    private static final Label TERMINAL = new Label("TERMINAL", "-- TERMINAL --", Group.OTHER);

    /** Every label, for {@link #texts}. */
    private static final List<Label> ALL = List.of(ECLIPSE, NORMAL, OPERATOR, CTRL_O_INSERT, CTRL_O_REPLACE,
            CTRL_O_VREPLACE, INSERT, REPLACE, VREPLACE, VISUAL, VISUAL_LINE, VISUAL_BLOCK, SELECT_VISUAL,
            SELECT_VISUAL_LINE, SELECT_VISUAL_BLOCK, SELECT, SELECT_LINE, SELECT_BLOCK, COMMAND, EX, MORE,
            HIT_ENTER, CONFIRM, SHELL, TERMINAL);

    private ModeLabels() {
    }

    /**
     * The label for Neovim mode {@code mode}; Normal for {@code null} or "" (before Neovim said).
     * An unknown mode (a newer Neovim's) gets a label made from the string itself: {@code XY},
     * tooltip {@code -- XY --}.
     */
    public static Label of(String mode) {
        if (mode == null || mode.isEmpty()) {
            return NORMAL;
        }
        boolean fromSelect = mode.length() > 1 && mode.charAt(1) == 's'; // vs, Vs, ^Vs
        return switch (mode.charAt(0)) {
            case 'n' -> mode.startsWith("no") ? OPERATOR
                    : mode.equals("niI") ? CTRL_O_INSERT
                    : mode.equals("niR") ? CTRL_O_REPLACE
                    : mode.equals("niV") ? CTRL_O_VREPLACE
                    : NORMAL; // n, nt, ntT
            case 'i' -> INSERT; // i, ic, ix
            case 'R' -> mode.startsWith("Rv") ? VREPLACE : REPLACE; // R, Rc, Rx; Rv, Rvc, Rvx
            case 'v' -> fromSelect ? SELECT_VISUAL : VISUAL;
            case 'V' -> fromSelect ? SELECT_VISUAL_LINE : VISUAL_LINE;
            case VimModes.CTRL_V -> fromSelect ? SELECT_VISUAL_BLOCK : VISUAL_BLOCK;
            case 's' -> SELECT;
            case 'S' -> SELECT_LINE;
            case VimModes.CTRL_S -> SELECT_BLOCK;
            case 'c' -> mode.startsWith("cv") ? EX : COMMAND; // c, cr; cv, cvr
            case 'r' -> mode.equals("rm") ? MORE : mode.equals("r?") ? CONFIRM : HIT_ENTER;
            case '!' -> SHELL;
            case 't' -> TERMINAL;
            default -> {
                String name = mode.toUpperCase(Locale.ROOT);
                yield new Label(name, "-- " + name + " --", Group.OTHER);
            }
        };
    }

    /**
     * The mode label's text: {@code label}, followed by the macro being recorded into
     * {@code register}, in lower case so it stands apart from the mode's name. Example:
     * {@code INSERT rec @q}.
     *
     * @param register the register being recorded into; {@code null} or "" when not recording
     */
    public static String text(Label label, String register) {
        return recording(register) ? label.text() + " rec @" + register : label.text();
    }

    /**
     * The mode label's tooltip: Vim's wording, and Vim's {@code recording @q} while recording.
     * Example: {@code -- INSERT --  recording @q}, as Vim's {@code 'showmode'} line reads.
     */
    public static String tooltip(Label label, String register) {
        return recording(register) ? label.vim() + "  recording @" + register : label.vim();
    }

    private static boolean recording(String register) {
        return register != null && !register.isEmpty();
    }

    /**
     * Every text the mode label may show, each with {@code rec @a} after it while
     * {@code recording}: the label is as wide as the widest of them as its font draws them
     * ({@code editor/StatusBarLabels}), so it keeps its size (and the labels after it their places) as
     * the mode changes, and widens for a recording only while one runs. Example: {@code NORMAL},
     * {@code O-PENDING}, ... {@code (S) V-BLOCK}.
     */
    public static List<String> texts(boolean recording) {
        return ALL.stream().map(label -> text(label, recording ? "a" : "")).toList();
    }
}
