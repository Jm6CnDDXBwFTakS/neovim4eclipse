# Search highlighting

Neovim highlights the matches of the last search (`'hlsearch'`), and every match of a search while
it is typed (`'incsearch'`). The editor shows the same: each match gets a background color behind
its text, and a box around the match under the caret, which Neovim marks with its `CurSearch`
group.
`:noh` takes them away until the next search.

## What is shown

- **After a search** (`/`, `?`, `*`, `#`, `n`, `:s`): every match of register `/` in the lines the
  editor shows, while `v:hlsearch` is 1. That is, while `'hlsearch'` is on and no `:nohlsearch`
  came after the search.
- **While a search is typed**, with `'incsearch'` on: the matches of what is typed so far, without a
  search offset (`/foo/e` shows `foo`). After `?`, and in `:s?a?b?`, an escaped `\?` is a plain
  `?`, as Neovim reads it (`?what\?` shows `what?`, not `wha` and an optional `t`); after `/`,
  `\/` is already a plain `/` to the regexp. A delimiter inside a `[]` collection does not end
  the pattern (`/[/]x` shows `/x`), as Neovim's `skip_anyof` reads it, with `'magic'`, `\v` and
  `\V` deciding whether `[` or `\[` begins one. Before the first character, the last search's, which
  Neovim still shows. The same for the pattern of a `:s`, `:g`, `:v`, `:smagic`
  or `:snomagic` line (`:%s/foo/bar` shows `foo`). A range with a pattern of its own
  (`:/x/s/y/`) is not read; the last search shows instead.
- **Nothing** otherwise, and in Eclipse mode. Back in Neovim mode, the matches come back.

The plug-in follows the user's options and `:noh`, and adds no preference of its own. The plug-in's
start-up settings turn `'hlsearch'` and `'incsearch'` on (`LuaScripts.SETUP`); `:set nohlsearch`
in Neovim turns the highlights off.

### Turning it off

`vim.g.neovim4eclipse_search = 0` in `init.lua` stops Neovim reporting matches, as the other
features' `vim.g` switches do. It is read once when Neovim starts.

## How it works

```
Neovim redraws its window ──on_win──▶ vim.schedule ──▶ M.update(win)
   (a search, :noh, a key typed in /, an edit, a scroll)   │ matches in w0..w$
                                                           ▼
                      eclipse_search(buf, ranges, win)  (only when they changed)
                                                           │
NotificationBridge ─▶ NeovimSession (attached window only) ─▶ SearchPort listener
                                                           │
NeovimEditorController ─UI thread─▶ SearchHighlighter.show ─▶ invalidateTextPresentation
                                                           │
                       the viewer rebuilds the presentation; applyTextPresentation adds backgrounds
```

### In Neovim: `LuaScripts.SEARCH`

- **When.** A decoration provider's `on_win` runs at every redraw of a window. A redraw is exactly
  when the highlights may have changed: a new pattern, `:noh`, a typed character, an edit, a
  scroll. `on_win` must not change anything, so it only schedules the work, once per batch:
  twenty `Ctrl-E` give twenty `on_win` calls and one computation.
- **Only the visible lines.** `matchbufline(buf, pattern, top, bottom)` takes 0.1 to 0.2 ms over
  60 lines, whatever the file's size. The whole buffer takes 2.5 ms at 2,000 lines, 8 ms at 10,000
  and 114 ms at 100,000 (Neovim 0.12.6, headless and with a UI attached).
