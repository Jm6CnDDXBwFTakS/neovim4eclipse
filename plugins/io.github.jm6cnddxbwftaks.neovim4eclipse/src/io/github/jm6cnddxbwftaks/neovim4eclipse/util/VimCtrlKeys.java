// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

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
    public static final String COMMAND_PREFIX = "io.github.jm6cnddxbwftaks.neovim4eclipse.commands.ctrl.";

    /** Prefix of every per-letter context id; the key letter follows it. */
    public static final String CONTEXT_PREFIX = "io.github.jm6cnddxbwftaks.neovim4eclipse.context.ctrl.";

    /** Parent of the letter contexts; active while Neovim owns the keys of the focused editor. */
    public static final String NORMAL_CONTEXT_ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.context.normal";

    /**
     * Letters that, in an insert Eclipse types, run Eclipse's Word Completion
     * ({@link #WORD_COMPLETION_COMMAND_ID}) instead of reaching Neovim, whose completion popup the
     * plug-in does not draw: {@code Ctrl-N} and {@code Ctrl-P}. Each has a second context
     * ({@link #completionContextId}) whose binding runs Eclipse's command itself. Why not the
     * plug-in's own command, which could run it too: Word Completion offers the next word when
     * pressed again only while no other command runs in between, and the plug-in's command would be
     * one.
     */
    public static final String COMPLETION_LETTERS = "np";

    /** Prefix of the Word Completion contexts; the letter follows it. */
    public static final String COMPLETION_CONTEXT_PREFIX = "io.github.jm6cnddxbwftaks.neovim4eclipse.context.complete.";

    /** Eclipse's <i>Word Completion</i> (Alt+/), {@code ITextEditorActionDefinitionIds.HIPPIE_COMPLETION}. */
    public static final String WORD_COMPLETION_COMMAND_ID = "org.eclipse.ui.edit.text.hippieCompletion";

    /** Every letter that can be sent to Neovim. */
    public static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";

    /**
     * Letters that stay with Eclipse on Windows and Linux unless the user changes it: Ctrl+S saves,
     * Ctrl+W closes the editor (what Eclipse users expect of it; ticked, it gives Vim's window
     * commands, docs/design/SPLITS.md), Ctrl+Z undoes (Vim's suspend is meaningless when embedded).
     * See {@code docs/KEYBOARD.md}.
     */
    public static final String ECLIPSE_BY_DEFAULT = "swz";

    /**
     * The same on macOS, without {@code w}: Eclipse closes an editor with Cmd+W there, so
     * Control+W is free for Vim's window commands ({@code Ctrl-W l}, {@code Ctrl-W s}) by default.
     */
    public static final String ECLIPSE_BY_DEFAULT_MAC = "sz";

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
        "window commands: splits",        // w
        "decrement number",               // x
        "scroll up one line",             // y
        "suspend",                        // z
    };

    /**
     * What each key does in Vim's insert mode, indexed like {@link #LETTERS}; shown in the
     * preferences. {@code Ctrl-N}, {@code Ctrl-P} and {@code Ctrl-X} run Eclipse's completion
     * instead of Neovim's popup, which the plug-in does not draw (docs/design/INSERT_CTRL_KEYS.md).
     */
    private static final String[] INSERT_MEANINGS = {
        "insert the last inserted text",  // a
        "unused in insert mode",          // b
        "leave insert mode, like Esc",    // c
        "unindent the line",              // d
        "insert the character below",     // e
        "unused in insert mode",          // f
        "undo break, cursor (u, j, k)",   // g
        "delete the character before",    // h
        "insert a Tab",                   // i
        "new line",                       // j
        "enter a digraph",                // k
        "unused in insert mode",          // l
        "new line",                       // m
        "Word Completion (Eclipse)",      // n
        "one normal-mode command",        // o
        "Word Completion (Eclipse)",      // p
        "insert the next key literally",  // q
        "insert a register",              // r
        "unused in insert mode",          // s
        "indent the line",                // t
        "delete back to where insert began", // u
        "insert the next key literally",  // v
        "delete the word before",         // w
        "Ctrl-X Ctrl-O: content assist (Eclipse)", // x
        "insert the character above",     // y
        "unused in insert mode",          // z
    };

    /**
     * Letters that go to Neovim in insert mode by default on Windows and Linux: delete word, delete
     * to the insert's start, insert a register, one normal command, indent, unindent. The others
     * keep Eclipse's shortcuts there, which people use while typing (Ctrl+A select all, Ctrl+C copy,
     * Ctrl+V paste, Ctrl+X cut, Ctrl+Z undo, Ctrl+S save, Ctrl+F find).
     */
    public static final String INSERT_BY_DEFAULT = "wurotd";

    /**
     * On macOS, Eclipse's own shortcuts use Cmd, so every letter with a Vim insert meaning goes to
     * Neovim by default, Ctrl+A, Ctrl+E and Ctrl+K included, although macOS text
     * fields use them for line start, line end and delete to line end.
     */
    public static final String INSERT_BY_DEFAULT_MAC = "acdeghijkmnopqrtuvwxy";

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

    /** Word Completion context id for a letter of {@link #COMPLETION_LETTERS}, e.g. {@code ...context.complete.n}. */
    public static String completionContextId(char letter) {
        return COMPLETION_CONTEXT_PREFIX + Character.toLowerCase(letter);
    }

    /** Whether the letter goes to Neovim when the user has not chosen otherwise, on macOS or elsewhere. */
    public static boolean toNeovimByDefault(char letter, boolean mac) {
        return (mac ? ECLIPSE_BY_DEFAULT_MAC : ECLIPSE_BY_DEFAULT).indexOf(Character.toLowerCase(letter)) < 0;
    }

    /** Whether the letter goes to Neovim in insert mode when the user has not chosen otherwise. */
    public static boolean inInsertByDefault(char letter, boolean mac) {
        return (mac ? INSERT_BY_DEFAULT_MAC : INSERT_BY_DEFAULT).indexOf(Character.toLowerCase(letter)) >= 0;
    }

    /** What the key does in Vim's normal mode, for example {@code "redo"} for {@code 'r'}. */
    public static String vimMeaning(char letter) {
        return VIM_MEANINGS[index(letter)];
    }

    /** What the key does in Vim's insert mode, for example {@code "delete the word before"} for {@code 'w'}. */
    public static String insertMeaning(char letter) {
        return INSERT_MEANINGS[index(letter)];
    }

    private static int index(char letter) {
        int index = LETTERS.indexOf(Character.toLowerCase(letter));
        if (index < 0) {
            throw new IllegalArgumentException("not a Ctrl-key letter: " + letter);
        }
        return index;
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
