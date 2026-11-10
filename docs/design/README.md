# Design notes

A design note explains one larger part of the plug-in: a feature that took several commits, a
subsystem, or how the project is built or released. It says how that part works and **why** it is
built that way: the problem, how Eclipse and Neovim really behave (the experiments, with the
versions they ran on), the alternatives that were rejected and the reasons. The code can say what
it does; only a note like this can say why it does not do something simpler. Notes about the
plug-in as a whole live one level up, in `docs/` (for example [ARCHITECTURE.md](../ARCHITECTURE.md)
and [KEYBOARD.md](../KEYBOARD.md)).

| Note | What it covers |
|------|----------------|
| [MARKETPLACE.md](MARKETPLACE.md) | The permanent p2 update site on GitHub Pages, PGP-signed releases, and the Eclipse Marketplace listing |
| [KEY_ROUTING.md](KEY_ROUTING.md) | When `:`, `/`, `?` open the box and when `q` records: Neovim says (`ext_cmdline`), the plug-in does not guess |
| [DIAGNOSTICS.md](DIAGNOSTICS.md) | Eclipse's problems as Neovim diagnostics (`]d`, `[d`), quickfix lists of the workspace's problems and tasks, the `gr*` keys |
| [FOLDING.md](FOLDING.md) | Folding with `zo`, `zc` and the other `z` commands, mirroring Eclipse's folds into Neovim |
| [VIEWPORT.md](VIEWPORT.md) | Neovim's window follows the editor's size and scroll position, so `H`, `L`, `Ctrl-D`, `zz` act on what is on screen |
| [WINDOWS.md](WINDOWS.md) | One Neovim window per Eclipse editor, each alone in a hidden tabpage: per-editor cursors, views, folds and jump history; `Ctrl-O` takes the window along |
| [SPLITS.md](SPLITS.md) | Vim's splits as Eclipse editor stacks: `:split` and `Ctrl-W s` put an editor beside this one, `Ctrl-W h/j/k/l`, the sizes and `:only` work on the editor area, Toggle Split Editor makes the same split |
| [BUFFER_NUMBERS.md](BUFFER_NUMBERS.md) | A closed file keeps its buffer number, marks and last position, as with Vim's `:bd`; Neovim moving into a file without an editor opens it; Neovim reads no project file itself |
| [STATUS_BAR.md](STATUS_BAR.md) | The status bar's three labels: the mode's names (lualine's), widths in characters, the cap, Neovim fitting the statusline |
| [DOWNLOAD_NEOVIM.md](DOWNLOAD_NEOVIM.md) | *Get Neovim*: download the pinned Neovim release or install it from a file, check its SHA-256, unpack it into `neovim/<platform code>/`; Eclipse's proxy setting; the plug-in folder layout |
| [INSERT_CTRL_KEYS.md](INSERT_CTRL_KEYS.md) | Vim's `Ctrl` keys in insert mode: a Normal and an Insert choice per letter, a sync point that hands Eclipse's typed text to Neovim before the key, the user's insert mappings kept |
| [SEARCH_HIGHLIGHTING.md](SEARCH_HIGHLIGHTING.md) | The matches of Neovim's search painted behind the editor's text, the one under the caret stronger: visible lines only, sent when they change, JFace's presentation listener |
| [NEW_FILE.md](NEW_FILE.md) | `:e newfile` opens an empty editor that `:w` creates |
| [LOG_LEVEL.md](LOG_LEVEL.md) | One switch for the plug-in's log level, and why it is the only one |
| [RPC_CLIENT.md](RPC_CLIENT.md) | The plug-in's own msgpack-rpc client, and why it replaced neovim-java |

## What goes in a note, and what does not

A note describes the code as it is, in the present tense, for anyone who works on it later:

- **In:** the mechanism, the constraints, the decisions and their reasons ("X does Y, because Z"),
  rejected alternatives with the reason, experiment results with the Neovim, Eclipse and OS
  versions they hold for, the tests that cover it, and limits of the current design ("Not done",
  with why).
- **Out:** the history of how it was planned and discussed. No status lines, branch names or
  commit IDs, no dates of decisions, no "decided with ..." marks or numbered questions, no plan
  phases or size estimates, no "review fix" labels, and no pointers to anything outside the
  repository that a reader cannot open (private checklists, notes or chats). The changelog and
  git history say when something changed.

**Planning happens outside the repository.** While a change is being planned and built, its plan
(options, questions, steps, status) is kept privately. Once it is built, the parts that explain
the result are written up here as a note, or added to an existing one, in the same branch as the
code. A new note is named after its subject in capitals (`FOLDING.md`) and added to the table above.