- **Which lines are visible.** The window's own (`w0` to `w$`) and the editor's, which Eclipse
  reports whenever its view changes (a scroll, a resize: `SearchHighlighter.setViewListener`,
  `SearchPort.setSearchViewAsync`, `LuaScripts.SEARCH_VIEW`); both ranges are searched. The
  viewport feature keeps the two the same, but they differ when it is off
  (`vim.g.neovim4eclipse_viewport = 0`, a fixed 120 × 40 screen), when Eclipse scrolled alone in
  insert mode, when Neovim does not know Eclipse's folds (`vim.g.neovim4eclipse_folds = 0`), and by
  a last line the editor shows only in part. Two ranges apart (the viewport off, the editor far
  from Neovim's window) are searched one by one, not with every line between them.
- **Across lines.** `matchbufline()` matches within one line. A pattern that can span lines (`\n`,
  `\_x`) is found with a `searchpos()` walk inside `nvim_win_call`: the start, then the end
  (`'e'` flag), then the view is put back as it was. Each next match is looked for after the end of
  the last one, as Neovim's hlsearch goes on (with `'cpoptions'` `c`, the default): on lines `a`,
  `b`, `c` the pattern `a\nb\|b\nc` paints `a`-`b` only, and on `foo foo`, `bar` the pattern
  `foo\_.\{-}bar` paints one match, without a second scan from the inner `foo`. A match that
  begins at a line break (`b\|\n.` on `ab`, `cd`: the break and `c`) is one Neovim's search puts on
  the line's last character, and so skips from there; the walk looks for it with the pattern tied
  to that spot (`\%1l\%3c` before it, in a group). The walk starts a screen above the top, so a
  match that begins above the view and ends in it is found, and it stops after `'redrawtime'`
  (2 s by default), as Neovim's own highlighting does: `foo\_.*bar` makes each end search scan
  towards the end of the file.
- **Only changes are sent.** Each window's last list is kept, as text. `n`, which moves the cursor
  between the same matches, sends nothing: the editor decides the current match from the caret.
  With matches, the buffer's change count (`changedtick`) is part of the list: after an edit the
  editor may have dropped a match the edit touched (see "Following the text"), and gets them all
  again even where none moved. Example: `:s/a/b/g` on `a foo a` changes the whole line in the
  editor, which drops `foo`, though `foo` is where it was.
  Entering a window or a buffer forgets the window's list, so an editor switched to gets its
  matches at the next redraw. `M.resend()` sends them in any case, after an attach's push (a
  re-attach to the same window enters nothing) and when an editor is back in Neovim mode
  (`SearchPort.resendSearchAsync`, `LuaScripts.SEARCH_RESEND`). It is scheduled too: called
  inside the RPC request, the search state it reads is not the one the window shows.
- **Units.** Each match is four numbers: start line (1-based), start byte column (0-based), end
  line, end byte column (exclusive). A match of no width (`^`) is left out: there is nothing to
  paint.

### Upper and lower case

`matchbufline()` and `searchpos()` follow `'ignorecase'` but not `'smartcase'`, so the pattern
gets an explicit `\c` or `\C`, in the order Neovim's own search decides:

1. a `\c` or `\C` in the pattern wins;
2. `'noignorecase'` means exact case;
3. `'smartcase'` and an upper-case letter means exact case, except for a search made with `*`,
   `#`, `g*` or `g#`, which Vim never makes smart. Which letters count is Vim's
   `pat_has_uppercase`: not one after a backslash (`\S` is a class), nor the one after `\_` or
   `\%` (`\_S`, `\%V`); an accented capital (`É`) counts;
4. otherwise, any case.

