# Cross-platform notes (Windows, macOS, Linux)

## Supported environments

| OS | Window system | Arch | Neovim binary |
|----|---------------|--------------|---------------|
| Linux | GTK | x86_64 | `nvim` on `PATH` |
| macOS | Cocoa | x86_64, aarch64 | `nvim` on `PATH` |
| Windows | Win32 | x86_64 | `nvim.exe` on `PATH` |

Maven/Tycho builds the OSGi plug-in, feature, and p2 update site. Unit tests (`tests/unit`) run as plain JUnit without Eclipse.

## Neovim executable

- Preference **Neovim executable** accepts a **command name** (`nvim`, `nvim.exe`) or an **absolute path**.
- Default: `nvim` on Unix/macOS, `nvim.exe` on Windows (`NvimExecutable.defaultCommandName()`).
- Process is started with `ProcessBuilder` and the inherited environment so `PATH` / `PATHEXT` apply on Windows.
- On Windows, `SHELL` is not passed on (`NvimExecutable.prepareEnvironment`): Eclipse started from Git Bash
  inherits `SHELL=/usr/bin/bash`, which Neovim would combine with `cmd.exe`'s flags, so `:!` commands failed.
  Neovim then uses `cmd.exe`; set `'shell'` and its flags in `init.lua` for another shell.
- Install Neovim from https://neovim.io/ and ensure the binary is on `PATH`, or set the absolute path in **Preferences → Neovim**.
- A bare name that is not on `PATH` is also looked for in the usual install folders, on macOS and Linux
  alike: `/opt/homebrew/bin`, `/usr/local/bin`, `/home/linuxbrew/.linuxbrew/bin`, `~/.linuxbrew/bin`,
  `/opt/local/bin`, `~/.local/bin`, `~/bin`, `/snap/bin` (Windows: `%ProgramFiles%\Neovim\bin`,
  Scoop's shims, Chocolatey's `bin`). This matters when Eclipse is started from the macOS Dock, a desktop
  menu or a PDE launch, which do not get the `PATH` your shell sets up.
- If Neovim cannot be started, an error dialog says so once (naming the executable) and offers to open
  the preference page; editors keep their normal Eclipse keys.

## Line endings

- Buffer sync uses the Eclipse document’s default delimiter when available.
- Fallback is `System.lineSeparator()` (`\n` on Linux/macOS, `\r\n` on Windows).
- `LineTextUtil` splits `\n`, `\r\n`, and `\r` when reading document text.

## Clipboard

- `ClipboardBridge` uses SWT `Clipboard` / `TextTransfer` (works on all three platforms), so Neovim
  needs no clipboard tool (`xclip`, `wl-clipboard`, `pbcopy`, `win32yank`).
- System clipboard access requires the display thread: copies are queued to it, pastes wait for it
  (at most 2 s).
- `"*` is the primary selection (`DND.SELECTION_CLIPBOARD`) on GTK only; on Windows and macOS it is
  the one clipboard, as in Vim.
- Copies join lines with the platform's line separator (`\r\n` on Windows); pastes accept any line
  break.

## Keys

- Key mapping uses SWT key codes/state masks (same logical keys on all platforms).
- Modifier chords: Ctrl (`M1` on Windows/Linux, Control on macOS), Alt/Option, Shift.

## Known platform caveats

1. **Windows path with spaces** — set the preference to a quoted-free absolute path (`C:\Program Files\Neovim\bin\nvim.exe`); `ProcessBuilder` does not use a shell.
2. **macOS Gatekeeper** — first launch of a Homebrew/nvim binary may need approval.
3. **Headless CI** — unit tests in `tests/unit` need no GUI; full plugin tests need a display (or Xvfb on Linux).
4. **RPC EOF on shutdown** — closing sends `:qa!` as a notification and waits for the process to exit (milliseconds); `core/QuittingConnection` keeps neovim-java from logging the expected end of the stream as an error. An ERROR from neovim-java's reader now means Neovim really went away during a session (it crashed or was killed).

## Quick verification

```bash
./mvnw test -f tests/unit/pom.xml                 # unit tests, no Eclipse or Neovim needed
./mvnw test -f tests/unit/pom.xml -Pintegration   # real nvim required; skipped automatically if missing
```

`PlatformUtilTest`, `NvimExecutableTest` and `KeyMappingTest` cover the OS-dependent logic by passing
OS names/PATH values in, so the Windows and macOS branches are tested on any machine.
