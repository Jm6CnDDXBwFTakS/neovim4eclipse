// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.Locale;

/**
 * What the status bar's mode label says for a Neovim mode string ({@code nvim_get_mode().mode}), in
 * the style the user chose (preference <em>Mode style</em>). One table, so the three styles can
 * never disagree about which states exist. Example for Virtual Replace ({@code gR}, mode {@code Rv}):
 * Full {@code -- VREPLACE --}, Short {@code VREPLACE}, Letters {@code -VR-}.
 *
 * <h2>Why these texts</h2>
 * Full is Vim's own {@code 'showmode'} text where Vim has one ({@code -- (insert) --} after Ctrl-O in
 * Insert mode, {@code -- VREPLACE --}). Vim shows nothing in Normal mode, on the command line, in Ex
 * mode and while a shell command runs; the plug-in names those too ({@code -- NORMAL --}), since an
 * empty label would look broken. Short is Full without the dashes, in capitals. Letters were chosen one by one by
 * the maintainer (2026-10-29; docs/design/STATUS_BAR.md). Neovim reports a mode more finely than these
 * labels: all four operator-pending forms ({@code no}, {@code nov}, {@code noV}, {@code no^V}) are
 * {@code -O-}, and the completion forms of Insert ({@code ic}, {@code ix}) are Insert's.
 *
 * <p>Two states Vim names cannot be told apart from the mode string: Visual or Select entered with
 * Ctrl-O from Insert mode report plain {@code v} or {@code s}, so they show as plain Visual or Select
 * (decided 2026-10-29: not worth tracking the mode before them).
 *
 * <p>Pure Java; all mode strings are interpreted here or in {@link VimModes}.
 */
public final class ModeLabels {

    /** The preference's choices. {@link #key()} is the value stored. */
    public enum Style {
        /** Vim's text: {@code -- INSERT --}. */
        FULL("full"),
        /** Without the dashes, in capitals: {@code INSERT}, {@code (INSERT)}. */
        SHORT("short"),
        /** A few letters: {@code -I-}. */
        LETTERS("letters");

        private final String key;

        Style(String key) {
            this.key = key;
        }

        /** The value stored in the preference store. */
        public String key() {
            return key;
        }

        /** The style stored as {@code key}; Full for anything else (an old or hand-edited value). */
        public static Style of(String key) {
            for (Style s : values()) {
                if (s.key.equals(key)) {
                    return s;
                }
            }
            return FULL;
        }
    }

    /**
     * One state's three texts. {@code name} is Full without the dashes, as Vim writes it
     * ({@code (insert)}, {@code VREPLACE}); Short is {@code name} in capitals ({@code (INSERT)}), and
     * the tooltip uses {@code name} to explain the letters ({@code -VR- = VREPLACE}).
     */
    public record Label(String name, String letters) {

        /** The text in {@code style}. */
        public String text(Style style) {
            return switch (style) {
                case FULL -> "-- " + name + " --";
                case SHORT -> name.toUpperCase(Locale.ROOT);
                case LETTERS -> letters;
            };
        }
    }

    /** Keys go to Eclipse, not Neovim (an editor the plug-in does not drive, or Neovim stopped). */
    public static final Label ECLIPSE = new Label("ECLIPSE", "-E-");

