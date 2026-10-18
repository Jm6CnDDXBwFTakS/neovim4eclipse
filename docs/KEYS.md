# Keys: who gets which key

Every key press in an attached editor goes either to Neovim or to Eclipse. This page explains the
rules, and why three `Ctrl` keys stay with Eclipse unless you change them. For the mechanism (key
bindings, contexts, the order in which Eclipse looks at a key) see
[LEARNING.md](LEARNING.md), part 1.6.

## The rules

| Situation | Keys go to |
|-----------|------------|
| Normal, visual and operator-pending mode | Neovim, except the `Ctrl` keys left to Eclipse (below) |
| Insert and replace mode | Eclipse, which types the text; `Esc` hands control back to Neovim |
| Insert mode started from blockwise visual (`Ctrl+V` then `I`, `A`, `c`), or while recording a macro | Neovim, so it sees every key |
| Keys typed within milliseconds of entering insert mode, before Eclipse has Neovim's text | Neovim, for the rest of that insert, so nothing is lost |
| `:`, `/`, `?` in normal mode | the plug-in's command box at the bottom of the editor |
| `ZZ`, `ZQ`, and the file and tab commands (`:w`, `:wa`, `:q`, `:e`, `:tabn`, ... see the README) | Eclipse's save, Save All, close, open and tab switching |
| `gd`, `K`, `gt`, `gT` in normal mode | Neovim first, which hands them to Eclipse (below) |
| Eclipse mode (the *NVIM* toolbar button) | Eclipse, always |

## `Ctrl` keys

Eclipse looks at a `Ctrl` key before the editor does, so a letter only reaches Neovim if the plug-in
claims it. *Window → Preferences → Neovim → Ctrl Keys* has one check box per letter: ticked letters go
to Neovim in normal and visual mode, cleared ones keep their Eclipse shortcut. Changes apply at once.

By default every letter goes to Neovim **except these three**:

| Key | Eclipse (Windows, Linux) | Vim | Why it stays with Eclipse |
|-----|--------------------------|-----|---------------------------|
| `Ctrl+S` | Save | nothing in normal mode | Saving with the usual shortcut matters more than a key Vim does not use. |
| `Ctrl+W` | Close editor | window commands (`Ctrl+W s`, `Ctrl+W q`, ...) | The embedded Neovim has a single window that Eclipse draws, so splits and window moves have no visible effect, and `Ctrl+W q` would quit the embedded Neovim (a new one starts on the next attach, losing undo history and registers). Closing the editor is the useful action. |
| `Ctrl+Z` | Undo | suspend Neovim | Suspending makes no sense for an embedded Neovim, and Vim's undo is `u`. Eclipse's undo also undoes Neovim's changes, because they are applied to the Eclipse document, and the result is pushed back to Neovim. |

Tick any of them to send it to Neovim instead.

**"Keybinding conflicts occurred" in the Error Log.** Eclipse warns about this when a Java editor opens:
`Ctrl+I`, `Ctrl+O` and `Ctrl+T` have bindings of the Java editor (Correct Indentation, Quick Outline,
Quick Type Hierarchy) and of the plug-in, in contexts where neither is the parent of the other, so
Eclipse picks no command. The key then reaches the editor widget, and the plug-in sends it to Neovim
as usual: `Ctrl+O` still jumps back. The warning is harmless. Clearing a letter's check box removes
its conflict and gives the key back to Java.

### macOS

The bindings use the physical **Control** key on macOS, while Eclipse's shortcuts use **Command**
(`Cmd+S` saves, `Cmd+W` closes). So on a Mac all `Ctrl` letters can safely go to Neovim. A letter left
to Eclipse there usually has no Eclipse binding, and then does nothing.

### Insert mode

In insert mode Eclipse types, so all `Ctrl` keys keep their Eclipse meaning: `Ctrl+Space` for content
assist, `Ctrl+V` to paste (on Windows and Linux), and so on. Vim's insert-mode `Ctrl` keys
(`Ctrl+W` to delete a word, `Ctrl+R` to insert a register) are not available.

## Other keys

- `Esc` always reaches Neovim, including in insert mode, where it first hands Eclipse's typing to
  Neovim so that `.` can repeat it.
- Any other key with an Eclipse key binding is taken by Eclipse first, in every mode: function keys
  such as `F3` (Open Declaration) and most `Ctrl+Shift+...` and `Alt+Shift+...` shortcuts.
- Keys Eclipse has no binding for reach Neovim in normal and visual mode: `Alt+letter` as `<A-x>`,
  arrows, `Home`, `End`, unbound function keys as `<F5>` and so on.

## Keys Neovim hands to Eclipse: `gd`, `K`, `gt`, `gT`

Some normal-mode keys mean something only the IDE can do. The plug-in maps them in Neovim to a small
Lua function that sends an `eclipse_action` notification back to Eclipse (`core/IdeAction`), so Neovim
still handles counts and the `g` prefix:

| Key | Eclipse runs |
|-----|--------------|
| `gd` | the command bound to `F3` in this editor (Open Declaration in the Java editor); in an editor without one (the plain Text Editor), Neovim's own `gd` (the word's first use). The Generic Editor binds `F3` to Open Hyperlink, which is also how Language Server files go to a definition, so there `gd` does what `F3` does, including its hyperlink chooser |
| `K` | Show Tooltip Description (`F2`): the hover information |
| `gt`, `{N}gt` | next editor tab, or tab N |
| `gT`, `{N}gT` | previous editor tab, or N tabs back |

These mappings are made after your `init.lua` runs, so they replace your own mappings of those keys.
To keep Neovim's meaning instead, put this in `init.lua`:

```lua
vim.g.eclipse_ide_keys = 0   -- or false
```

## Where this lives in the code

- `util/VimCtrlKeys`: the letters, their defaults (`ECLIPSE_BY_DEFAULT`), and what each does in Vim.
- `prefs/CtrlKeysPreferencePage`: the check boxes.
- `core/LuaScripts` (`SETUP`) and `editor/IdeActions`: the keys handed to Eclipse.
- `editor/VimKeyContexts`: activates the contexts of the letters sent to Neovim.
- `editor/KeyInterceptor`: routes every other key.
