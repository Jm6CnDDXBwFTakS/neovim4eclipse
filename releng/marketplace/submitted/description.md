Vim mode for Eclipse, powered by a real Neovim. **Not an emulation:** Neovim itself does the
editing, so your `init.lua`, mappings and the Neovim plug-ins that work on text (surround,
commentary) apply. Motions, operators, text objects, visual mode, registers, macros and `:`
commands work on the buffer you see; Eclipse keeps owning files, rendering, content assist and its
own commands.

**See it work:** a demo is available at the top of the [README](https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#readme).

**Works with Eclipse:**

- Vim's splits are Eclipse editors: `:split`, `:vsplit` and `Ctrl-W v` put the file in an editor
  beside this one, `Ctrl-W h/j/k/l` move between them, `:only` gathers them again.
- Eclipse's problems in Neovim: `]d` / `[d` go between a file's errors and warnings, `:Eproblems`
  puts the workspace's in the quickfix list for `]q` / `:cnext`, and `:copen` shows the Problems view.
- Neovim's keys run Eclipse's commands: `gd` Open Declaration, `K` hover, `grn` Rename,
  `gra` Quick Fix, `grr` References.
- Folding with `zo`, `zc` and `zM` on Eclipse's own folds (methods, imports, comments).
- `:terminal` opens Eclipse's Terminal view, with a shell in the file's folder.

**Neovim plug-ins** are best effort, with extra care for those that add what Eclipse lacks:

- Plug-ins that work on text (surround, text objects, motions, your own mappings) work as in a
  terminal.
- Floating windows are drawn over the editor: key hints (which-key), hover text, notifications,
  and the labels of jump plug-ins (flash, leap, hop).
- Plug-ins that bring an IDE of their own (pickers, file managers, git interfaces, completion
  menus) may not work. Some are tested behind the scenes, but Eclipse already does their job, and
  replacing it is not a goal.

**Requires:**

- Neovim 0.12.6. After installing, a *Get Neovim* dialog opens when Eclipse starts. It
  downloads the official release into the plug-in's own folder, or installs an archive you
  downloaded yourself (for a machine that is offline or behind a proxy), and checks it either way.
  A Neovim of your own, 0.12.6 or newer, can be named instead with `-Dneovim4eclipse.nvim`.
- Eclipse 2024-12 (4.34) or newer, on Java 21 or newer.

**Signed releases:** Eclipse asks whether to trust the key that signed the plug-in, *Neovim for
Eclipse releases*. Its fingerprint is `A91F 53D8 931B 7FD0 D146  8618 6CC9 E446 147B 063C`.

Before installing, read the [known limitations](https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#known-limitations).
Questions and bug reports: [GitHub issues](https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse/issues).
