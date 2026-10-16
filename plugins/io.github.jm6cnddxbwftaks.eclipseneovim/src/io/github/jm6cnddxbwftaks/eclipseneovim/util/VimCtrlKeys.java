// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

/**
 * The {@code Ctrl+letter} keys that the plug-in can claim from Eclipse while Neovim owns normal and
 * visual mode, and the ids that connect them to {@code plugin.xml}.
 *
 * <h2>Why key bindings are needed at all</h2>
 * Eclipse's workbench looks at every key press <em>before</em> the focused widget does. If the
 * press matches a key binding (Ctrl+S is Save, Ctrl+F is Find, ...) the workbench runs that command
 * and the editor never sees the key. To get {@code Ctrl+F} to Neovim, the plug-in therefore declares
 * its own binding for it, which runs a command whose handler ({@code SendKeyHandler}) sends
 * {@code <C-f>} to Neovim.
 *
 * <h2>How each letter is switched on and off</h2>
 * Each letter has its own <em>context</em> ({@code ...context.ctrl.f}) with exactly one binding in
 * it. A binding only counts while its context is active, and the editor activates just the
 * contexts of the letters the user sends to Neovim (Window > Preferences > Neovim > Ctrl Keys).
 * Letters left to Eclipse have no active binding, so Eclipse's own binding applies.
 *
 * <pre>
 *   org.eclipse.ui.textEditorScope        Eclipse's text editor bindings (Ctrl+D = Delete Line)
 *   └── ...context.normal                 active while Neovim owns normal/visual mode
 *       ├── ...context.ctrl.a             binding Ctrl+A -> ...commands.ctrl.a
 *       ├── ...context.ctrl.b             ...
 *       └── ...context.ctrl.z
 * </pre>
 *
 * <p>When two bindings use the same keys, Eclipse picks the one in the deeper (more specific)
 * context. The letter contexts sit below the text editor scope and also below editor-specific
 * scopes such as the Java editor's, so they win wherever they are active.
 *
 * <p>Each key has <em>its own command</em> instead of one command with a "key" parameter, because
 * parameterized key bindings did not deliver their parameter on every platform (the handler saw
 * {@code null} on Linux). {@code PluginXmlConsistencyTest} checks that {@code plugin.xml} declares
 * a context, command, handler and binding for every letter in {@link #LETTERS}.
 */
public final class VimCtrlKeys {

    /** Prefix of every Ctrl-key command id; the key letter follows it. */
    public static final String COMMAND_PREFIX = "io.github.jm6cnddxbwftaks.eclipseneovim.commands.ctrl.";

    /** Prefix of every per-letter context id; the key letter follows it. */
    public static final String CONTEXT_PREFIX = "io.github.jm6cnddxbwftaks.eclipseneovim.context.ctrl.";

    /** Parent of the letter contexts; active while Neovim owns the keys of the focused editor. */
    public static final String NORMAL_CONTEXT_ID = "io.github.jm6cnddxbwftaks.eclipseneovim.context.normal";

    /** Every letter that can be sent to Neovim. */
    public static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";

    /**
     * Letters that stay with Eclipse unless the user changes it: Ctrl+S saves, Ctrl+W closes the
     * editor (Vim's window commands are meaningless with one embedded window, and Ctrl+W q would quit
     * Neovim), Ctrl+Z undoes (Vim's suspend is meaningless when embedded). See {@code docs/KEYS.md}.
     */
    public static final String ECLIPSE_BY_DEFAULT = "swz";

    /** What each key does in Vim's normal mode, indexed like {@link #LETTERS}; shown in the preferences. */
    private static final String[] VIM_MEANINGS = {
        "increment number",               // a
        "page up",                        // b
        "cancel pending command",         // c
        "half page down",                 // d
        "scroll down one line",           // e
        "page down",                      // f
        "show file info",                 // g
        "cursor left",                    // h
        "jump forward",                   // i
        "cursor down",                    // j
        "unused in normal mode",          // k
        "redraw screen",                  // l
        "next line, first non-blank",     // m
        "cursor down",                    // n
        "jump back",                      // o
        "cursor up",                      // p
        "blockwise visual",               // q
        "redo",                           // r
        "unused in normal mode",          // s
        "back in tag stack",              // t
        "half page up",                   // u
        "blockwise visual",               // v
        "window commands",                // w
        "decrement number",               // x
        "scroll up one line",             // y
        "suspend",                        // z
    };

    private VimCtrlKeys() {
    }

    /** Command id for a letter, e.g. {@code 'v'} gives {@code ...commands.ctrl.v}. */
    public static String commandId(char letter) {
        return COMMAND_PREFIX + Character.toLowerCase(letter);
    }

    /** Context id for a letter, e.g. {@code 'v'} gives {@code ...context.ctrl.v}. */
    public static String contextId(char letter) {
        return CONTEXT_PREFIX + Character.toLowerCase(letter);
    }

    /** Whether the letter goes to Neovim when the user has not chosen otherwise. */
    public static boolean toNeovimByDefault(char letter) {
        return ECLIPSE_BY_DEFAULT.indexOf(Character.toLowerCase(letter)) < 0;
    }

    /** What the key does in Vim, for example {@code "redo"} for {@code 'r'}. */
    public static String vimMeaning(char letter) {
        int index = LETTERS.indexOf(Character.toLowerCase(letter));
        if (index < 0) {
            throw new IllegalArgumentException("not a Ctrl-key letter: " + letter);
        }
        return VIM_MEANINGS[index];
    }

    /**
     * Neovim key for a command id, e.g. {@code ...commands.ctrl.v} gives {@code <C-v>}.
     *
     * @return the key, or {@code null} if the id is not one of ours
     */
    public static String keyFor(String commandId) {
        if (commandId == null || !commandId.startsWith(COMMAND_PREFIX)) {
            return null;
        }
        String rest = commandId.substring(COMMAND_PREFIX.length());
        if (rest.length() != 1 || LETTERS.indexOf(rest.charAt(0)) < 0) {
            return null;
        }
        return "<C-" + rest + ">";
    }

    /** Whether the id is one of the Ctrl-key commands. */
    public static boolean isCtrlKeyCommand(String commandId) {
        return keyFor(commandId) != null;
    }

    /**
     * The letter of a Neovim key such as {@code <C-f>}, or {@code 0} if the key is not
     * {@code Ctrl} plus a single letter (with no other modifier).
     */
    public static char letterOf(String nvimKey) {
        if (nvimKey != null && nvimKey.length() == 5 && nvimKey.startsWith("<C-") && nvimKey.endsWith(">")) {
            char letter = Character.toLowerCase(nvimKey.charAt(3));
            return LETTERS.indexOf(letter) >= 0 ? letter : 0;
        }
        return 0;
    }
}
