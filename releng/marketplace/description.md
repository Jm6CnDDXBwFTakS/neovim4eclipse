Vim mode for Eclipse, powered by a real Neovim. **Not an emulation:** Neovim itself does the
editing, so your `init.lua`, mappings and the Neovim plug-ins that work on text (surround,
commentary, text objects) apply; plug-ins that draw (completion menus, pickers, file trees,
dashboards) do not show. Motions, operators, text objects,
visual mode, registers, macros and `:` commands work on the buffer you see; Eclipse keeps owning
files, rendering, content assist and its own commands.

**Requires:**

- Neovim {neovim}. After installing, a *Get Neovim* dialog opens when Eclipse starts: it can
  download the official release into the plug-in's own folder and check it. A Neovim of your own,
  {neovim} or newer, can be named instead with `-Dneovim4eclipse.nvim`.
- Eclipse {eclipse.oldest} ({eclipse.oldest.number}) or newer, on Java {java} or newer.

**Signed releases:** Eclipse asks whether to trust the key that signed the plug-in, *Neovim for
Eclipse releases*. Its fingerprint is `{fingerprint}`.

Before installing, read the [known limitations](https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse#known-limitations).
Questions and bug reports: [GitHub issues](https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse/issues).
