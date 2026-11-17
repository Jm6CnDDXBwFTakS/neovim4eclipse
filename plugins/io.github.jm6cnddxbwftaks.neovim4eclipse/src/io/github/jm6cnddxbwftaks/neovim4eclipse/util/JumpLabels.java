// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.ArrayList;
import java.util.List;

/**
 * The labels of a jump plug-in (flash, leap, hop, mini.jump2d), drawn in the editor
 * (docs/design/SEARCH_HIGHLIGHTING.md, "Jump labels").
 *
 * <p><b>The concept.</b> A jump plug-in lets the user reach any place on the screen with two or
 * three keys: after its key it draws a letter over each place the cursor could go to, and the
 * letter typed next takes it there. In Neovim the letters are <i>overlay virtual text</i>: an
 * extmark at the target's position whose text is drawn over the character there, without
 * changing the buffer. The editor is Eclipse's, so the plug-in's script reads those marks and
 * sends them ({@code LuaScripts.HIGHLIGHTS}), and the editor paints them.
 *
 * <p><b>Which marks are a jump plug-in's.</b> Those whose highlight group starts with a known
 * prefix. {@link #BUILT_IN_PREFIXES} is the one place that names the plug-ins the editor knows
 * without any setting; the session hands it to the script as its argument, so the script holds no
 * copy. The user adds prefixes of their own with
 * {@code vim.g.neovim4eclipse_jump_label_group_prefixes}; the script joins the two lists and
 * changes neither.
 *
 * <p>Pure; unit-tested in {@code JumpLabelsTest}.
 */
public final class JumpLabels {

    /**
     * The start of the highlight-group names of the jump plug-ins known without a setting: flash
     * ({@code FlashLabel}, {@code FlashMatch}, {@code FlashBackdrop}), leap ({@code LeapLabel},
     * {@code LeapBackdrop}), hop ({@code HopNextKey}, {@code HopUnmatched}) and mini.jump2d
     * ({@code MiniJump2dSpot}, {@code MiniJump2dDim}). Unmodifiable. To know another plug-in by
     * default, add its prefix here and to the README's Settings table ({@code SettingsNamesTest}
     * checks that the two agree); nothing else names them.
     */
    public static final List<String> BUILT_IN_PREFIXES = List.of("Flash", "Leap", "Hop", "MiniJump2d");

    /**
     * The most labels taken from one notification. A screen of text has a few hundred places at
     * most; a list far longer is not a jump plug-in's, and painting each one costs time on the UI
     * thread.
     */
    public static final int MAX_LABELS = 2000;

    /**
     * The most screen cells a label may start after its character. A label sits on or right
     * beside its target; a number far larger is no jump label's, and multiplied by a cell's width
     * in pixels it would overflow an {@code int} and put the label somewhere else.
     */
    public static final int MAX_CELLS = 1000;

    /** The most characters of one label's text that are kept; jump labels have one or two. */
    public static final int MAX_TEXT_LENGTH = 8;

    /** A color the group does not set, as {@link NeovimHighlight#NO_COLOR}. */
    public static final int NO_COLOR = NeovimHighlight.NO_COLOR;

    private JumpLabels() {
    }

    /**
     * One piece of overlay text. Example: flash's {@code a} over the {@code l} of {@code alpha}
     * on line 1 is {@code Label(1, 1, 0, "a", 0x6B5300, 0xEEF1F8, false)}.
     *
     * @param line1      the line, 1-based
     * @param col0       the byte column of the character it is drawn over, 0-based
     * @param cells      how many screen cells after that character it starts: 0, or more for the
     *                   later pieces of a label made of several
     * @param text       what to draw, never empty
     * @param background {@code 0xRRGGBB}, or {@link #NO_COLOR}: the editor's own background then
     * @param foreground {@code 0xRRGGBB}, or {@link #NO_COLOR}: the editor's own text color then
     * @param bold       whether the group asks for bold text
     */
    public record Label(int line1, int col0, int cells, String text, int background, int foreground, boolean bold) {
    }

    /**
     * The labels in the fourth argument of {@code eclipse_highlights}: a list of
     * {@code { line, column, cells, text, background, text color, bold }}. The values come from
     * the user's own plug-ins, but they cross a process boundary, so nothing is taken on trust: an
     * entry of another shape, with a line below 1, a negative column or cell, an empty text or a
     * color outside {@code 0xRRGGBB} (and not -1) is left out; a text is cut to
     * {@link #MAX_TEXT_LENGTH} characters, without splitting a surrogate pair, and has its control
     * and layout characters removed ({@link #isDrawn}); entries after the {@link #MAX_LABELS}th are dropped. Anything that is no
     * list gives no labels (an older script sends no fourth argument).
     */
    public static List<Label> parse(Object argument) {
        if (!(argument instanceof List<?> list)) {
            return List.of();
        }
        List<Label> labels = new ArrayList<>(Math.min(list.size(), MAX_LABELS));
        for (Object entry : list) {
            if (labels.size() >= MAX_LABELS) {
                break;
            }
            Label label = entry instanceof List<?> fields ? label(fields) : null;
            if (label != null) {
                labels.add(label);
            }
        }
        return List.copyOf(labels);
    }

    private static Label label(List<?> f) {
        if (f.size() < 7 || !(f.get(3) instanceof String raw)) {
            return null;
        }
        Integer line = number(f.get(0));
        Integer col = number(f.get(1));
        Integer cells = number(f.get(2));
        Integer bg = number(f.get(4));
        Integer fg = number(f.get(5));
        String text = clean(raw);
        if (line == null || col == null || cells == null || bg == null || fg == null || text.isEmpty()
                || line < 1 || col < 0 || cells < 0 || cells > MAX_CELLS || !isColor(bg) || !isColor(fg)) {
            return null;
        }
        return new Label(line, col, cells, text, bg, fg, Boolean.TRUE.equals(f.get(6)));
    }

    /** {@code raw} without control characters, cut to {@link #MAX_TEXT_LENGTH} characters at a code point's edge. */
    private static String clean(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length();) {
            int cp = raw.codePointAt(i);
            int n = Character.charCount(cp);
            if (out.length() + n > MAX_TEXT_LENGTH) {
                break;
            }
            if (isDrawn(cp)) {
                out.appendCodePoint(cp);
            }
            i += n;
        }
        return out.toString();
    }

    /**
     * Whether a character of a label is kept: not a control character, nor one that only steers
     * how text is laid out (a line or paragraph separator, a format character such as the
     * right-to-left override or a zero-width joiner). Such a character would draw nothing in the
     * label's cell and could change how the cell's other characters read.
     */
    private static boolean isDrawn(int codePoint) {
        int type = Character.getType(codePoint);
        return !Character.isISOControl(codePoint) && type != Character.FORMAT && type != Character.LINE_SEPARATOR
                && type != Character.PARAGRAPH_SEPARATOR;
    }

    private static boolean isColor(int value) {
        return value == NO_COLOR || (value >= 0 && value <= 0xFFFFFF);
    }

    /** A whole number that fits an {@code int}, whatever integer type MessagePack decoded it to; else {@code null}. */
    private static Integer number(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long l = ((Number) value).longValue();
            return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (int) l : null;
        }
        return null;
    }
}
