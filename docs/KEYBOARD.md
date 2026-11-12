# Keyboard: who gets which key

Every key press in an attached editor goes either to Neovim or to Eclipse. This page explains the
rules, and why three `Ctrl` keys stay with Eclipse unless you change them. For the mechanism (key
bindings, contexts, the order in which Eclipse looks at a key) see
[LEARNING.md](LEARNING.md), part 1.6.

## The rules

| Situation | Keys go to |
|-----------|------------|
| Normal, visual and operator-pending mode | Neovim, except the `Ctrl` keys left to Eclipse (below) |
| Insert and replace mode | Eclipse, which types the text; `Esc` hands control back to Neovim. The `Ctrl` letters ticked for insert mode (`Ctrl+W`, `Ctrl+U`, ...) go to Neovim after the typed text (below) |
| Insert mode started from blockwise visual (`Ctrl+V` then `I`, `A`, `c`), or while recording a macro | Neovim, so it sees every key; the `Ctrl` letters ticked for insert mode too, also ones Eclipse binds (the Java editor's `Ctrl+O`) |
| Keys typed within milliseconds of entering insert mode, or of a `Ctrl` key Neovim handles there, before Eclipse has Neovim's text | Neovim, so nothing is lost; then Eclipse types again |
| The key after `Ctrl-R`, `Ctrl-V`, `Ctrl-Q`, `Ctrl-K`, `Ctrl-G` or `Ctrl-\` (keys after which Neovim waits for another), and any typed before Neovim has it | Neovim; then Eclipse types again |
| `:`, `/`, `?` in normal mode | Neovim, which opens its command line; the editor shows it in the `:` box, whose keys all go to Neovim (Paste and Copy stay Eclipse's, acting on the box) |
| `ZZ`, `ZQ`, and the file and tab commands (`:w`, `:wa`, `:q`, `:bd`, `:e`, `:tabn`, ... see docs/USER_GUIDE.md, "Command line") | Eclipse's save, Save All, close, open and tab switching |
| `gd`, `Ctrl-]`, `K`, `grn`, `gra`, `grr`, `gri`, `grt`, `gO`, `gt`, `gT` in normal mode (`gra` in visual mode too) | Neovim first, which hands them to Eclipse (below) |
| The left mouse button in normal and visual mode | Neovim's own mouse (`nvim_input_mouse`); Ctrl/Cmd+click, the right and middle buttons stay Eclipse's (docs/USER_GUIDE.md, "Mouse") |
| The vertical mouse wheel and touchpad in normal and visual mode | Neovim, which scrolls its window by the lines the system reports and drags the cursor, as `Ctrl-E`; the editor follows. With Ctrl, Shift or Cmd held, sideways, and in insert mode: Eclipse (docs/USER_GUIDE.md, "The editor's size, scrolling and word wrap") |
| Eclipse mode (the Neovim toolbar button, the logo) | Eclipse, always |

## `Ctrl` keys

Eclipse looks at a `Ctrl` key before the editor does, so a letter only reaches Neovim if the plug-in
claims it. *Window → Preferences → Neovim → Ctrl Keys* has two check boxes per letter, each with
Vim's meaning beside it: **Normal** (normal and visual mode, and the `:` box) and **Insert** (insert
and replace mode, below). Ticked letters go to Neovim there, cleared ones keep their Eclipse shortcut.
In the box, `Ctrl+V` pastes and `Ctrl+C` with text selected copies (on Windows and Linux). Changes
apply at once.

In normal mode, by default every letter goes to Neovim **except these two**, on every system:

| Key | Eclipse (Windows, Linux) | Vim | Why it stays with Eclipse |
|-----|--------------------------|-----|---------------------------|
| `Ctrl+S` | Save | nothing in normal mode | Saving with the usual shortcut matters more than a key Vim does not use. |
| `Ctrl+Z` | Undo | suspend Neovim | Suspending makes no sense for an embedded Neovim, and Vim's undo is `u`. Eclipse's undo also undoes Neovim's changes, because they are applied to the Eclipse document, and the result is pushed back to Neovim. |

**`Ctrl+W`** goes to Neovim, though Eclipse closes the editor with it on Windows and Linux: it starts
Vim's window commands, which work on Eclipse's editors, each Vim window being an editor and each split
an editor stack (docs/USER_GUIDE.md, "Splits"; docs/design/SPLITS.md). `Ctrl+W s` and `v` split, `Ctrl+W h/j/k/l`
move, `Ctrl+W +` sizes, `Ctrl+W o` is `:only`; `Ctrl+W q`, `Ctrl+W Ctrl+Q` and `Ctrl+W c` close the
editor (as `:q` and `:close` do; in a floating window you entered, they close that window, as in
Neovim); `Ctrl+W T` moves the editor out of its split, as a tab. Your own mappings of these keys win.
Eclipse's *Close* is still `Ctrl+F4` (and `Cmd+W` on macOS), the tab's close button, or `:q`; clear
the Normal box of `Ctrl+W` to give it back its Eclipse meaning (`:wincmd l` and `:split` work either
way).

Tick any of them to send it to Neovim instead.

**"Keybinding conflicts occurred" in the Error Log.** Eclipse warns about this when a Java editor opens:
`Ctrl+I`, `Ctrl+O` and `Ctrl+T` have bindings of the Java editor (Correct Indentation, Quick Outline,
Quick Type Hierarchy) and of the plug-in, in contexts where neither is the parent of the other, so
Eclipse picks no command. The key then reaches the editor widget, and the plug-in sends it to Neovim
as usual: `Ctrl+O` still jumps back. The warning is harmless. Clearing a letter's check box removes
its conflict and gives the key back to Java.

### `Ctrl` with punctuation: `Ctrl-^`, `Ctrl-]` and the others

Vim names five more `Ctrl` keys after punctuation: `Ctrl-^`, `Ctrl-]`, `Ctrl-\`, `Ctrl-_` and
`Ctrl-@`. They are not in the preferences: they go to Neovim in normal and visual mode (in insert
mode Eclipse types, as for every key), unless an Eclipse key binding takes one first, as for any key. In ASCII each is the
punctuation's code minus 0x40 (`^` is 0x5E, `Ctrl-^` is 0x1E), and systems report them either as that
code or as the punctuation with `Ctrl` held; `util/KeyMapping` reads both, from one table.

| Key | Typed as (US keyboard) | Does |
|-----|------------------------|------|
| `Ctrl-^` | `Ctrl+Shift+6`, or `Ctrl+6` as in Vim's GUIs | edits the alternate file: toggles between two files' editors, the window moving along as for `Ctrl-O` |
| `Ctrl-]` | `Ctrl+]` | Open Declaration, as `gd` (below): Vim's tag jump, and the plug-in has no tags file |
| `Ctrl-\`, `Ctrl-_`, `Ctrl-@` | `Ctrl+\`, `Ctrl+Shift+-`, `Ctrl+Shift+2` | Neovim's meaning (`Ctrl-\ Ctrl-N`: normal mode) |

`Ctrl-[` is `Esc` (ASCII 27), as in Vim. On Windows, `Ctrl+Alt` is AltGr, which types `\` and `]` on
many keyboards, so a key with both is typed, not read as one of these.

### macOS

The bindings use the physical **Control** key on macOS, while Eclipse's shortcuts use **Command**
(`Cmd+S` saves, `Cmd+W` closes). So on a Mac all `Ctrl` letters can safely go to Neovim;
`Control+S` and `Control+Z` stay unticked, as elsewhere. A letter left to Eclipse there usually has
no Eclipse binding, and then does nothing.

`Cmd` keys never reach Neovim (`util/KeyMapping`). One that Eclipse has no binding for (say `Cmd+U`)
is left to Eclipse and does nothing; it used to reach Neovim as the bare letter, so `Cmd+U` undid
a change and `Cmd+P` put a register.

On Linux SWT reports the **Super** (Windows logo) key as `Cmd`, and the text widget would type the
letter of a Super chord the desktop does not take: `Super+U` put a `u` in the file. So outside macOS
the editor drops such a key: it is neither typed nor sent (`KeyMapping.dropsKey`). It is not sent as
Neovim's `<D-u>` either: unmapped, Neovim types that as the text `<D-u>` in insert mode and takes it
as `u` (undo) in normal mode.

### Insert mode

In insert mode Eclipse types, so content assist, auto-indent and templates keep working, and the
`Ctrl` letters not ticked for insert mode keep their Eclipse meaning: `Ctrl+Space` for content
assist, `Ctrl+V` to paste (on Windows and Linux), and so on.

By default these ten go to Neovim in insert mode on Windows and Linux:

| Key | Vim | Eclipse shortcut it replaces there |
|-----|-----|------------------------------------|
| `Ctrl+W` | delete the word before the cursor | Close editor |
| `Ctrl+U` | delete back to where the insert began | — |
| `Ctrl+R` | insert a register (`Ctrl+R "`, `Ctrl+R a`, `Ctrl+R =`) | Run to Line, while debugging |
| `Ctrl+O` | one normal-mode command, then back to insert | Java: Quick Outline |
| `Ctrl+T` | indent the line | Java: Quick Type Hierarchy |
| `Ctrl+D` | unindent the line | Delete Line |
| `Ctrl+K` | enter a digraph (`Ctrl+K e :` types `ë`) | Find Next |
| `Ctrl+Q` | insert the next key literally (`Ctrl+V` stays Paste) | Last Edit Location |
| `Ctrl+N`, `Ctrl+P` | word completion: Eclipse's *Word Completion* (below) | New, Print |

