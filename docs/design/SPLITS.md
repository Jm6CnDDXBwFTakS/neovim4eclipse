# Vim splits as Eclipse editor stacks

Each Vim window is an Eclipse editor here ([WINDOWS.md](WINDOWS.md)): one Neovim window per editor,
alone in a hidden tabpage. So a Vim split cannot be a second Neovim window in that tabpage, which
Eclipse would not show; it is a second Eclipse editor, beside the first, in an editor stack of its
own. `:split` puts it there, as dragging an editor's tab to the edge of the editor area would, and
the `Ctrl-W` keys, the sizes and `:only` work on the editor area as Vim's work on its windows.

## 1. The mechanism: a stack for every split

Since Eclipse 4 the workbench is an application model ([LEARNING.md](../LEARNING.md) has the basics;
`editor/EditorTabs` the first use). The editor area is an `MArea`, a sash container: its children
are laid out side by side or one above the other (`MGenericTile.isHorizontal()`), and each child is
an editor stack (`MPartStack`, a row of tabs) or another sash container. That is Vim's tree of
frames: rows and columns of windows, each stack's front editor one window.

```
  +--------+--------+       MArea (side by side)
  |        | B.java |       ├── MPartStack   front: A.java   (window 1)
  | A.java +--------+       └── MPartSashContainer (one above the other)
  |        | C.java |           ├── MPartStack   front: B.java   (window 2)
  +--------+--------+           └── MPartStack   front: C.java   (window 3)
```

`EModelService` changes it in code:

- `insert(stack, beside, EModelService.RIGHT_OF, 0.5f)` puts a new stack beside another, taking
  half its space, and makes a sash container around the two when the side does not match the
  parent's direction;
- `move(part, stack)` moves an editor into a stack, the code equivalent of dragging its tab;
- each child's `containerData` is its weight (`"5000"`): setting the weights moves the sashes.

