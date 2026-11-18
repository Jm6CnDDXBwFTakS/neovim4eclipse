# Glossary: Eclipse words, Neovim words

Eclipse and Neovim use some of the same words for different things: an Eclipse *editor tab* is not
a Neovim *tab page*, and a Vim *split* is not an Eclipse split. This page says which side each word
belongs to, then how the plug-in maps one side onto the other. [LEARNING.md](LEARNING.md) explains
the machinery behind each term; [ARCHITECTURE.md](ARCHITECTURE.md) the design decisions.

Example of the mix-up it prevents: "the page editor is in a hidden tab page" mixes both sides. On
the Eclipse side the page editor is an editor tab in an editor stack. On the Neovim side its
content is a whole second Neovim, and only the editors' windows sit in hidden tab pages.

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
| Floating window, float | A window drawn over the others, outside the split layout: which-key's panel, the completion list, a hover (`:help api-floatwin`). |
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
| A page editor | A whole second Neovim | One per page kind, its own process; its grid 1 is what the page editor shows (`core/ScreenSession`, decision 10 in ARCHITECTURE.md). |

## The plug-in's own words

| Term | Meaning |
|---|---|
| Editing Neovim | The one Neovim behind every text editor of a workbench. |
| Second Neovim | A page editor's own Neovim, started at its first content; it never writes a file. |
| Attached | The text editor (and its window) whose document Neovim mirrors now: its buffer is kept in step and its caret follows Neovim's cursor. |
| Focused window | Neovim's current window, and the part shown from it. The mode, the `:` box, messages, floats and host commands follow it (`core/FocusRouter`). Usually the attached editor's; a page's window can have it while the text editor stays attached. |
| Home window | The window Neovim starts with. Documents are shown in it only by tests that pass no editor window. |
| Page kind | help, messages or checkhealth (`util/PageKind`); each kind has one page editor. |
| Dedicated Neovim | The Neovim the plug-in installs with Get Neovim and checks at every start. |