Vim keeps "made with `*`" with the pattern, so it holds for `n`, and after `:w` or a search
cancelled with `Esc`. The plug-in follows the pattern the same way: `vim.on_key` notes register
`/` before a `*` or `#` in normal mode, and when the register changed once the key ran, its new
value is the star's pattern. "Once the key ran" is the next key, or, when none follows, work
scheduled with `vim.schedule`, whichever comes first: keys that arrive together (a macro's, a
mapping's) all run before any scheduled work, so in `*:noh<CR>/Foo<CR>` the check must not wait for
it. The highlighted pattern is a star's while it equals that value. A
`*` that is the argument of another command (`"*p`, `f*`, `r*`) changes no register and counts
for nothing. Left over: a search typed to the very pattern `*` made (`/\<Foo\>` after `*` on
`Foo`) still counts as the star's. A spike compared the rules with Neovim's own `searchcount()` in
seven cases (`/Foo`, `/foo`, `*`, `n` after `*`, `:s`, `#`, `noignorecase`); all agreed.

### In Eclipse: behind the text

`editor/SearchHighlighter` turns the byte columns into document offsets
(`DocumentPositions.byteColToOffset`) and paints through JFace's own mechanism: it is an
`ITextPresentationListener` on the editor's viewer. The viewer builds what the widget shows (the
syntax colors) as a `TextPresentation` and lets such listeners add to it. The highlighter merges a
background into the ranges the matches cover (`TextPresentation.mergeStyleRange`, which keeps the
foreground and the font). When the matches change, `invalidateTextPresentation` has the viewer
build that region again. Eclipse's highlighted annotations (Mark Occurrences set to "Highlighted")
work the same way.

Rejected: painting rectangles in a `PaintListener`, as `BlockOverlay` does for `Ctrl-V`. That runs
after the text is drawn, so the match color would cover the text, or need transparency that dims
the syntax colors. A `LineBackgroundListener` colors whole lines only.

The presentation is in document offsets, and the viewer maps it to the widget, so folded code needs
nothing: a match inside a closed fold is not shown. The selection is drawn over the backgrounds.

### Following the text

Between two reports from Neovim the matches follow the text. An edit made on Eclipse's side
reaches Neovim later (in insert mode Eclipse types itself, and Neovim gets the lines at `Esc`;
Format or a Quick Fix changes the document directly), so Neovim sends nothing new meanwhile. A
prenotified document listener, told of each change before the viewer repaints it, moves the
matches after the change by its length and drops one the change touches, as an editor keeps its
markers. Example: `foo` at 10 to 13, `xx` typed at 2: the match is now 12 to 15. The match under
the caret keeps its box where it moved (or loses it when dropped), and is worked out again from the
caret afterward, on the UI thread: the listener runs before the widget has the change, and on the
thread that changed the document.

While Eclipse types, Neovim may still send matches (a view report after a scroll asks for them),
but of its older lines, whose columns would land on the wrong characters; the editor ignores
them, as it ignores Neovim's cursor then. When the insert ends, the editor asks for the matches
again if it ignored any (`resendSearchAsync`, queued after the `Esc` push); an insert without a
search costs nothing more. A match that typing dropped needs no request: the `Esc` push changes
the buffer, and its matches are sent with the new change count.

The match under the caret is the one whose range holds the caret's document offset (from its
start, up to its end). After `n` Neovim's cursor is on a match's first character, so that match is
the current one. While a search is typed, Neovim's cursor sits just after the match it jumped to
(`'incsearch'`), so then the match that ends at the caret is the current one too. It keeps the same background as the others and gets a box: a one-pixel rectangle
around each visual row's part of it (a wrapped line has several), drawn by a `PaintListener` after
the text, at its place in the
widget (`modelOffset2WidgetOffset`, so a match in a closed fold has none). A caret move repaints
only the old and the new box.

Rejected: a stronger background for it, the match color moved toward the text color. In a light
theme that is a darker shade under dark text, which reads well; in Eclipse's dark theme it is a
light gray, and the gray of a Java comment on it could not be read (the gallery's dark pictures,
`SearchGalleryTest`, showed it). Each syntax color is the theme's, so no background is safe under
every one; a box does not sit under the text at all.

### Colors

A color of the plug-in's own, *Search match*: a `colorDefinition` in `plugin.xml`, which Eclipse
lists under *Window → Preferences → General → Appearance → Colors and Fonts → Neovim for Eclipse*,
where users change it, as they change JDT's or Git's colors. Its default is an amber, 255,214,102,
as Vim users know `'hlsearch'`. Eclipse's dark theme gets a darker amber, 110,88,24, from a
stylesheet the plug-in adds to that theme (`css/e4-dark_neovim4eclipse.css`, the
`org.eclipse.e4.ui.css.swt.theme` extension, an optional dependency: an Eclipse without CSS themes
ignores it), as JDT gives its syntax colors a dark default.

