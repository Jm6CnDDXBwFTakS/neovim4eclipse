# Security policy

## Supported versions

Only the latest release gets fixes. Neovim for Eclipse has a single maintainer, so there are no
backports to older versions: a fix comes as a new release.

## Reporting a vulnerability

Please report security problems **privately**, not in a public issue, so that a fix can be released
before the problem is widely known.

Use GitHub's private vulnerability reporting: on the repository's **Security** tab, choose
**Report a vulnerability**
([direct link](https://github.com/Jm6CnDDXBwFTakS/neovim4eclipse/security/advisories/new)). Only the
maintainer sees the report.

Please include:

- the Neovim for Eclipse, Eclipse and Neovim versions, and the operating system;
- what an attacker can do, and the steps or a file that shows it.

There is no guaranteed response time. You can expect an acknowledgement when the maintainer has
read the report, and a note when a fix is released. You are credited in the release notes unless
you ask not to be.

## Release signing

Since 0.1.4, every release is signed with the project's PGP key, *Neovim for Eclipse releases*,
fingerprint:

```text
A91F 53D8 931B 7FD0 D146  8618 6CC9 E446 147B 063C
```

Eclipse shows this key when it installs the plug-in; trust it only if the fingerprint matches. The
public key is [`releng/signing-key.asc`](releng/signing-key.asc) and on
[keys.openpgp.org](https://keys.openpgp.org/search?q=A91F53D8931B7FD0D14686186CC9E446147B063C). If the key is ever
compromised, it is revoked there, and a new key is announced in a release and on this page.

## What a file you open can do

The aim: a file of a repository you only cloned, opened and edited with the usual keys, does not
make a program run or a network request go out. Neovim has ways for a file's text or name to do
that, and the plug-in turns them off when it starts the embedded Neovim:

| Off | Why |
|---|---|
| Modelines (`'modeline'`) | A line such as `// vim: set ... :` sets options; some change what a later key does. |
| Filetype plug-ins for the files you edit | Filetype detection is not used for them, so no filetype plug-in or language server starts for a file. |
| netrw, the `http://` reader, the zip and tar browsers | `gf` on a URL written in a file fetched it; `gf` on an archive unpacked it. |
| The `term://` reader | `gf` on `term://./script` ran the script. |
| `Ctrl-X Ctrl-V` in insert mode, and Neovim's `Ctrl-X Ctrl-O` | Completing a line as a Vim command runs text in backticks in the shell. |

The [user guide](docs/USER_GUIDE.md) says how to turn a reader back on, and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) where each switch is.

What stays as in Vim, because it takes a key you press on purpose or your own setup:

- `Ctrl-R` with a register (`%`, `#`, `"`, `/`, `:`, `-`, `=`) inserts its text as if typed, so
  a control character in it acts as a key. A file whose name holds a real Esc can run a command
  that way when you press `Ctrl-R %` in it. `Ctrl-R Ctrl-R` inserts the same text as text.
- Commands that run a program by design: `:!`, `K` (the manual page of the word under the
  cursor), `gx` (the system's handler for the link under the cursor), `:make`. `:terminal` opens
  Eclipse's Terminal with a shell instead, and runs no command typed after it; a terminal a
  plug-in or a mapping starts runs its program in Neovim, with a message naming it.
- `:vimgrep` reads the files it searches itself, with filetype detection.
- Three bundled Neovim plug-ins are still loaded: `gzip` (a compressed file Neovim reads itself is
  unpacked with the system's `gzip`), `editorconfig` (a repository's `.editorconfig` may set
  indent, line-ending and text-width options) and the `man://` reader of manual pages.
- Your own `init.lua` and the plug-ins it loads run with your rights, as in any Neovim.
- The help editor (`:help`) runs the help scripts of your plug-ins (`ftplugin/help.lua`,
  `after/ftplugin/help.lua`, a parser for a code block's language) on each page, as your Neovim
  does: it has your `'runtimepath'`. A help page is a plug-in's, not a cloned repository's file.
  The `:messages` and `:checkhealth` editors run in other Neovims, without your plug-ins.

A way around the first list is a vulnerability: please report it as described above.

## What counts

The plug-in starts a local `nvim` process and talks to it over that process's standard input and
output; it opens no network connection of its own. Examples of what to report:

- opening or editing a file makes the plug-in run commands or code the user did not ask for;
- the plug-in starts a different program than the configured Neovim executable;
- a dependency embedded in the plug-in (msgpack-core, in `plugins/.../lib/`) has a known
  vulnerability that affects the plug-in.

Not in scope: what Neovim itself does with a file, the user's own Neovim configuration and plug-ins
(these run with the user's rights by design), and problems in Eclipse itself. Report those to the
project concerned.
