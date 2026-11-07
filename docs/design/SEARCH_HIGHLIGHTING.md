# Search highlighting

Neovim highlights the matches of the last search (`'hlsearch'`), and every match of a search while
it is typed (`'incsearch'`). The editor shows the same: each match gets a background color behind
its text, and the match under the caret a shade stronger, as Neovim's `CurSearch` group does.
`:noh` takes them away until the next search.

## What is shown

- **After a search** (`/`, `?`, `*`, `#`, `n`, `:s`): every match of register `/` in the lines the
  editor shows, while `v:hlsearch` is 1. That is, while `'hlsearch'` is on and no `:nohlsearch`
  came after the search.
- **While a search is typed**, with `'incsearch'` on: the matches of what is typed so far, without a
  search offset (`/foo/e` shows `foo`).
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
- **Only the visible lines.** `matchbufline(buf, pattern, line('w0'), line('w$'))` takes 0.1 to
  0.2 ms over 60 lines, whatever the file's size. The whole buffer takes 2.5 ms at 2,000 lines,
  8 ms at 10,000 and 114 ms at 100,000 (Neovim 0.12.6, headless and with a UI attached). The
  viewport feature keeps Neovim's window the editor's size, so its visible lines are the
  editor's.
- **Across lines.** `matchbufline()` matches within one line. A pattern that can span lines (`\n`,
  `\_x`) is found with a `searchpos()` walk inside `nvim_win_call`: the start, then the end
  (`'e'` flag), then the view is put back as it was.
- **Only changes are sent.** Each window's last list is kept, as text. `n`, which moves the cursor
  between the same matches, sends nothing: the editor decides the current match from the caret.
  Entering a window or a buffer forgets the window's list, so an editor just attached gets its
  matches at the next redraw. `M.resend()` does the same when an editor is back in Neovim mode
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
3. `'smartcase'` and an upper-case letter that is not escaped (`\S` is a class, not a capital)
   means exact case, except for a search made with `*`, `#`, `g*` or `g#`, which Vim never makes
   smart;
4. otherwise, any case.

The `*` case is seen with `vim.on_key` in normal mode, and lasts until the next `/`, `?` or `:`
command line, so `n` after `*` keeps it. A spike compared this with Neovim's own `searchcount()`
in seven cases (`/Foo`, `/foo`, `*`, `n` after `*`, `:s`, `#`, `noignorecase`); all agreed.
Known gap: `f*` or `r*` in normal mode also set the flag, as `on_key` sees the `*` before Neovim
knows it is the argument of `f`; the next typed search clears it.

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

The match under the caret is the one whose range holds the caret's document offset (from its
start, up to its end). After `n` Neovim's cursor is on a match's first character, so that match is
the current one. A caret move repaints only the old and the new current match.

### Colors

The *Search Results* annotation color (*Window → Preferences → General → Editors → Text Editors →
Annotations*), the color Eclipse's Search view marks its hits with. It follows the theme and the
user's choice, and a change applies at once (`editor/SearchColors` listens to the preference
store). The key is read from the Search plug-in's annotation declaration
(`org.eclipse.search.results`), not written here. Without that plug-in, its default (206, 204,
247) is used. The current match is that color moved a quarter toward the editor's text color
(`util/OverlayColors.currentSearch`), so it stands out in a light theme and a dark one alike.
Neovim's `Search` and `CurSearch` groups are not used: their colors suit Neovim's screen, not the
editor's theme.

## Not done

- **No marks in the overview ruler.** Eclipse's own Find overlay (2024-12 and 2026-09) marks none
  either; ruler marks come only from Mark Occurrences and the Search view. They would need every
  match in the file, which costs 8 ms at 10,000 lines and 114 ms at 100,000 at each change.
- **Other highlights:** the yank flash, `:match`, other plug-ins' extmarks.
- **`'inccommand'`'s live preview of `:s`** is not shown, as before.

## Tests

- `tests/unit/src/test/lua/search_spec.lua`: which pattern, smartcase and `*`, offsets, byte
  columns, matches across lines, only the visible lines, only changes sent, resend.
- `NotificationBridgeTest.searchMatchesComeInGroupsOfFour`: the payload.
- `SearchHighlighterTest` (`tests/swt`): on a real source viewer, the backgrounds in the widget's
  style ranges, the current match, clear and dispose, a match the document no longer has.
- `OverlayColorsTest.theCurrentSearchMatchMovesTowardTheText`.
- `SearchHighlightingEndToEndTest`: a search, `n`, `:noh`, Eclipse mode and back, in a real editor.
- The manual check `sh-colors`: the colors in a light and a dark theme, and in the Java editor.