`Ctrl+A`, `Ctrl+C`, `Ctrl+V`, `Ctrl+X`, `Ctrl+Y`, `Ctrl+Z`, `Ctrl+S` and `Ctrl+F` stay Eclipse's
(select all, copy, paste, cut, redo, undo, save, find): people use them while typing. So do `Ctrl+B`
and `Ctrl+L`, which mean nothing in Vim's insert mode; `Ctrl+H`, `Ctrl+I`, `Ctrl+J` and `Ctrl+M`,
whose Vim meanings are Backspace, Tab and Enter, keys the keyboard has anyway (Eclipse's Search,
Correct Indentation, Incremental Find and Maximize stay); `Ctrl+E` (Quick Switch Editor) and
`Ctrl+G`. Each can be ticked on the *Ctrl Keys* page. On macOS, where Eclipse's
shortcuts use Command, every letter with a Vim insert-mode meaning goes to Neovim, `Ctrl+A`,
`Ctrl+E` and `Ctrl+K` included.

**How it works: the sync point.** Neovim has not seen what Eclipse typed, so such a key first hands
it over: the typed text is re-typed into Neovim's open insert, the document is pushed, Neovim's
cursor goes to the caret, and then the key is sent. Neovim does the work, exactly as Vim: after
`foo bar`, `Ctrl+W` deletes `bar`; `Ctrl+U` deletes only what this insert typed; `.` later repeats
the whole insert, `Ctrl+W` included. Eclipse then types on. This takes about a millisecond; a key
typed in that moment goes to Neovim, as at the start of an insert, and Eclipse types the next one.