    // The table, in the order of docs/design/STATUS_BAR.md. Ctrl-V and Ctrl-S are written \u0016 and \u0013.
    private static final Label NORMAL = new Label("NORMAL", "-N-");
    private static final Label OPERATOR = new Label("OPERATOR", "-O-");
    private static final Label CTRL_O_INSERT = new Label("(insert)", "-(i)-");
    private static final Label CTRL_O_REPLACE = new Label("(replace)", "-(r)-");
    private static final Label CTRL_O_VREPLACE = new Label("(vreplace)", "-(vr)-");
    private static final Label INSERT = new Label("INSERT", "-I-");
    private static final Label REPLACE = new Label("REPLACE", "-R-");
    private static final Label VREPLACE = new Label("VREPLACE", "-VR-");
    private static final Label VISUAL = new Label("VISUAL", "-V-");
    private static final Label VISUAL_LINE = new Label("VISUAL LINE", "-VL-");
    private static final Label VISUAL_BLOCK = new Label("VISUAL BLOCK", "-VB-");
    private static final Label SELECT_VISUAL = new Label("(select) VISUAL", "-(s)V-");
    private static final Label SELECT_VISUAL_LINE = new Label("(select) VISUAL LINE", "-(s)VL-");
    private static final Label SELECT_VISUAL_BLOCK = new Label("(select) VISUAL BLOCK", "-(s)VB-");
    private static final Label SELECT = new Label("SELECT", "-S-");
    private static final Label SELECT_LINE = new Label("SELECT LINE", "-SL-");
    private static final Label SELECT_BLOCK = new Label("SELECT BLOCK", "-SB-");
    private static final Label COMMAND = new Label("COMMAND", "-C-");
    private static final Label EX = new Label("EX", "-EX-");
    private static final Label MORE = new Label("MORE", "-RM-");
    /** Same letters as Replace, by choice: the prompt is rarely seen with the plug-in's message handling. */
    private static final Label HIT_ENTER = new Label("HIT-ENTER", "-R-");
    private static final Label CONFIRM = new Label("CONFIRM", "-R?-");
    private static final Label SHELL = new Label("SHELL", "-!sh-");
    /** Neovim's terminal; Eclipse editors never show one, but the mode exists. */
    private static final Label TERMINAL = new Label("TERMINAL", "-T-");

    /** Every label, for {@link #texts}. */
    private static final List<Label> ALL = List.of(ECLIPSE, NORMAL, OPERATOR, CTRL_O_INSERT, CTRL_O_REPLACE,
            CTRL_O_VREPLACE, INSERT, REPLACE, VREPLACE, VISUAL, VISUAL_LINE, VISUAL_BLOCK, SELECT_VISUAL,
            SELECT_VISUAL_LINE, SELECT_VISUAL_BLOCK, SELECT, SELECT_LINE, SELECT_BLOCK, COMMAND, EX, MORE,
            HIT_ENTER, CONFIRM, SHELL, TERMINAL);

    private ModeLabels() {
    }

    /**
     * The label for Neovim mode {@code mode}; Normal for {@code null} or "" (before Neovim said).
     * An unknown mode (a newer Neovim's) gets a label made from the string itself: {@code -- XY --},
     * {@code XY}, {@code -xy-}.
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
            default -> new Label(mode.toUpperCase(Locale.ROOT), "-" + mode + "-");
        };
    }

    /**
     * The mode label's text: {@code label} in {@code style}, followed by the macro being recorded
     * into {@code register} as Vim shows it ({@code recording @a}; Letters {@code rec @a}). Examples:
     * {@code -- INSERT --  recording @q}, {@code -I- rec @q}.
     *
     * @param register the register being recorded into; {@code null} or "" when not recording
     */
    public static String text(Label label, String register, Style style) {
        String text = label.text(style);
        if (register == null || register.isEmpty()) {
            return text;
        }
        return style == Style.LETTERS ? text + " rec @" + register : text + "  recording @" + register;
    }

    /**
     * The mode label's tooltip: what the label says in words. Full shows it all already, so its tooltip
     * repeats the text; Short and Letters add Vim's name: {@code -VR- = VREPLACE},
     * {@code -I- rec @q = INSERT, recording @q}.
     */
    public static String tooltip(Label label, String register, Style style) {
        String shown = text(label, register, style);
        if (style == Style.FULL) {
            return shown;
        }
        String recording = register == null || register.isEmpty() ? "" : ", recording @" + register;
        return shown + " = " + label.name() + recording;
    }

    /**
     * Every text the mode label may show in {@code style}, each with {@code recording @a} after it
     * while {@code recording}: the label is as wide as the widest of them as its font draws them
     * ({@code editor/StatusBarLabels}), so it keeps its size (and the labels after it their places) as
     * the mode changes, and widens for a recording only while one runs. Example, Full:
     * {@code -- NORMAL --}, {@code -- OPERATOR --}, ... {@code -- (select) VISUAL BLOCK --}.
     */
    public static List<String> texts(Style style, boolean recording) {
        return ALL.stream().map(label -> text(label, recording ? "a" : "", style)).toList();
    }
}
