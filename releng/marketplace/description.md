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

**Plug-ins that draw on the screen** are shown only in part, since Eclipse draws the editor:

- Shown: floating windows, such as which-key's key hints, and those that take the keyboard
  (pickers such as Telescope, fzf-lua and snacks.nvim, oil.nvim's float), Neovim's own
  popup menu (`Ctrl-N`, Tab on the `:` line) and the labels of jump plug-ins (flash, leap, hop,
  mini.jump2d).
- Opened in Eclipse: a plug-in's split or tab page on a file (a picker's `Ctrl+V` or `Ctrl+T`,
  vim-fugitive's commit message) becomes an Eclipse split or editor tab.
- Not shown: splits on anything but a file (file trees, vim-fugitive's status window and
  `:Gdiffsplit`), and a plug-in's buffer that is not a file, opened in the editor's own window
  (`:Oil` without `--float`: the editor stays on its file).
- Open only at a key: windows that follow typing in insert mode, such as the menus of completion
  plug-ins (nvim-cmp, blink.cmp), open at the plug-in's own `Ctrl-N` and follow the typing from
  then on.

**Requires:**

- Neovim {neovim}. After installing, a *Get Neovim* dialog opens when Eclipse starts. It
  downloads the official release into the plug-in's own folder, or installs an archive you
  downloaded yourself (for a machine that is offline or behind a proxy), and checks it either way.
  A Neovim of your own, {neovim} or newer, can be named instead with `-Dneovim4eclipse.nvim`.
- Eclipse {eclipse.oldest} ({eclipse.oldest.number}) or newer, on Java {java} or newer.

**Signed releases:** Eclipse asks whether to trust the key that signed the plug-in, *Neovim for
Eclipse releases*. Its fingerprint is `{fingerprint}`.

Before installing, read the [known limitations](https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#known-limitations).
Questions and bug reports: [GitHub issues](https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse/issues).
