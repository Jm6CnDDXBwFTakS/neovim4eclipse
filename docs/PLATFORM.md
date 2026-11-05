# Cross-platform notes (Windows, macOS, Linux)

## Supported environments

| OS | Window system | Arch |
|----|---------------|--------------|
| Linux | GTK | x86_64, aarch64 |
| macOS | Cocoa | x86_64, aarch64 |
| Windows | Win32 | x86_64, aarch64 |

Maven/Tycho builds the OSGi plug-in, feature, and p2 update site. Unit tests (`tests/unit`) run as plain JUnit without Eclipse.

## Neovim executable

- The startup option **`-Dneovim4eclipse.nvim`** (`util/NeovimOption`, read by `util/NvimExecutable`)
  accepts:
  - **unset or blank** (the default): the dedicated Neovim (`util/PluginNeovim`), strictly
    `<folder>/<code>/bin/nvim` with `share/nvim/runtime` beside its `bin`, and only if
    `PluginNeovim.verified`: the tree hash of all its files (`util/InstallTree`) equals the one pinned
    in `neovim-downloads.properties`. Nothing else is searched: not `PATH`, not the usual install
    folders. The code names the platform: `l`/`m`/`w` for Linux, macOS, Windows, then `x`/`a` for
    x86_64, arm64 (`util/NeovimPlatform`). The processor is the machine's real one
    (`util/ProcessorProbe`: `/usr/sbin/sysctl -n hw.optional.arm64` on macOS, the registry's
    `PROCESSOR_ARCHITECTURE` through `%SystemRoot%\System32\reg.exe` on Windows), so an Eclipse
    running emulated still gets the native Neovim; the other processor's folder is tried next
    (verified, then asked `--version`, which an install for another processor fails). Anything else
    in the folder is not used; the preference page's note lists it as no longer used. The folder is
    `<data home>/neovim4eclipse-plugin/neovim`, the data home being Neovim's (`util/PluginFolders`):
    `$XDG_DATA_HOME` if set to an absolute path; else on Linux and macOS `$HOME/.local/share` if
    `HOME` is set, else the home folder's `.local/share`; on Windows `%LOCALAPPDATA%`, else
    `%HOME%\AppData\Local`, else the home folder's `AppData\Local`. So by default
    `~/.local/share/neovim4eclipse-plugin/neovim`, on Windows
    `%LOCALAPPDATA%\neovim4eclipse-plugin\neovim`. It is not named `neovim4eclipse`, a
    likely `NVIM_APPNAME`, whose Neovim data would land in the same folder. Eclipse sees only the
    variables of whatever started it: set in a shell's startup file, they are missing when Eclipse
    starts from the macOS Dock or the Windows Start menu. The preference page shows the folder used;
  - **a program**: an absolute path to `nvim`, used as it is, unchecked and unsupported (for a system
    without an official build, or a newer Neovim's security fix). On the command line
    `eclipse -vmargs -Dneovim4eclipse.nvim=/path/to/nvim`, which adds to `eclipse.ini`'s `-vmargs`
    when that has `--launcher.appendVmargs` (the EPP packages do) and replaces them otherwise; or the
    line `-Dneovim4eclipse.nvim=/path/to/nvim` after `-vmargs` in `eclipse.ini`. A leading `~` is the home folder. A folder is refused (the program
    inside it must be named), and so is anything that is not an absolute path, such as a command name
    (`nvim`) or a relative path: Neovim is not started, and the error says what to name
    (`util/NeovimNotFoundException`).
- The preference page shows which Neovim runs: the dedicated one and that it was checked, or the option's program and its version ("from -Dneovim4eclipse.nvim").
- Process is started with `ProcessBuilder` and the inherited environment (no shell).
- On Windows, `SHELL` is not passed on (`NvimExecutable.prepareEnvironment`): Eclipse started from Git Bash
  inherits `SHELL=/usr/bin/bash`, which Neovim would combine with `cmd.exe`'s flags, so `:!` commands failed.
  Neovim then uses `cmd.exe`; set `'shell'` and its flags in `init.lua` for another shell.
- Simplest install: *Get Neovim* (README, "Get Neovim"), with no `-Dneovim4eclipse.nvim`. It downloads
  through Eclipse's proxy settings (`IProxyService.select` per address, as p2 does; Eclipse's
  *Native* reads environment variables and GNOME on Linux, the Windows settings on Windows, nothing
  on macOS), or installs an archive the user picked. A proxy that wants a password needs
  `-Djdk.http.auth.tunneling.disabledSchemes=` in `eclipse.ini` (the JDK's default refuses Basic
  logins through HTTPS tunnels; Eclipse's own updater has the same limit). Files Java unpacks carry
  no macOS quarantine and no Windows Mark of the Web, so `bin/nvim` runs at once; an archive
  extracted by hand from a browser download needs `xattr -dr com.apple.quarantine` on macOS.
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

1. **Windows path with spaces** — name an absolute path in `-Dneovim4eclipse.nvim` without quotes in `eclipse.ini` (`-Dneovim4eclipse.nvim=C:\Program Files\Neovim\bin\nvim.exe`); `ProcessBuilder` does not use a shell.
2. **macOS Gatekeeper** — first launch of a Homebrew/nvim binary may need approval.
3. **CI on all three** — the *Build* workflow runs every test (unit, text, SWT, UI and the
   integration tests with a real Neovim) on Linux, Windows and macOS: the Linux jobs under Xvfb, the
   `platforms` job on `windows-latest` (x86_64) and `macos-latest` (Apple silicon), which have a
   desktop. Unit tests in `tests/unit` need no GUI; the others need a display. Not covered: Linux on
   aarch64, Windows on aarch64 and macOS on x86_64 (no matching runner is used), and Wayland.
4. **RPC EOF on shutdown** — closing sends `:qa!` as a notification and waits for the process to exit (milliseconds); `RpcChannel.closing()` marks the end of the stream as expected, so it is logged at FINE only. A WARNING "Neovim exited unexpectedly" means Neovim really went away during a session (it crashed or was killed); anything Neovim writes to stderr is logged as a WARNING "Neovim stderr: ...". On Windows, `nvim --embed` passes nothing on to that stderr pipe (Neovim 0.12, tried with its own writes and with a child process's), so nothing is logged there; diagnose with Neovim's own log (`:help $NVIM_LOG_FILE`) instead.
5. **macOS: links open in MacVim, or not at all** — Eclipse's *Default system web browser* opens a
   link with the app for `.html` files (`Program.findProgram("html")` in `org.eclipse.ui.browser`),
   not the default browser, and a browser added by its `.app` folder does not start. The fix, an
   entry with `/usr/bin/open` and `-a Safari %URL%`, is in the README, Get Neovim, "The dialog's
   links".

## Quick verification

```bash
./mvnw test -f tests/unit/pom.xml                 # unit tests, no Eclipse or Neovim needed
./mvnw test -f tests/unit/pom.xml -Pintegration   # real nvim required; skipped automatically if missing
```

`PlatformUtilTest`, `NvimExecutableTest` and `KeyMappingTest` cover the OS-dependent logic by passing
OS names/PATH values in, so the Windows and macOS branches are tested on any machine.
