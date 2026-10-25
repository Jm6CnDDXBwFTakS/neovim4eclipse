# Cross-platform notes (Windows, macOS, Linux)

## Supported environments

| OS | Window system | Arch |
|----|---------------|--------------|
| Linux | GTK | x86_64, aarch64 |
| macOS | Cocoa | x86_64, aarch64 |
| Windows | Win32 | x86_64, aarch64 |

Maven/Tycho builds the OSGi plug-in, feature, and p2 update site. Unit tests (`tests/unit`) run as plain JUnit without Eclipse.

## Neovim executable

- Preference **Neovim folder or program** (`util/NvimExecutable`) accepts:
  - **blank** (the default): `~/.local/opt/neovim` (Windows:
    `%USERPROFILE%\.local\opt\neovim`), the folder meant for a Neovim of the plug-in's own,
    searched as a folder (below); then `nvim` on `PATH`, then the usual install folders (below);
  - **a folder**: `folder/bin/nvim` if it works, else `folder/nvim` (a `bin` folder) if it works, else
    every install below the folder down to depth 4 (`NvimExecutable.maxSearchDepth()`;
    `folder/a/b/bin/nvim` is depth 4). An install is a `bin/nvim` (`bin\nvim.exe` on Windows) with
    `share/nvim/runtime` beside its `bin`, as every release archive has, also when Windows Explorer's
    *Extract All* put it in one more folder (`nvim-win64\nvim-win64\bin\nvim.exe`). Links and Windows
    junctions are not followed. Every candidate is asked `--version`: one that does not answer as
    Neovim (another processor, a broken install) is skipped, and of several the newest wins;
  - **a program**: an absolute path to `nvim`, or a command name looked up on `PATH`, then in the usual
    install folders. A leading `~` is the home folder.
- The preference page shows which Neovim the field finds and its version, or that it is too old.
- Process is started with `ProcessBuilder` and the inherited environment so `PATH` / `PATHEXT` apply on Windows.
- On Windows, `SHELL` is not passed on (`NvimExecutable.prepareEnvironment`): Eclipse started from Git Bash
  inherits `SHELL=/usr/bin/bash`, which Neovim would combine with `cmd.exe`'s flags, so `:!` commands failed.
  Neovim then uses `cmd.exe`; set `'shell'` and its flags in `init.lua` for another shell.
- Simplest install: extract the release archive from https://github.com/neovim/neovim/releases into
  `~/.local/opt/neovim` and leave the preference blank.
- The usual install folders, each checked for `bin/nvim` or `nvim` directly inside it (not searched
  further down, since folders such as `/usr/local` can be large) and asked `--version`, on macOS and
  Linux alike: `/opt/homebrew`, `/usr/local`, `/home/linuxbrew/.linuxbrew`, `~/.linuxbrew`,
  `/opt/local`, `~/.local`, `~/bin`, `/opt/nvim-linux-x86_64`, `/opt/nvim-linux-arm64`, `/opt/nvim`
  (Neovim's own Linux instructions). Windows:
  `%ProgramFiles%\Neovim`, `%LOCALAPPDATA%\Programs\Neovim`, Scoop's shims and `apps\neovim\current`,
  Chocolatey's `bin`. This matters when Eclipse is started from the macOS Dock, a desktop
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
3. **CI on all three** — the *Build* workflow runs every test (unit, text, SWT, UI and the
   integration tests with a real Neovim) on Linux, Windows and macOS: the Linux jobs under Xvfb, the
   `platforms` job on `windows-latest` (x86_64) and `macos-latest` (Apple silicon), which have a
   desktop. Unit tests in `tests/unit` need no GUI; the others need a display. Not covered: Linux on
   aarch64, Windows on aarch64 and macOS on x86_64 (no matching runner is used), and Wayland.
4. **RPC EOF on shutdown** — closing sends `:qa!` as a notification and waits for the process to exit (milliseconds); `RpcChannel.closing()` marks the end of the stream as expected, so it is logged at FINE only. A WARNING "Neovim exited unexpectedly" means Neovim really went away during a session (it crashed or was killed); anything Neovim writes to stderr is logged as a WARNING "Neovim stderr: ...". On Windows, `nvim --embed` passes nothing on to that stderr pipe (Neovim 0.12, tried with its own writes and with a child process's), so nothing is logged there; diagnose with Neovim's own log (`:help $NVIM_LOG_FILE`) instead.

## Quick verification

```bash
./mvnw test -f tests/unit/pom.xml                 # unit tests, no Eclipse or Neovim needed
./mvnw test -f tests/unit/pom.xml -Pintegration   # real nvim required; skipped automatically if missing
```

`PlatformUtilTest`, `NvimExecutableTest` and `KeyMappingTest` cover the OS-dependent logic by passing
OS names/PATH values in, so the Windows and macOS branches are tested on any machine.
