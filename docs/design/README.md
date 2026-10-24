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
| [KEY_ROUTING.md](KEY_ROUTING.md) | When `:`, `/`, `?` open the box and when `q` records: fixing the router's guesses, and letting Neovim say (`ext_cmdline`) | phase 1 built, phase 2 next |
| [FOLDING.md](FOLDING.md) | Folding with `zo`, `zc` and the other `z` commands, mirroring Eclipse's folds into Neovim | planned |
| [NEW_FILE.md](NEW_FILE.md) | `:e newfile` opens an empty editor that `:w` creates | implemented |
| [TAB_COMPLETION.md](TAB_COMPLETION.md) | Tab completion in the `:` command line | implemented |
| [LOG_LEVEL.md](LOG_LEVEL.md) | One switch for the plug-in's log level, and why it is the only one | implemented |
| [RPC_CLIENT.md](RPC_CLIENT.md) | Our own msgpack-rpc client in place of neovim-java, step by step | done |

**Writing a new one:** name it after the change in capitals (`FOLDING.md`), start with a
`Status:` line that names the branch and commit it was planned against, and add it to the table
above. Keep the status here and in the note current as the work goes on.

Notes about how the plug-in works today, rather than one change, live one level up in `docs/`
(for example [ARCHITECTURE.md](../ARCHITECTURE.md) and [KEYS.md](../KEYS.md)).