A dark editor can come without Eclipse's dark theme: another dark theme, your own dark editor
colors on the light theme, or a high-contrast system setting. So where the registry holds one of the
two defaults, the editor's background picks between them (`OverlayColors.searchMatch`): a dark
background gets the dark amber, a light one the light amber. A color you picked yourself stays,
unless it is exactly one of the defaults, which the registry cannot tell apart.
`SearchMatchColorFilesTest` checks that `plugin.xml`, the stylesheet and `OverlayColors` agree on
both values, and that the stylesheet's selector names the color.

`editor/SearchColors` reads the current theme's color registry and listens to the theme manager (a
change of the color or of the theme), hopping to the UI thread, as either may come on another. The
editor's own colors are watched on the widget, at each paint: they change through the Text Editors
preferences, but some editors set them straight on the widget (a TextMate theme in the Generic
Editor), and the widget repaints after either; a paint with other colors than last time schedules
new highlight colors. While Eclipse shuts down there may be no workbench to ask; the highlights then
take the default for the background.

Until 0.2.6 the matches took Eclipse's *Search Results* annotation color. That made a change of the
color recolor Eclipse's own search marks too, and its lavender default became a gray in the dark
theme that matches barely showed on (the gallery's dark pictures).

The box is the match color moved halfway toward the editor's text color
(`util/OverlayColors.searchBox`): darker in a light theme, lighter in a dark one, so it shows on
the match's background in both.
Neovim's `Search` and `CurSearch` groups are not used: their colors suit Neovim's screen, not the
editor's theme.

## Not done

- **No marks in the overview ruler.** Eclipse's own Find overlay (2024-12 and 2026-09) marks none
  either; ruler marks come only from Mark Occurrences and the Search view. They would need every
  match in the file, which costs 8 ms at 10,000 lines and 114 ms at 100,000 at each change.
- **Other highlights:** the yank flash, `:match`, other plug-ins' extmarks.
- **`'inccommand'`'s live preview of `:s`** is not shown, as before.

## Tests

- `tests/unit/src/test/lua/search_spec.lua`: which pattern (`/`, `?`, `:s`, `:g`, the last search
  before anything is typed), smartcase and `*` (kept after `:w` and a cancelled search, not set by
  `f*`, not given to a search in the same batch of keys), Vim's capitals, offsets, byte columns,
  matches across lines (one beginning above the view too), only the visible lines and the
  editor's reported ones (two ranges apart searched apart, a match between them sent once), only
  changes sent, sent again after an edit, resend.
- `NotificationBridgeTest.searchMatchesComeInGroupsOfFour`: the payload.
- `SearchHighlighterTest` (`tests/swt`): on a real source viewer, the backgrounds in the widget's
  style ranges, the box around the current match (painted into an image, one per wrapped row),
  matches following Eclipse's edits, the view reports, clear and dispose.
- `SearchRegionsTest` (`tests/text`, no display): byte columns to document offsets, a match across
  lines, past the document or empty, the current match (while a search is typed too), and each
  case of the matches moving with an edit (`editor/SearchRegions`).
- `OverlayColorsTest.theSearchBoxMovesTowardTheText`.
- `SearchHighlightingEndToEndTest`: a search, `n`, `:noh`, Eclipse mode and back, another editor
  and back, Detach and Attach, typing in insert mode with a scroll to another match before `Esc`
  (Neovim's older columns not painted), in a real editor.
- The manual check `sh-colors`: the gallery's pictures (`SearchGalleryTest`, `-Pgallery`) in a
  light and a dark theme, plain text and the Java editor.
