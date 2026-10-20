// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * Translates SWT key events into Neovim key notation for {@code nvim_input}
 * (for example {@code <C-w>}, {@code <Esc>}, {@code <lt>}).
 *
 * <p>This class is the <strong>single source of truth</strong> for key translation. It takes
 * plain integers so it can be unit tested without SWT; the numeric constants below mirror
 * {@code org.eclipse.swt.SWT.*}. SWT constants are compile-time public API and never change,
 * and {@code KeyInterceptor} logs a warning at class-load time if any of them ever diverge.
 */
public final class KeyMapping {

    // ---- Mirrors of SWT constants (see class comment) ----
    public static final int SWT_ALT = 1 << 16;
    public static final int SWT_SHIFT = 1 << 17;
    public static final int SWT_CTRL = 1 << 18;

    public static final int SWT_BS = 8;
    public static final int SWT_TAB = 9;
    public static final int SWT_LF = 10;
    public static final int SWT_CR = 13;
    public static final int SWT_ESC = 27;
    public static final int SWT_DEL = 0x7F;

    /** SWT's marker bit for non-character key codes. */
    private static final int KEYCODE_BIT = 1 << 24;
    public static final int SWT_ARROW_UP = KEYCODE_BIT + 1;
    public static final int SWT_ARROW_DOWN = KEYCODE_BIT + 2;
    public static final int SWT_ARROW_LEFT = KEYCODE_BIT + 3;
    public static final int SWT_ARROW_RIGHT = KEYCODE_BIT + 4;
    public static final int SWT_PAGE_UP = KEYCODE_BIT + 5;
    public static final int SWT_PAGE_DOWN = KEYCODE_BIT + 6;
    public static final int SWT_HOME = KEYCODE_BIT + 7;
    public static final int SWT_END = KEYCODE_BIT + 8;
    public static final int SWT_INSERT = KEYCODE_BIT + 9;
    /** {@code F1} is {@code SWT_F1}; {@code F12} is {@code SWT_F1 + 11}. */
    public static final int SWT_F1 = KEYCODE_BIT + 10;
    public static final int SWT_F12 = SWT_F1 + 11;
    public static final int SWT_KEYPAD_CR = KEYCODE_BIT + 80;

    private KeyMapping() {
    }

    /**
     * @param keyCode   SWT {@code keyCode}
     * @param stateMask SWT {@code stateMask} (modifier bits)
     * @param character SWT {@code character} ({@code 0} if none)
     * @return Neovim key string, or {@code null} if the event should be ignored (left to Eclipse)
     */
    public static String toNeovimKey(int keyCode, int stateMask, char character) {
        boolean ctrl = (stateMask & SWT_CTRL) != 0;
        boolean alt = (stateMask & SWT_ALT) != 0;
        boolean shift = (stateMask & SWT_SHIFT) != 0;

        String special = specialKeyName(keyCode);
        if (special != null) {
            return wrapModifiers(special, ctrl, alt, shift);
        }
        if (keyCode == SWT_ESC || character == SWT_ESC) {
            return "<Esc>"; // modifiers on Esc are not meaningful to Neovim here
        }
        // Ctrl+I, Ctrl+M and Ctrl+H arrive as the Tab, CR and BS characters (that is what they are in
        // ASCII), with the letter in keyCode. Checked first so they reach Neovim as <C-i> (jump forward),
        // <C-m> and <C-h>, not as <C-Tab>, <C-CR> and <C-BS>.
        int lowerKey = Character.toLowerCase(keyCode);
        if (ctrl && !alt && keyCode < 128 && lowerKey >= 'a' && lowerKey <= 'z') {
            return "<C-" + (char) lowerKey + ">";
        }
        if (keyCode == SWT_CR || keyCode == SWT_KEYPAD_CR || character == SWT_CR || character == SWT_LF) {
            return wrapModifiers("CR", ctrl, alt, shift);
        }
        if (keyCode == SWT_TAB || character == SWT_TAB) {
            return wrapModifiers("Tab", ctrl, alt, shift);
        }
        if (keyCode == SWT_BS || character == SWT_BS) {
            return wrapModifiers("BS", ctrl, alt, shift);
        }
        if (keyCode == SWT_DEL) {
            return wrapModifiers("Del", ctrl, alt, shift);
        }

        if (character != 0 && !Character.isISOControl(character)) {
            return printableKey(character, ctrl, alt);
        }

        return null;
    }

    private static String printableKey(char character, boolean ctrl, boolean alt) {
        char lower = Character.toLowerCase(character);
        boolean isLetter = lower >= 'a' && lower <= 'z';
        if (isLetter && ctrl && !alt) {
            return "<C-" + lower + ">";
        }
        if (isLetter && alt && !ctrl) {
            return "<A-" + lower + ">";
        }
        // '<' must be escaped for nvim_input or it starts a key-notation sequence.
        return character == '<' ? "<lt>" : String.valueOf(character);
    }

    private static String specialKeyName(int keyCode) {
        return switch (keyCode) {
            case SWT_ARROW_UP -> "Up";
            case SWT_ARROW_DOWN -> "Down";
            case SWT_ARROW_LEFT -> "Left";
            case SWT_ARROW_RIGHT -> "Right";
            case SWT_PAGE_UP -> "PageUp";
            case SWT_PAGE_DOWN -> "PageDown";
            case SWT_HOME -> "Home";
            case SWT_END -> "End";
            case SWT_INSERT -> "Insert";
            default -> keyCode >= SWT_F1 && keyCode <= SWT_F12 ? "F" + (keyCode - SWT_F1 + 1) : null;
        };
    }

    /** Whether Neovim understands an {@code S-} prefix for this key (navigation and function keys). */
    private static boolean acceptsShift(String key) {
        return switch (key) {
            case "Up", "Down", "Left", "Right", "Home", "End", "PageUp", "PageDown" -> true;
            default -> key.startsWith("F");
        };
    }

    private static String wrapModifiers(String key, boolean ctrl, boolean alt, boolean shift) {
        // Shift is only encoded for navigation/function keys. Shift+Tab is intentionally
        // left as plain <Tab> (existing behavior); Shift on other keys is already in the character.
        String name = shift && acceptsShift(key) ? "S-" + key : key;
        if (ctrl && alt) {
            return "<C-A-" + name + ">";
        }
        if (ctrl) {
            return "<C-" + name + ">";
        }
        if (alt) {
            return "<A-" + name + ">";
        }
        return "<" + name + ">";
    }
}
