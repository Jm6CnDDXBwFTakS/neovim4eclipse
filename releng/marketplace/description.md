Vim mode for Eclipse, powered by a real Neovim. **Not an emulation:** Neovim itself does the
editing, so your `init.lua`, mappings and the Neovim plug-ins that work on text (surround,
commentary) apply; plug-ins that draw (completion menus, pickers, file trees, dashboards) do not
show. Motions, operators, text objects,
visual mode, registers, macros and `:` commands work on the buffer you see; Eclipse keeps owning
files, rendering, content assist and its own commands.

**See it work:** a demo is available at the top of the [README](https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#readme).

**Works with Eclipse:**

- Vim's splits are Eclipse editors: `:split`, `:vsplit` and `Ctrl-W v` put the file in an editor
  beside this one, `Ctrl-W h/j/k/l` move between them, `:only` gathers them again.
- Eclipse's problems in Neovim: `]d` / `[d` go between a file's errors and warnings, `:Eproblems`
  puts the workspace's in the quickfix list for `]q` / `:cnext`, and `:copen` shows the Problems view.
- Neovim's keys run Eclipse's commands: `gd` Open Declaration, `K` hover, `grn` Rename,
  `gra` Quick Fix, `grr` References.
- Folding with `zo`, `zc` and `zM` on Eclipse's own folds (methods, imports, comments).

**Requires:**

- Neovim {neovim}. After installing, a *Get Neovim* dialog opens when Eclipse starts: it can
  download the official release into the plug-in's own folder and check it. A Neovim of your own,
  {neovim} or newer, can be named instead with `-Dneovim4eclipse.nvim`.
- Eclipse {eclipse.oldest} ({eclipse.oldest.number}) or newer, on Java {java} or newer.

**Signed releases:** Eclipse asks whether to trust the key that signed the plug-in, *Neovim for
Eclipse releases*. Its fingerprint is `{fingerprint}`.

Before installing, read the [known limitations](https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#known-limitations).
Questions and bug reports: [GitHub issues](https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse/issues).
