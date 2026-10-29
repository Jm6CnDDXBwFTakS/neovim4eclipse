// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The one table of Neovim's keys that run an IDE command ({@code gd}, {@code K}, the {@code gr*}
 * keys, {@code gt}): which key, in which modes, sends which {@link IdeAction}, and how Eclipse finds
 * the command to run for it. Neovim's mappings ({@link LuaScripts#IDE_KEYS}, given {@link #toLuaArgs})
 * and Eclipse's side ({@code editor/IdeActions}) both read it, so a key is added in one place.
 *
 * <h2>How a command is found: {@link Way}s, tried in order</h2>
 * The same action is a different command in every kind of editor (Java's Rename is JDT's refactoring,
 * C's is CDT's), so the plug-in does not implement any of them; it finds the editor's own:
 * <ul>
 *   <li>{@link Way#boundTo(String)}: the command the editor binds to Eclipse's usual key. Eclipse
 *       resolves a key by the active contexts, so "what does Alt+Shift+R run here?" gives Java's
 *       Rename in the Java editor and C's in the C editor. Used where the usual key is not one the
 *       plug-in's own key contexts take (they take Ctrl+letter: Ctrl+O is Neovim's {@code Ctrl-O}).
 *       Example: {@code gd} asks for F3. Open Hyperlink (the Generic Editor's F3) counts only with a
 *       link under the caret.</li>
 *   <li>{@link Way#command(String)}: a command by id, for one every editor shares (Quick Fix,
 *       Show Tooltip Description) or one only Java has, whose key the plug-in's contexts take.</li>
 *   <li>{@link Way#hyperlink(String)}: a hyperlink detector's link at the caret, opened, for what
 *       Eclipse offers only through Ctrl+click: JDT's "Open Declared Type" has no command.</li>
 * </ul>
 * A way that finds nothing, or a command with no enabled handler in this editor (Rename in a plain
 * text file), passes to the next. When none runs: Neovim's own key for {@code gd} (its "local
 * declaration"), else a message, {@code grn: Rename is not available in this editor}. Neovim's own
 * {@code gr*} keys would need a language server inside Neovim, which the plug-in avoids (Eclipse is
 * that server).
 *
 * <p>The command ids and Eclipse key sequences are plain strings here (no Eclipse types in
 * {@code core}); {@code KeyBindings} parses the keys ({@code M1} is Ctrl, Cmd on macOS; {@code M2}
 * Shift; {@code M3} Alt).
 */
public final class IdeKeys {

    private IdeKeys() {
    }

    /** One way of finding the command for an action; see the class comment. */
    public record Way(Kind kind, String value) {

        /** What {@link #value} is. */
        public enum Kind {
            /** An Eclipse key sequence ({@code "M2+M3+R"}): the command bound to it now. */
            BOUND_TO,
            /** A command id. */
            COMMAND,
            /** A hyperlink detector's id: its first link at the caret. */
            HYPERLINK
        }

        public Way {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
        }

        public static Way boundTo(String eclipseKey) {
            return new Way(Kind.BOUND_TO, eclipseKey);
        }

        public static Way command(String id) {
            return new Way(Kind.COMMAND, id);
        }

        public static Way hyperlink(String detectorId) {
            return new Way(Kind.HYPERLINK, detectorId);
        }
    }

    /**
     * An action carried out by an Eclipse command.
     *
     * @param label      the command's name in Eclipse's menus, for the message when it is missing
     * @param ways       how the command is found, in order
     * @param neovimKeys Neovim's own keys to run (without mappings) when no way runs, instead of a
     *                   message; "" for the message. Example: {@code "gd"}, Vim's "go to local
     *                   declaration", for Open Declaration
     */
    public record Command(String label, List<Way> ways, String neovimKeys) {

        public Command {
            ways = List.copyOf(ways);
            neovimKeys = neovimKeys == null ? "" : neovimKeys;
        }
    }

    /**
     * A Neovim key that sends an action.
     *
     * @param lhs         the key in Neovim's notation ({@code "gd"}, {@code "<C-]>"})
     * @param modes       Neovim's mode letters for {@code vim.keymap.set}: {@code "n"}, or {@code "nx"}
     *                    for normal and visual mode
     * @param action      the {@link IdeAction} name it sends
     * @param description the mapping's description ({@code :map gd} shows {@code Eclipse: ...})
     */
    public record Key(String lhs, String modes, String action, String description) {
    }

    /** The command id of Quick Fix / Quick Assist (Ctrl+1) in every text editor: {@code ITextEditorActionDefinitionIds.QUICK_ASSIST}. */
    public static final String QUICK_ASSIST = "org.eclipse.jdt.ui.edit.text.java.correction.assist.proposals";
    /** Show Tooltip Description (F2): {@code ITextEditorActionDefinitionIds.SHOW_INFORMATION}. */
    public static final String SHOW_INFORMATION = "org.eclipse.ui.edit.text.showInformation";

    /**
     * The actions carried out by an Eclipse command, by {@link IdeAction} name. The tab, history and
     * close actions are not here: {@code editor/IdeActions} does those itself.
     */
    public static final Map<String, Command> COMMANDS = Map.of(
            IdeAction.DEFINITION, new Command("Open Declaration", List.of(Way.boundTo("F3")), "gd"),
            IdeAction.HOVER, new Command("Show Tooltip Description", List.of(Way.command(SHOW_INFORMATION)), ""),
            IdeAction.RENAME, new Command("Rename", List.of(Way.boundTo("M2+M3+R")), ""),
            IdeAction.QUICK_FIX, new Command("Quick Fix", List.of(Way.command(QUICK_ASSIST)), ""),
            IdeAction.REFERENCES, new Command("References", List.of(Way.boundTo("M1+M2+G")), ""),
            IdeAction.IMPLEMENTATION, new Command("Open Implementation",
                    List.of(Way.command("org.eclipse.jdt.ui.edit.text.java.open.implementation")), ""),
            IdeAction.TYPE_DEFINITION, new Command("Open Declared Type", List.of(
                    Way.hyperlink("org.eclipse.jdt.internal.ui.javaeditor.JavaElementHyperlinkDeclaredTypeDetector")), ""),
            // Ctrl+O, Quick Outline's key, is the plug-in's (Neovim's Ctrl-O): by id.
            IdeAction.OUTLINE, new Command("Quick Outline",
                    List.of(Way.command("org.eclipse.jdt.ui.edit.text.java.show.outline")), ""));

    /**
     * Neovim's keys that {@code vim.g.neovim4eclipse_ide_keys = 0} gives back to Neovim, in the order
     * they are mapped. The {@code gr*} keys and {@code gO} are Neovim's own defaults for a language
     * server ({@code :help grn}); here they run Eclipse's counterparts. {@code grx} (run a code lens)
     * has none and stays Neovim's.
     */
    public static final List<Key> KEYS = List.of(
            command("gd", "n", IdeAction.DEFINITION),
            // Vim's tag jump: in Neovim with a language server it goes to the definition too (its
            // 'tagfunc'), and the plug-in has no tags file.
            command("<C-]>", "n", IdeAction.DEFINITION),
            command("K", "n", IdeAction.HOVER),
            command("grn", "n", IdeAction.RENAME),
            // Visual mode too, as Neovim's own gra: v and V selections are the editor's selection,
            // so Quick Assist works on it (Extract Local Variable).
            command("gra", "nx", IdeAction.QUICK_FIX),
            command("grr", "n", IdeAction.REFERENCES),
            command("gri", "n", IdeAction.IMPLEMENTATION),
            command("grt", "n", IdeAction.TYPE_DEFINITION),
            command("gO", "n", IdeAction.OUTLINE),
            new Key("gt", "n", IdeAction.NEXT_TAB, "next editor tab"),
            new Key("gT", "n", IdeAction.PREVIOUS_TAB, "previous editor tab"));

    /** A key of a {@link #COMMANDS} action, described by the command's label. */
    private static Key command(String lhs, String modes, String action) {
        return new Key(lhs, modes, action, COMMANDS.get(action).label());
    }

    /** The first key of {@link #KEYS} that sends {@code action} ({@code gd} for Open Declaration), for messages. */
    public static Optional<String> keyOf(String action) {
        return KEYS.stream().filter(k -> k.action().equals(action)).map(Key::lhs).findFirst();
    }

    /**
     * The arguments of {@link LuaScripts#IDE_KEYS}: four strings per key, lhs, modes, action,
     * description. Example: {@code ["gd", "n", "definition", "Open Declaration", ...]}.
     */
    public static List<String> toLuaArgs() {
        List<String> args = new ArrayList<>(4 * KEYS.size());
        for (Key key : KEYS) {
            args.add(key.lhs());
            args.add(key.modes());
            args.add(key.action());
            args.add(key.description());
        }
        return args;
    }
}
