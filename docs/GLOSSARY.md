# Glossary: Eclipse words, Neovim words

Eclipse and Neovim use some of the same words for different things: an Eclipse *editor tab* is not
a Neovim *tab page*, and a Vim *split* is not an Eclipse split. This page says which side each word
belongs to, then how the plug-in maps one side onto the other. [LEARNING.md](LEARNING.md) explains
the machinery behind each term; [ARCHITECTURE.md](ARCHITECTURE.md) the design decisions.

Example of the mix-up it prevents: "the help editor is in a hidden tab page" mixes both sides. On
the Eclipse side the help editor is an editor tab in an editor stack. On the Neovim side it is a
window of the editing Neovim, alone in a hidden tab page, as each text editor's window is: one
window per text editor and one per page editor.

## Eclipse side

| Term | Meaning |
|---|---|
| Workbench window | The whole Eclipse window, with its editor area, views and toolbars. |
| Editor | A part in the editor area showing one input (a file, a page). |
| Text editor | An editor with a document, drawn by a `StyledText` widget (`ITextEditor`): the editors the plug-in attaches Neovim to. |
| Page editor | The plug-in's editor that is no text editor: *Neovim: help*, *Neovim: messages*, *Neovim: checkhealth* (`editor/NeovimPageEditor`). It shows a Neovim screen and has no document. |
| Editor tab | The tab with an editor's title. Vim's tab commands (`gt`, `:tabnext`) move between these. |
| Editor stack | A group of editor tabs, one of them in front. A Vim split (`:split`, `Ctrl-W s`) opens a new stack. |
| View | A part outside the editor area: Console, Problems, Terminal, Outline. |
| Document | Eclipse's text of a file (`IDocument`). Eclipse saves it: a `:w` in Neovim asks Eclipse to (`BufWriteCmd`, `eclipse_write`). |
| Active part, focus | The editor or view that gets the keyboard. |

## Neovim side

| Term | Meaning |
|---|---|
| Buffer | Neovim's copy of a text: a file, a help page, a scratch text (`:help buffers`). |
| Window | A viewport onto a buffer, with its own cursor, scroll position and jump list (`:help windows`). |
| Current window | The one window that Neovim's keys and commands act on. |
| Tab page | A set of windows, of which Neovim shows one set at a time (`:help tab-page`). Not an Eclipse editor tab. |
| Split | A window made by dividing another within one tab page. |
| Floating window, float | A window drawn over the others, outside the split layout: which-key's panel, a hover, ui2's command line (`:help api-floatwin`). |
| Popup menu | Neovim's list of candidates: the `:` line's Tab completion and insert-mode `Ctrl-N` (`:help popupmenu-completion`). Drawn by Neovim into a grid of its own, a float with no window; the plug-in draws it as a float. |
| ui2 | Neovim's newer, experimental way of drawing its command line, prompts, pager and messages (`vim._core.ui2`, Neovim 0.12; `:help ui2`): a Lua UI inside Neovim that draws them into four floating windows, `cmd`, `msg`, `pager` and `dialog`. The plug-in turns it on and copies those windows' cells over the editor's last row (ARCHITECTURE.md, "Messages and output"). |
| Pager | ui2's window for long output and `g<`: the cursor goes into it, `q` closes it. Not the page editors. |
| Grid | The character cells Neovim sends a UI for drawing. With `ext_multigrid` each window has a grid of its own; grid 1 is the whole screen (`:help ui-multigrid`). |
| Command-line window | The window `q:` opens on the command history (`:help cmdwin`). |
| UI | A program attached to Neovim that receives its redraw events (`nvim_ui_attach`); the plug-in is one. |

## How the plug-in connects them

| Eclipse | Neovim | Why, or what to know |
|---|---|---|
| A text editor | One window, alone in a hidden tab page | Each editor keeps its own cursor, scroll position and jumps, and two editors on one file have a cursor each. The tab pages are bookkeeping and never shown (`'showtabline'` 0). |
| The document | The buffer | Kept in step both ways (`sync/BufferSynchronizer`). |
| The text editor with the focus | The current window | The *attached* editor and its window (`DocumentBuffers.isAttached`), and the focused window too. A page's window can take the focus while the text editor stays attached (`DocumentBuffers.focusPage`). |
| A new editor stack | — | What a Vim split (`:split`, `:vsplit`, `Ctrl-W s`) opens: Eclipse splits, not Neovim splits (`editor/EditorSplits`). |
| Editor tabs | — | What Vim's tab commands move between (`editor/EditorTabs`); Neovim's tab pages are not used for them. |
| The text editor's `StyledText` | — | Eclipse draws the text and the caret; Neovim's grid for that window is not drawn. |
| The q: panel, floats over the editor | Their grids | Drawn by the plug-in over the text editor (`editor/NeovimWindowPanel`, `editor/FloatingWindows`). |
| The `:` line over the editor's last row | ui2's `cmd` window, in the screen's row below the editor's window (`'cmdheight'` 1) | Drawn as a float, always; the editor's `StyledText` keeps the keyboard and every key goes to Neovim. |
| A page editor (help, messages, checkhealth) | A window of the editing Neovim, alone in a hidden tab page | Drawn from that window's grid (`editor/WindowPane`, decision 10 in ARCHITECTURE.md); a split's two halves are two windows on one buffer. |

## The plug-in's own words

| Term | Meaning |
|---|---|
| Editing Neovim | The one Neovim behind every text editor of a workbench. |
| Attached | The text editor (and its window) whose document Neovim mirrors now: its buffer is kept in step and its caret follows Neovim's cursor. |
| Focused window | Neovim's current window, and the part shown from it. The mode, floats (ui2's command line and messages among them) and host commands follow it (`core/FocusRouter`). Usually the attached editor's; a page's window can have it while the text editor stays attached. |
| Home window | The window Neovim starts with. Documents are shown in it only by tests that pass no editor window. |
| Page kind | help, messages or checkhealth (`util/PageKind`). Help and checkhealth have one page editor each; messages one buffer, and an editor per window on it. |
| Dedicated Neovim | The Neovim the plug-in installs with Get Neovim and checks at every start. |
