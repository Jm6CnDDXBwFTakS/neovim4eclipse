# Acknowledgements

## vscode-neovim

<https://github.com/vscode-neovim/vscode-neovim>, licensed under the MIT License.

vscode-neovim embeds a real Neovim process in Visual Studio Code. Eclipse Neovim follows the same
architecture instead of re-implementing Vim inside the IDE:

- Neovim owns normal and visual mode; the IDE owns insert mode, files and rendering.
- Buffer changes are synchronized through `nvim_buf_attach` line events.
- Commands that act on files (`:w`, `:wa`, `:q`) are handled by the IDE instead of Neovim.

No source code was copied from vscode-neovim. Ideas and designs are not covered by copyright, so its
license does not apply to this project, and no MIT notice needs to be kept here.

## Libraries

These libraries are embedded in the plug-in's `lib/` folder. Their license texts ship with the
plug-in in `plugins/io.github.jm6cnddxbwftaks.eclipseneovim/licenses/` and are linked from its
`about.html`.

| Library | License |
|---------|---------|
| [neovim-java](https://codeberg.org/neovim-java/neovim-java) | MIT |
| [msgpack-java](https://github.com/msgpack/msgpack-java) | Apache-2.0 |
| [Jackson](https://github.com/FasterXML/jackson) | Apache-2.0 |
| [SLF4J](https://www.slf4j.org/) | MIT |

[Neovim](https://neovim.io/) itself (Apache-2.0 and Vim license) is not embedded; the plug-in
starts the `nvim` you have installed.
