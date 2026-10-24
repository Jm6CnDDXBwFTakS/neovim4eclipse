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