After `Ctrl+R`, `Ctrl+V`, `Ctrl+Q`, `Ctrl+K` (digraph), `Ctrl+G` and `Ctrl+\` Neovim waits for one
more key (a register name, a digraph's letters, a character code), so that key goes to Neovim.
Once Neovim has it, Eclipse types again, with content assist and the like: `Ctrl+R a` inserts
register `a`, and the next letter is Eclipse's. `.` still repeats the whole insert.
After `Ctrl+O`, Neovim runs one normal-mode command, and Eclipse types again when the insert goes on.

**Completion is Eclipse's.** Neovim's completion popup is not drawn in the editor, so these keys,
when ticked for insert mode, run Eclipse's instead (`Ctrl+N` and `Ctrl+P` are ticked by default;
`Ctrl+X`, for its `Ctrl+X Ctrl+O`, on macOS only):

| Key | Runs |
|-----|------|
| `Ctrl+N`, `Ctrl+P` | *Word Completion* (`Alt+/`): the nearest matching word before the caret first, then, pressed again, the next one, from all open editors. It cannot go back, so both keys do the same. |
| `Ctrl+X` `Ctrl+O` | *Content Assist* (`Ctrl+Space`), as Vim's omni completion. `Ctrl+X` `Ctrl+N` or `Ctrl+P`: Word Completion. `Ctrl+X` then any other key: that key, as usual. |
| `Ctrl+C` | Ends the insert, exactly as `Esc` (`.` repeats it). |

In an insert Neovim types (after a block-visual `I`, while recording), these go to Neovim, which
inserts its first match without showing the list.

## Other keys

- `Esc` always reaches Neovim, including in insert mode, where it first hands Eclipse's typing to
  Neovim so that `.` can repeat it. When Eclipse takes `Esc` first (to leave linked editing, as in a
  Java rename or a template, or to close the completion popup), the same `Esc` still ends insert mode,
  as in Vim, where `Esc` closes the completion menu and leaves insert mode at once.
- `Enter` that accepts a rename you began in normal mode (`Alt+Shift+R`, then `ea` and a suffix, or
  `ciw` and a new name) also ends insert mode: the insert was only for the new name, as Vim's `ciw`
  ends with the change. A template inserted while you type (content assist's `fore`) keeps insert
  mode after its `Enter`, which puts the caret where the typing goes on (`util/LinkedEditInsert`).
- Any other key with an Eclipse key binding is taken by Eclipse first, in every mode: function keys
  such as `F3` (Open Declaration) and most `Ctrl+Shift+...` and `Alt+Shift+...` shortcuts.
- The `z` fold commands (`zo`, `zc`, `zM`, ...) are Neovim's like any normal-mode key. In an editor
  that folds they act on Eclipse's folds, which are mirrored into Neovim. There `zf`, `zF`, `zd`,
  `zD` and `zE` and `:fold` show `zf: Eclipse defines the folds in this editor` instead, since
  Eclipse decides where folds are (docs/USER_GUIDE.md, "Folding"). The keys are caught by a buffer-local
  mapping of `z` alone that reads the next key without a time limit (two-key mappings timed out,
  and a slowly typed `zE` was Neovim's); `:fold` is refused when its line runs, like the host
  commands. In an editor that cannot fold they are Neovim's own.
- Keys Eclipse has no binding for reach Neovim in normal and visual mode: `Alt+letter` as `<A-x>`
  (`Alt+Shift+A` as `<A-A>`, `Alt+1` as `<A-1>`, `Alt+<` as `<A-lt>`), `Ctrl` with a character that
  is no Vim Ctrl key as `<C-2>` or `<C-lt>`, arrows, `Home`, `End`, unbound function keys as `<F5>`
  and so on. On macOS, an Option key that types a character (`Option+A` is `å`) types it.

## Keys Neovim hands to Eclipse: `gd`, `Ctrl-]`, `K`, `gr*`, `gO`, `gt`, `gT`, `ZZ`, `ZQ`

Some normal-mode keys mean something only the IDE can do. The plug-in maps them in Neovim to a small
Lua function that sends an `eclipse_action` notification back to Eclipse (`core/IdeAction`), so Neovim
still handles counts, the `g` prefix and arguments (`fZ` finds a `Z`; it is not the start of `ZZ`):

| Key | Eclipse runs |
|-----|--------------|
| `gd` | the command bound to `F3` in this editor (Open Declaration in the Java editor); in an editor without one (the plain Text Editor), or where that command cannot run (another plug-in may bind `F3` for every text editor), Neovim's own `gd` (the word's first use). The Generic Editor binds `F3` to Open Hyperlink, which is also how Language Server files go to a definition; there `gd` does what `F3` does where the editor knows a definition under the cursor, and is Neovim's own `gd` elsewhere. In short: `gd` goes to a definition when Eclipse knows one, else it is Vim's `gd`, so a plain `.txt` file behaves the same in the Text Editor and the Generic Editor. URLs and file paths are not followed by `gd` in either; that is `Ctrl`+click |
| `Ctrl-]` | the same as `gd` (Vim's jump to the tag under the cursor; Neovim's language-server setup sends it to the definition too) |
| `K` | Show Tooltip Description (`F2`): the hover information |
| `grn` | Rename: the command bound to `Alt+Shift+R` in this editor (Java's Rename refactoring) |
| `gra` | Quick Fix (`Ctrl+1`), in every text editor; in visual mode (`v`, `V`) on the selection, so Quick Assists such as *Extract Local Variable* work on it |
| `grr` | References: the command bound to `Ctrl+Shift+G` (Java's References in Workspace; the Generic Editor's Find References for Language Server files) |
| `gri` | Open Implementation (Java) |
| `grt` | Open Declared Type (Java): the type of the variable or expression under the cursor, as `Ctrl`+click offers it |
| `gO` | Quick Outline (Java's `Ctrl+O`, which is Neovim's `Ctrl-O` here) |
| `gt`, `{N}gt` | next editor tab, or tab N |
| `gT`, `{N}gT` | previous editor tab, or N tabs back |
| `Ctrl-O`, `Ctrl-I` | Neovim's own while its jump history has an older (newer) entry; at its end, Eclipse's Back (`Alt+Left`) or Forward (`Alt+Right`), with the count. Each editor is a Neovim window with its own jump history, and switching editors in Eclipse (a tab click, `Ctrl+F6`, `F3`) is no Neovim jump, so `Ctrl-O` after a tab click goes back through Eclipse's history instead of doing nothing. Back steps over the switches the plug-in made itself for Neovim's jumps (those are in Neovim's history already), and `Ctrl-I` retraces `Ctrl-O` (`editor/NavigationHistorySteps`, [design/WINDOWS.md](design/WINDOWS.md), E2). As Eclipse's Back does, they can reopen an editor you closed (`:bd`); Neovim alone would not. A place whose file was deleted since is stepped over: Eclipse's Back would open an error page there. Not where `init.lua` maps the key itself |
| `ZZ` | save the editor if it has changes, then close it (Vim's `:x`); a failed save keeps it open |
| `ZQ` | close the editor without saving (Vim's `:q!`) |

`grn`, `gra`, `grr`, `gri`, `grt` and `gO` are Neovim's own defaults for a language server
(`:help grn`); here Eclipse is that server, so they run its commands. Where the editor has no such
command (Rename in a plain text file) the message line says so:
`grn: Rename is not available in this editor`. `grx` (run a code lens) has no Eclipse counterpart
and stays Neovim's. Which Eclipse command each key runs, and how it is found, is one table in the
code (`core/IdeKeys`), which also makes Neovim's mappings.

These mappings are made after your `init.lua` runs, so they replace your own mappings of those keys,
except `Ctrl-O` and `Ctrl-I`: those keep yours, and that is also how to turn their fallback off,
for Vim's exact "nothing at the end of the history":

```lua
vim.keymap.set('n', '<C-o>', '<C-o>')
vim.keymap.set('n', '<C-i>', '<C-i>')
```

A count larger than the jump history (`5<C-o>` with two older jumps) does nothing, as in Neovim;
only with no older jump at all does `Ctrl-O` go to Eclipse. Whether Neovim gets `Ctrl+O` and
`Ctrl+I` at all is the *Ctrl Keys* preference (above); cleared, they are Eclipse's own keys and
none of this applies.
To keep Neovim's meaning instead, put this in `init.lua`:

```lua
vim.g.neovim4eclipse_ide_keys = 0   -- or false
```

`ZZ`, `ZQ` and the `Ctrl-O` / `Ctrl-I` fallback stay either way: Neovim's own would quit the embedded Neovim, which every
editor shares.

## Where this lives in the code

- `util/VimCtrlKeys`: the letters, their defaults (`ECLIPSE_BY_DEFAULT`), and what each does in Vim.
- `prefs/CtrlKeysPage`: the check boxes (`CtrlKeysPreferencePage` gives it the plug-in's store).
- `core/LuaScripts` (`SETUP`) and `editor/IdeActions`: the keys handed to Eclipse.
- `editor/VimKeyContexts`: activates the contexts of the letters sent to Neovim.
- `core/KeyRouter`: decides who handles every other key (unit-tested in `KeyRouterTest`);
  `editor/KeyInterceptor` is the SWT listener that feeds it.
