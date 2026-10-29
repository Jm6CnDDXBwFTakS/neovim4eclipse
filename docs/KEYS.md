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
| `:`, `/`, `?` in normal mode | Neovim, which opens its command line; the editor shows it in the `:` box, whose keys all go to Neovim (Paste and Copy stay Eclipse's, acting on the box) |
| `ZZ`, `ZQ`, and the file and tab commands (`:w`, `:wa`, `:q`, `:bd`, `:e`, `:tabn`, ... see the README) | Eclipse's save, Save All, close, open and tab switching |
| `gd`, `K`, `gt`, `gT` in normal mode | Neovim first, which hands them to Eclipse (below) |
| The left mouse button in normal and visual mode | Neovim's own mouse (`nvim_input_mouse`); Ctrl/Cmd+click, the right and middle buttons stay Eclipse's (README, "Mouse") |
| The vertical mouse wheel and touchpad in normal and visual mode | Neovim, which scrolls its window by the lines the system reports and drags the cursor, as `Ctrl-E`; the editor follows. With Ctrl, Shift or Cmd held, sideways, and in insert mode: Eclipse (README, "The editor's size, scrolling and word wrap") |
| Eclipse mode (the Neovim toolbar button, the logo) | Eclipse, always |

## `Ctrl` keys

Eclipse looks at a `Ctrl` key before the editor does, so a letter only reaches Neovim if the plug-in
claims it. *Window → Preferences → Neovim → Ctrl Keys* has one check box per letter: ticked letters go
to Neovim in normal and visual mode and in the `:` box, cleared ones keep their Eclipse shortcut. In the
box, `Ctrl+V` pastes and `Ctrl+C` with text selected copies (on Windows and Linux). Changes apply at once.

By default every letter goes to Neovim **except these three**:

| Key | Eclipse (Windows, Linux) | Vim | Why it stays with Eclipse |
|-----|--------------------------|-----|---------------------------|
| `Ctrl+S` | Save | nothing in normal mode | Saving with the usual shortcut matters more than a key Vim does not use. |
| `Ctrl+W` | Close editor | window commands (`Ctrl+W s`, `Ctrl+W q`, ...) | Each editor is one Neovim window, alone in its hidden tab (docs/design/WINDOWS.md), so splits and window moves have nothing to show. Given to Neovim, `Ctrl+W q`, `Ctrl+W Ctrl+Q` and `Ctrl+W c` close the editor (as `:q` and `:close` do; in a floating window you entered, they close that window, as in Neovim), and `Ctrl+W s`, `v`, `n` and `T` are refused with a message pointing to Eclipse's split editor; your own mappings of these keys win. Closing the editor stays the default because it is what Eclipse users expect. |
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
  Neovim so that `.` can repeat it. When Eclipse takes `Esc` first (to leave linked editing, as in a
  Java rename or a template, or to close the completion popup), the same `Esc` still ends insert mode,
  as in Vim, where `Esc` closes the completion menu and leaves insert mode at once.
- Any other key with an Eclipse key binding is taken by Eclipse first, in every mode: function keys
  such as `F3` (Open Declaration) and most `Ctrl+Shift+...` and `Alt+Shift+...` shortcuts.
- The `z` fold commands (`zo`, `zc`, `zM`, ...) are Neovim's like any normal-mode key. In an editor
  that folds they act on Eclipse's folds, which are mirrored into Neovim. There `zf`, `zF`, `zd`,
  `zD` and `zE` and `:fold` show `zf: Eclipse defines the folds in this editor` instead, since
  Eclipse decides where folds are (README, "Folding"). The keys are caught by a buffer-local
  mapping of `z` alone that reads the next key without a time limit (two-key mappings timed out,
  and a slowly typed `zE` was Neovim's); `:fold` is refused when its line runs, like the host
  commands. In an editor that cannot fold they are Neovim's own.
- Keys Eclipse has no binding for reach Neovim in normal and visual mode: `Alt+letter` as `<A-x>`,
  arrows, `Home`, `End`, unbound function keys as `<F5>` and so on.

## Keys Neovim hands to Eclipse: `gd`, `K`, `gt`, `gT`, `ZZ`, `ZQ`

Some normal-mode keys mean something only the IDE can do. The plug-in maps them in Neovim to a small
Lua function that sends an `eclipse_action` notification back to Eclipse (`core/IdeAction`), so Neovim
still handles counts, the `g` prefix and arguments (`fZ` finds a `Z`; it is not the start of `ZZ`):

| Key | Eclipse runs |
|-----|--------------|
| `gd` | the command bound to `F3` in this editor (Open Declaration in the Java editor); in an editor without one (the plain Text Editor), or where that command cannot run (another plug-in may bind `F3` for every text editor), Neovim's own `gd` (the word's first use). The Generic Editor binds `F3` to Open Hyperlink, which is also how Language Server files go to a definition; there `gd` does what `F3` does where the editor knows a definition under the cursor, and is Neovim's own `gd` elsewhere. In short: `gd` goes to a definition when Eclipse knows one, else it is Vim's `gd`, so a plain `.txt` file behaves the same in the Text Editor and the Generic Editor. URLs and file paths are not followed by `gd` in either; that is `Ctrl`+click |
| `K` | Show Tooltip Description (`F2`): the hover information |
| `gt`, `{N}gt` | next editor tab, or tab N |
| `gT`, `{N}gT` | previous editor tab, or N tabs back |
| `ZZ` | save the editor if it has changes, then close it (Vim's `:x`); a failed save keeps it open |
| `ZQ` | close the editor without saving (Vim's `:q!`) |

These mappings are made after your `init.lua` runs, so they replace your own mappings of those keys.
To keep Neovim's meaning instead, put this in `init.lua`:

```lua
vim.g.neovim4eclipse_ide_keys = 0   -- or false
```

`ZZ` and `ZQ` stay mapped either way: Neovim's own would quit the embedded Neovim, which every
editor shares.

## Where this lives in the code

- `util/VimCtrlKeys`: the letters, their defaults (`ECLIPSE_BY_DEFAULT`), and what each does in Vim.
- `prefs/CtrlKeysPreferencePage`: the check boxes.
- `core/LuaScripts` (`SETUP`) and `editor/IdeActions`: the keys handed to Eclipse.
- `editor/VimKeyContexts`: activates the contexts of the letters sent to Neovim.
- `core/KeyRouter`: decides who handles every other key (unit-tested in `KeyRouterTest`);
  `editor/KeyInterceptor` is the SWT listener that feeds it.
