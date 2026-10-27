# Design notes

A design note is written **before** a larger change: a feature that takes several commits, a new
subsystem, or a change to how the project is built or released. It records the problem, the
experiments that showed how Eclipse and Neovim really behave, the options and the decisions made
with the maintainer (marked *(decided)*). Once the change is built, the note stays: its *Status* line
says so, and an *As built* section says where the code differs from the plan and why. That makes each
note the place to learn *why* the code is the way it is, which the code itself cannot say.

| Note | What it covers | Status |
|------|----------------|--------|
| [MARKETPLACE.md](MARKETPLACE.md) | A permanent p2 update site on GitHub Pages, and the Eclipse Marketplace listing | phase 1 built; signing (phase 4) built, first signed release 0.1.4 |
| [KEY_ROUTING.md](KEY_ROUTING.md) | When `:`, `/`, `?` open the box and when `q` records: fixing the router's guesses, and letting Neovim say (`ext_cmdline`) | phases 1 and 2 built; phase 3 built as step A2 of the Neovim 0.12 cleanup (0.1.7) |
| [DIAGNOSTICS.md](DIAGNOSTICS.md) | Eclipse's problems as Neovim diagnostics (`]d`, `[d`), then a quickfix list of the workspace's problems and tasks | phase 1 built (0.1.9); `gr*` keys and quickfix planned |
| [FOLDING.md](FOLDING.md) | Folding with `zo`, `zc` and the other `z` commands, mirroring Eclipse's folds into Neovim | Neovim side built; Eclipse side planned |
| [VIEWPORT.md](VIEWPORT.md) | Neovim's window follows the editor's size and scroll position, so `H`, `L`, `Ctrl-D`, `zz` act on what is on screen | planned, after FOLDING.md |
| [NEW_FILE.md](NEW_FILE.md) | `:e newfile` opens an empty editor that `:w` creates | implemented |
| [TAB_COMPLETION.md](TAB_COMPLETION.md) | Tab completion in the `:` command line | superseded in 0.1.7: Tab is Neovim's own, in Neovim's command line |
| [LOG_LEVEL.md](LOG_LEVEL.md) | One switch for the plug-in's log level, and why it is the only one | implemented |
| [RPC_CLIENT.md](RPC_CLIENT.md) | Our own msgpack-rpc client in place of neovim-java, step by step | done |

**Writing a new one:** name it after the change in capitals (`FOLDING.md`), start with a
`Status:` line that names the branch and commit it was planned against, and add it to the table
above. Keep the status here and in the note current as the work goes on.

Notes about how the plug-in works today, rather than one change, live one level up in `docs/`
(for example [ARCHITECTURE.md](../ARCHITECTURE.md) and [KEYS.md](../KEYS.md)).