An emptied stack removes itself (Eclipse's cleanup add-on), so closing a split's last editor gives
its space back, and nothing is left for the plug-in to tidy. A stack made here gets Eclipse's
`EditorStack` tag, as Eclipse's own editor stacks have it, so it looks and behaves as theirs.

**What the new editor shows** (`editor/EditorSplits.openFor`):

| Split | The new editor |
|-------|----------------|
| `:split`, `:vsplit`, `Ctrl-W s`, `:sbuffer` of this buffer | *Window › Editor › Clone* (`org.eclipse.ui.window.newEditor`), which copies the caret and the scroll position: Vim's new window starts where the old one is. Neovim takes the caret as the cursor of the clone's own window, as for any attach, so the two editors are two windows on one buffer |
| `:split b.txt`, `:sfind`, `:sbuffer 3`, `Ctrl-W ^`, `Ctrl-W f` | the file in a new editor even when another has it open (`IWorkbenchPage.MATCH_NONE`): a Vim window of its own is what was asked for. A missing file opens as a new one, as for `:e` ([NEW_FILE.md](NEW_FILE.md)) |
| `:new`, `:vnew`, `Ctrl-W n` | a new untitled editor, as `:enew` opens |

The editor opens in this editor's stack, as any editor does, and is then moved into the new stack,
inside one `setRedraw(false)`, so the screen shows the result only. The stack it came from shows
its old front tab again.

**Rejected: Eclipse's own split halves** (*Toggle Split Editor*), the other way an editor shows
twice. They are two halves of one editor, in one tab: there are only two, the halves close together,
and a file opened from Eclipse replaces both. Vim's windows nest any number deep either way and
close one by one; stacks do both. The cost: a split of the same file shows its tab twice, once in
each stack. Eclipse asks about saving only when the last editor of a document closes.

## 2. Neovim decides what a command means

A typed `:split` reaches Eclipse as `:Eclipse window split above same`, through the host commands'
`translate` hook (`LuaScripts.HOST_COMMANDS`): `CmdlineLeavePre` parses the line with
`nvim_parse_cmd()`, and a translated command becomes the `:Eclipse` line, so the history keeps the
line as typed and `@:` repeats it. A `Ctrl-W` key sends the same words itself.
`LuaScripts.WINDOW_COMMANDS` writes the words and `util/WindowCommand` reads them.

Neovim works out everything Vim would, before it sends one:

- **The side**: above, or left for `:vsplit`, unless `'splitbelow'` or `'splitright'` is set;
  `:aboveleft` and `:belowright` say it for one command, and so do `:topleft` and `:botright`,
  taken as those two (see "Not done").
- **The file**: named as `:e` names it (`expandcmd()`, then the full path against the window's
  folder), found in `'path'` for `:sfind` and `Ctrl-W f` (`findfile()`, which also tries
  `'suffixesadd'`), buffer N's for `:sbuffer N` and `Ctrl-W ^`. A file that is this buffer's makes
  a clone. Vim's own errors come from Neovim: `E345` for a name not in `'path'`, `E86`, `E23`.
- **The count**: `3 Ctrl-W w` is `window focus w 3`; `:resize +5` is `window size + 5`.

So Eclipse gets plain words and needs no Vim knowledge. A `+cmd` (`:split +10 b.txt`) is refused:
Eclipse would have to run it in the new editor only, once Neovim attaches there.

**Vim's tab pages are Eclipse's editor tabs.** Each editor is a Neovim window alone in a hidden
tab page, so Neovim's own tab pages are the plug-in's plumbing; the tab commands work on the
editor tabs of this stack instead, which are what a Vim user sees as tabs:

| Command | Eclipse does |
|---------|--------------|
| `:tabedit file`, `:tabfind file`, `:tab split`, `:tab sbuffer N` | a new editor tab in this stack, by the same rules for the file as a split's (a new editor even when one has the file open) |
| `:tabnew`, `:tabedit` alone, `:tab new` | a new untitled editor tab |
| `Ctrl-W T` | this editor leaves its split, as a tab of the first other stack on screen (top left), and stays the active one: Vim moves the window into a tab page of its own |
| `:tabclose 2`, `:tabclose $`, `:tabclose +`, `:tabclose -2` | closes that editor tab of this stack, counted as `:tabnext` counts them (`TabNavigation.closeTarget`; no wrapping, Vim's `E16`). Eclipse asks about its changes unless `!`, or another editor shows the document |

A count in front of `:tabclose` (`:2tabclose`, `:$tabclose`, `:+tabclose`) is refused with a
hint: Neovim has turned it into one of its hidden tab pages before the plug-in sees the line, which
says nothing about the editor tabs. `:tabclose` alone closes this editor, as before.

**Refused**, with a message: `:tabonly`, which would close every other editor's window, and the
splits that jump first (`:stag`, `Ctrl-W ]`, `Ctrl-W F`; see "Not done"). A window a plug-in opens in Neovim beside an editor's is still closed by the `WinNew`
safety net of `LuaScripts.WINDOWS`: Eclipse never heard of it.

## 3. The window commands on the editor area

`util/WindowLayout` answers the questions on a picture of the area (`EditorSplits.Picture`): nodes
for the rows and columns, a window for each stack's front editor, with its bounds in display
pixels. No Eclipse code, so `WindowLayoutTest` tests the rules.

| Keys | Rule |
|------|------|
| `Ctrl-W h j k l` | The nearest window on that side among those beside this one (overlapping it across); of several, the one level with the caret, as Vim takes the window at the cursor's row. A count goes that many on |
| `Ctrl-W w W t b` | Vim's order, depth first: the next or previous window (wrapping), window N with a count (the last when there are fewer), the first, the last |
| `Ctrl-W p` | The editor active before this one (`EditorSplits.activated`, from the part listener), while it is on screen |
| `Ctrl-W + - _`, `< > \|` | In the nearest column (row) holding this window: the new size in pixels from the text's line height (a character's average width), and the space taken from the windows after it, then before it, each keeping one line and its tab row; a window that shrinks gives the space to the next one. The sizes become the children's weights, on the scale their weights have, so a child the picture leaves out (a view dropped into the editor area) keeps its share |
| `Ctrl-W =` | Every row and column shares its space equally |
| `Ctrl-W x` | This stack's front editor and the next stack's (or the previous, for the last; the Nth with a count) change places, and the cursor stays where it was on screen, so the other editor is the active one: Vim's `win_exchange` enters the window that came to the cursor's place |
| `Ctrl-W r R` | Every front editor of the row or column moves one place on; the cursor goes with its editor. A row that holds a split of its own is Vim's `E443` |
| `Ctrl-W o`, `:only` | The other stacks' editors become tabs of this stack; nothing is closed, since an Eclipse editor may hold changes no Vim window would lose. Eclipse's split halves are joined again |
| `Ctrl-W q c`, `:q`, `:close` | Close the editor (host command `QUIT`, as before); its stack goes with its last editor |

## 4. Eclipse's *Toggle Split Editor*

In an editor Neovim drives, the command (`org.eclipse.ui.window.splitEditor`, `Ctrl+_` and
`Ctrl+{`) makes a Vim split: `editor/SplitEditorHandler` asks Neovim for `:split` or `:vsplit`
(`LuaScripts.SPLIT_WINDOW`, so the two options decide the side), and when more than one editor is
on screen it runs `:only`, so the key still toggles. Elsewhere (an editor in Eclipse mode, one
Neovim does not drive) it makes Eclipse's halves, as always.

- **Why the plug-in's handler wins.** A command has one active handler, chosen by the handlers'
  `activeWhen` expressions. Eclipse registers its own (`org.eclipse.ui.internal.SplitHandler`) with
  an `enabledWhen` and no `activeWhen`, which makes it the default; the plug-in's has an
  `activeWhen` (an editor is active), and a handler whose expression holds wins over one without.
- **Which is which.** The parameter `Splitter.isHorizontal` is `true` for `Ctrl+_`: a horizontal
  line divides the editor, so the halves are one above the other, as `:split` has them.
- **Eclipse's halves, done again.** Eclipse's handler is internal, so the plug-in does what it does
  by the same public means: it toggles a tag on the editor's model part
  (`IPresentationEngine.SPLIT_HORIZONTAL` or `SPLIT_VERTICAL`), and Eclipse's splitter add-on makes
  or removes the halves. `:only` removes the tags too.
- **The hidden preference `eclipseSplits`** (`PreferenceConstants.ECLIPSE_SPLITS`, default
  `false`, no control on any page): `true` gives the command back to Eclipse's halves in Neovim's
  editors too. It is kept in case the halves turn out to be wanted: the key, the default, the
  handler's check and a UI test are in place, so offering it takes one checkbox and the docs. It is
  read each time the command runs (a preference, no Neovim call), and it is not supported until
  then: the settings the plug-in promises to keep leave it out.

**Eclipse's halves are windows too** (from the preference, or a workspace that had them): the
stack's front tab is a composite part, with the two halves' parts in a sash container of its own,
sized by `containerData` like the stacks. So the picture takes them in as a column or row, and
`Ctrl-W h/j/k/l`, the sizes and `:only` work on them. `Ctrl-W x` and `r` move editors between
stacks, and say that the halves cannot.

## 5. `Ctrl+W`

On Windows and Linux `Ctrl+W` closes the editor, and stays Eclipse's by default: it is what Eclipse
users expect of it. Ticked under *Ctrl Keys*, it goes to Neovim, and the `Ctrl-W` keys work. On
macOS Eclipse closes with `Cmd+W`, so `Control+W` goes to Neovim by default
(`VimCtrlKeys.ECLIPSE_BY_DEFAULT_MAC`). `:wincmd` and the `:` commands work either way. The keys are
Neovim mappings, made only where the user's config maps none of them itself.

## 6. Experiments

Throwaway UI tests in the UI tests' Eclipse (the oldest release the plug-in supports), on Linux:

| Probe | Result |
|-------|--------|
| *Toggle Split Editor* | Two separate editor parts, each attaching Neovim when it gets the focus |
| Toggling it off while the second half is active | The first survives; Neovim attaches to it again and typing works |
| Closing one half | Un-splits, but leaves the focus on nothing |
| `insert` + `move` of an editor beside another | Focus, Neovim's attach and typing follow the editor |
| Closing the last editor of a split's stack | The stack removes itself; the other editor gets its space back |
| Setting two stacks' weights | Median 1.8 ms, at most 5.5 ms; Neovim's window follows the new size |
| The halves' sizes | A composite part holding a sash container with the halves' parts, weights 5000/5000: the same mechanism as the stacks |
| Swapping two stacks' front editors with `move` | About 40 to 45 ms; the widgets survive, Neovim stays attached |

After a restart Eclipse brings the stacks and their clones back, as it does any dragged layout.

## 7. Not done

- `:5split` (a height with the split): the new editor takes half.
- `:topleft` and `:botright` mean "the whole width (height) of the screen" in Vim; here they are
  taken as `:aboveleft` and `:belowright`, beside this editor.
- Splits that jump first: `:stag`, `Ctrl-W ]` (Vim's tags; the plug-in sends `Ctrl-]` to Eclipse's
  *Open Declaration*, which opens its editor later, on its own), `Ctrl-W F` (a line number too),
  `:split +cmd file`.
- A multi-page editor (the POM editor): its clone opens on its first page, where Neovim is not
  attached until its text page is chosen.
- `:tabonly` as Eclipse's *Close Others*: it closes editors, so it waits for its own decision.

## 8. Tests

- `WindowLayoutTest` (tests/unit): the neighbors, the order, the sizes, exchange and rotate.
- `WindowCommandTest` (tests/unit): the words Neovim sends, read.
- `windows_spec.lua` (tests/unit, a headless Neovim): which words each command and key sends, the
  side rule, `'path'`, buffers, the tab commands, the refusals.
- `TabNavigationTest` (tests/unit): which tab `:tabclose 2`, `$`, `+` and `-2` name.
- `SplitsEndToEndTest` (UI tests): `:vsplit`, `:split file`, `Ctrl-W` moves, `:only`, the sizes,
  `Ctrl-W x`, `:tabedit`, `:tab split`, `Ctrl-W T`, `:tabclose 2` (with and without `!`),
  *Toggle Split Editor* both ways, the hidden preference's halves, and `:q` in a half
  (the focus goes back to the other).
