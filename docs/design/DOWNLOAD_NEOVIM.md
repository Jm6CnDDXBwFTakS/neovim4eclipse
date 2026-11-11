# Design note: Get Neovim (download or install from a file) and the plug-in folder layout

The experiments below used Neovim 0.12.6 on Linux arm64, Windows 11 arm64 and macOS arm64, Eclipse
2024-12 and JDK 25.

## Summary

The plug-in needs Neovim `NeovimVersion.MINIMUM` (0.12.6) and raises it readily
([UPGRADING.md](../UPGRADING.md)), so many users' system Neovim is too old. Without help, a
Marketplace user without a new enough Neovim installs the plug-in and finds that nothing works, and
the fix is a hand install: download a release archive, extract it into the plug-in's folder, on
macOS remove the quarantine flag.

So one entry, *Get Neovim 0.12.6…*, is offered in three places. It opens a dialog that can
**download** the official release archive from GitHub or **install one from a file** the user
already has. Either way the plug-in checks the archive against a SHA-256 pinned in the plug-in,
unpacks it into its own folder (`neovim/<platform code>/`), checks that it runs, and uses it. This
copy is called the **dedicated Neovim** in everything the user sees: the dialogs ("Install the
dedicated Neovim 0.12.6…?", the *Folder* point "Dedicated to the plug-in; no other Neovim is
changed."), the preference note ("Found: dedicated Neovim 0.12.6, …") and the help. The name tells
the plug-in's copy apart from a Neovim the user installed.

Without a startup option the plug-in runs only the dedicated Neovim, and only if every file of it
matches the release (section 7). A Neovim of one's own is named by a startup option, not a
preference (section 8).

## 1. Where it is offered

One entry everywhere, **Get Neovim 0.12.6…** (the version comes from the data file, section 5):

- **Preference page** (*Window → Preferences → Neovim*): in the *Neovim* group, on a row of its own
  under the note (a second button in a field's row would widen every field of the page's grid).
  When the note finds the dedicated Neovim at the pinned release or newer
  (`NvimProbe.Note.current`), the button is hidden and a small *Reinstall…* link under the note
  opens the same dialog: there is nothing to get, but a copy can be damaged in a way `--version`
  does not show. A copy that does not answer gets the button back, its version being unknown. The
  note is a `Label` and the link a `Link` of its own, because a `Link` reads `<a>…</a>` in its
  text, and the note shows paths anyone who can name a folder chooses (`prefs/NeovimStatusSection`).
- **"Neovim not started" notice**: the default button, next to *Open Preferences*.
- **Neovim menu** (and Quick Access): shown only while `neovim/<code>/` is missing or holds a Neovim
  older than the one it would install, whatever else is installed. Checked by running `--version`
  (`InstalledVersions`, cached), through `prefs/NeovimPropertyTester` (`prefs/GetNeovimOffer` is the
  menu's `visibleWhen`). The menu label is *Get Neovim...* without the version, because `plugin.xml`
  cannot read the data file; the dialog and the other two entries name it.

Not in the welcome dialog, which only shows after Neovim has started.

"Get Neovim" is in quotes in running text where it names the button or the menu entry. The button
keeps its short label, *Get Neovim 0.12.6…*, not *Get Dedicated Neovim 0.12.6…*: it sits in a row
of three in the notice, and the dialog it opens says "dedicated" in its first line.

### The confirmation dialog

All three open the same confirmation dialog. It says that the download contacts github.com and that
the SHA-256 is checked. Links open in the external browser (`prefs/ExternalBrowser`; when that
browser cannot start, a *Link Not Opened* dialog shows the link to copy, `util/LinkNotOpened`). The
plug-in never contacts VirusTotal itself. Its buttons: **Download** (default) · **Install from
File…** · Cancel.

The dialogs are the shared `dialogs/HeadedMessageDialog`, the layout of the welcome and the Restart
Neovim question: an opening line, then bold headings with their text beside them, with the
platform's icon (question, information, error). A point may carry a value in a read-only
fixed-width field with *Copy* (the folder, the program, the SHA-256, the `eclipse.ini` line) and
links on one line. The words, values and links come from `util/GetNeovimText`, as
`util/HeadedMessage`s. The confirmation's points:

- *Folder*: the target folder.
- *Download*: the asset, its size, github.com and the host it redirects to.
- *From File*: with *Download nvim-linux-arm64.tar.gz*, the archive's own URL, a direct download.
- *SHA-256*: the pinned hash, with the *GitHub release* page and the *VirusTotal report*
  (`https://www.virustotal.com/gui/file/<sha256>`).

The full URLs are the links' tooltips rather than text. The headed dialogs leave a little space
between points (`POINT_DLUS`, 3 dialog units above each point but the first; none above a row
without a heading, which continues the point above).

**When the plug-in opens the dialog itself** (no dedicated Neovim at Eclipse's start), it starts
with *Why* ("The dedicated Neovim is not installed. Neovim for Eclipse will only run with the
dedicated Neovim; until it is installed or replaced, ...") and ends with *Instead*: cancel and start
Eclipse with `-vmargs -Dneovim4eclipse.nvim=/path/to/nvim` (or the line in `eclipse.ini`), run
unchecked, "so be sure it is Neovim" (section 8). "Only" because nothing is searched, so an
installed `/usr/bin/nvim` is not picked up; "runs only this Neovim" read as if no other Neovim
could be used at all. *Instead* shows only then, not when the user opens Get Neovim from the menu
or the preference page: they chose to install, and the dedicated Neovim is the default. Text only,
no third button. The command is in a *Copy* field rather than in the wrapped text, where it broke
at the `/` of its path.

## 2. How it runs

A background `Job` (status bar and Progress view, cancellable, never on the UI thread):

1. **Get the archive.** *Download*: Java's `HttpClient` (redirects followed; proxy as in section 4)
   into a temporary file in `neovim/`. The client's threads write the body; the Job's thread looks
   every 250 ms and stops the download on Cancel, after 60 s without a byte (a stalled connection,
   which the request timeout does not cover; counted from the request, so a server that never
   answers stalls too), or when the file grows past the pinned size.
   *Install from File*: the chosen file, only read.
2. **Copy and check**: the archive is copied into a temporary file in `neovim/` while its SHA-256 is
   computed, and size and hash are compared with the data file's entry for this platform; the copy
   is what is unpacked, so a file that changes after the check (a browser still writing it) cannot
   slip through. A file of the wrong size is only hashed, not copied. A mismatch names both hashes;
   if the hash belongs to another platform's entry, the message names the asset this Eclipse needs
   ("this is the archive for macOS arm64; this Eclipse needs nvim-linux-arm64.tar.gz").
3. **Unpack** into `neovim/<code>.new/`: our own small tar reader (ustar and PAX names, execute bits,
   a link refused rather than skipped, so a Neovim that starts shipping one fails loudly instead of
   installing incomplete; any entry outside the target refused, the "zip slip" check) or
   `java.util.zip` for the Windows `.zip`. The archive's one top folder is dropped.
4. **Run** `bin/nvim --version` and require exactly the data file's version. A Linux build that
   cannot run here (musl) fails with a clear message. The unpacked folder must also pass the tree
   check of section 7.
5. **Swap.** If no Neovim runs from `neovim/<code>/`: `<code>` becomes `<code>.old`, `<code>.new`
   becomes `<code>`, `.old` is deleted. If one runs, the swap waits for *Restart Neovim* (Windows
   locks a running `nvim.exe`; on Linux and macOS the running Neovim would lose its runtime files);
   a failed swap keeps the old one and is tried again at the next start. "Runs" means any process,
   not only this Eclipse's session: the folder is the user's, shared by every Eclipse they run, so
   another Eclipse's Neovim holds the swap too (`util/NeovimProcesses`, from
   `ProcessHandle.allProcesses()`). A `.old` left by an earlier failed delete is moved aside
   (`<code>.old-<n>`) rather than blocking the swap.
   Whether Neovim runs from there is asked at the swap, not when the Job starts
   (`NeovimInstaller.SwapGate`, answered by `Activator.swapUnlessRunningFrom`, which holds the lock a
   session start takes): a Neovim started during the download counts, and none of this Eclipse's
   can start during the two moves. The pending swap runs before every Neovim start
   (`Activator.getOrCreateSession`), not only at *Restart Neovim*; there only the moves run (the
   session may start on the UI thread). The replaced `.old` is deleted by a background Job
   (`NeovimInstaller.deleteReplaced`), unless a process still runs from it. The same Job, at every
   Neovim start, deletes download and install copies older than an hour (`STALE_DOWNLOAD`), left by
   an Eclipse killed in the middle.
6. Temporary files are always removed, also on error or cancel. A lock file in `neovim/` stops two
   Eclipses from installing at once; the empty file stays (the lock is on the open file, not on its
   presence). Within one Eclipse only the holder opens the file (`NeovimInstaller.InstallLock`): on
   Linux and macOS the lock is a POSIX one, which closing any other channel to the file in the same
   process would release, so the start-up clean-up must not even open it while an install runs.

Limits of the swap's "runs" check: another user's processes may not be visible (fine, the folder is
the user's own), and a process another Eclipse starts in the few milliseconds of the two moves is
not seen.

**On success** a done note: the active editor attaches at once; the note repeats the SHA-256,
*Copy* and the VirusTotal link, and offers *Restart Neovim* when a session runs and the swap waits.
When Eclipse was started with the option naming another Neovim, an *Option* row says the download
is not used until the option is removed and Eclipse restarted; the plug-in cannot remove a startup
option, so there is no button for it. Nothing changes unasked.

**On failure** a dialog with *Retry*, the GitHub release-page link and *Install from File…*. Two
reasons get a layout of their own instead of the message: a proxy password (*Why*, *Fix* with the
`eclipse.ini` line to copy, *Then*) and a wrong archive (*Why*, then *Expected* and *Your File* with
both sizes and hashes in fields, to compare). *Instead* (download in a browser, Install from File)
shows only where a browser can help: not for a Neovim that does not run, or a busy folder.

### Install from a file

For when Eclipse cannot download: a proxy that wants a password, a blocked
`release-assets.githubusercontent.com`, or no internet. The user gets the archive in a browser or
copies it over.

- A file chooser filtered to this platform's asset name (`nvim-linux-arm64.tar.gz`), which the
  dialog names.
- **Only the pinned archive** is accepted. Any other Neovim is a startup option (section 8), so
  `neovim/<code>/` always means "the plug-in's checked Neovim".
- The chosen file is never moved, renamed or deleted.
- Files Java unpacks carry no quarantine flag (macOS) or Mark of the Web (Windows), so a
  browser-downloaded archive needs no `xattr` step (section 6).

## 3. The plug-in folder layout

```
<data home>/neovim4eclipse-plugin/   one subfolder per purpose (only neovim/ today)
  neovim/
    la/       the one Neovim for Linux arm64: bin/nvim, lib/, share/nvim/runtime/
    lx/       another platform (for example the same home on an x86_64 machine)
    la.new/   a checked archive waiting to replace la/
```

- **Platform codes** (`util/NeovimPlatform`): an OS letter (l Linux, m macOS, w Windows) and an
  arch letter (x x86_64, a arm64): `lx la mx ma wx wa`. A third letter is kept free (`lxm` for a
  musl build). A platform without a code is offered nothing. The data file's keys are the codes too
  (`la.asset`, `la.sha256`), not `linux.arm64`: one name for a platform everywhere.
- **One Neovim per platform**: no version folders, no `current` link, no picker. The stable path is
  `neovim/<code>/bin/nvim`. A user who wants another version installs it elsewhere and names it with
  the startup option.
- **Strict**: only `neovim/<code>/bin/nvim` with `share/nvim/runtime` beside `bin` counts, and only
  if it passes the check of section 7. A hand install means extracting the archive and renaming its
  folder to the code.
- **Old layouts and anything else in `neovim/`** (`nvim-linux-arm64_0.12.6/`, `current/`) are not
  searched, never moved or deleted. The preference note and the notice list their full paths, one a
  line ("Not used by the plug-in, you can delete:"; `PluginNeovim.unusedEntries`).
- **Without the option, the dedicated Neovim only** (section 7): `neovim/<code>/bin/nvim`, checked,
  and nothing else. No `PATH`, no usual install folders, no folder search.
- **The real processor decides the code**, so an Eclipse running under emulation (x64 on an arm64
  Mac or Windows machine) gets a native Neovim: macOS asks `sysctl -n hw.optional.arm64`, Windows
  reads the system `PROCESSOR_ARCHITECTURE` from the registry (`reg query`, about 50 ms; no
  PowerShell fallback). Once per session on a background thread, cached (`util/ProcessorProbe`);
  `os.arch` until it answers or if it fails. Unverified: the registry's answer under x64 emulation.
- **Search order** (`PluginNeovim.searchHomes`, `NvimExecutable.find`): the real processor's folder,
  then Java's, then the same system's other one, each only if it passes the check and its Neovim
  answers `--version` (an x86_64 process starts an arm64 Neovim on Apple silicon and arm64 Windows,
  so the emulated one still works if it is the only one).
- **An outdated dedicated Neovim**: after an update of the plug-in raises the minimum,
  `neovim/<code>/` holds the old release. Its files differ from the new pinned release, so it fails
  the check and does not run; the menu entry shows, and Get Neovim replaces it. The pinned version
  stays equal to the minimum (`VersionConsistencyTest`).
- Uninstalling the plug-in leaves the folder (about 40 MB). No license or NOTICE files are needed:
  Neovim is not redistributed.

## 4. Proxy

Spiked on Linux, Windows and macOS with local logging proxies and the real GitHub asset.

- **Eclipse's setting, as p2 gets it.** The download's `HttpClient` gets a small `ProxySelector`
  that asks `IProxyService.select(uri)` for each request (`org.eclipse.core.net`, present in every
  IDE). No proxy gives `Proxy.NO_PROXY`. The download then works wherever Eclipse's own updater works.
- What Eclipse's default, *Native*, reads: environment variables and GNOME settings on Linux; the
  Windows proxy settings on Windows (not `https_proxy`); nothing on macOS, where Eclipse has no
  reader. *Manual* worked on all three.
- On macOS, after a switch from Manual to Native, `IProxyService.select` still returns the Manual
  proxy (it checks only the "enabled" preference and falls back to the stored data), while the system
  properties say DIRECT. ECF asks the same `select`, so following `select` matches p2.
- **Proxies that need a password**: Java blocks Basic logins through an HTTPS proxy by default
  (`jdk.http.auth.tunneling.disabledSchemes=Basic`, one setting for the whole JVM). Every variant got
  407 on all three systems: a plain client, Eclipse's selector, an `Authenticator` from Eclipse's
  stored login, Eclipse's own `NetAuthenticator`, a hand-set `Proxy-Authorization` header (not sent
  on `CONNECT`), and Eclipse's own download API (ECF). With
  `-Djdk.http.auth.tunneling.disabledSchemes=` the `Authenticator` is asked and it works.
  So on a 407 the error says the proxy wants a password, names that `eclipse.ini` line, and offers
  the release page and *Install from File…*. With the line set, an `Authenticator` built from
  Eclipse's stored login answers. The plug-in never changes the property itself.
- **SOCKS proxies**: Java's `HttpClient` uses only HTTP proxies; given a SOCKS one it connects
  directly, past the proxy. So the selector leaves SOCKS entries out, and when Eclipse sends GitHub
  through SOCKS only, Get Neovim stops with a message (`NeovimDownload.socksOnly`) pointing to
  *Install from File…* or an HTTP proxy, instead of a direct download that times out or bypasses
  the proxy. Both rules are `util/ProxyChoice`'s (unit-tested); `prefs/EclipseProxies` only asks
  Eclipse.
- A test that sets a proxy login must pass `-eclipse.keyring <tmp> -eclipse.password <file>`:
  otherwise the login goes into the user's real secure storage (and the macOS Keychain), and the test
  Eclipse never exits (a "Password Hint Needed" dialog on Windows; unseen on Linux and macOS).
- Not checked: Windows proxy auto-config scripts (PAC/WPAD).

## 5. One data file per Neovim release

Everything that changes between Neovim releases lives in
`util/neovim-downloads.properties` (read by `util/NeovimRelease`): the version, the URL template,
and per platform the asset name, size, SHA-256 and tree hash (section 7). Code holds no version,
URL, asset name or hash. Asset names do change: 0.10.4 renamed `nvim-linux64` to
`nvim-linux-x86_64`.

- **Updater**: `java releng/UpdateNeovimDownloads.java <version>`, a single-file Java program, so it
  runs the same on all three systems with no `gh`, `jq` or Python. It downloads all six archives,
  hashes them, requires GitHub's published digest to match, and rewrites the file. Nothing is
  written if a step fails. It refuses a release less than 7 days old (`--allow-young` after the
  version overrides it) and prints each archive's VirusTotal page to look at before committing.
- **Trust on first use**: GitHub's digest only catches a download damaged on its way, since GitHub
  computes it from whatever was uploaded. Neovim publishes no checksum file, signature or build
  attestation (checked for v0.12.6), so nothing independent exists to compare with; the week's wait,
  the VirusTotal pages and the later checks (`--check` at each release, `UpdateNeovimDownloads.java install` in every
  CI run) are what narrow it. If Neovim starts signing its archives, verify that in the updater.
- **`--check`**: verifies the six URLs and hashes, writes nothing. It runs in the release workflow
  on GitHub at the tag push, before anything is published; a mismatch stops the release.
- **`--virustotal`**: optional, run by hand on the maintainer's machine with a personal
  `VT_API_KEY`. Not in `--check`, the release process, CI or the plug-in; the key never leaves that
  machine.
- **Checks**: `VersionConsistencyTest` ties the file's version to `MINIMUM`; all six entries,
  64-hex hashes, https URLs on `github.com/neovim/neovim`.
- **CI** installs through `java releng/UpdateNeovimDownloads.java install auto <folder>`, which reads the same file and
  checks size, SHA-256 and tree hash of the archive it downloads; there is no separate Neovim
  version or archive list in the workflows.
- **UPGRADING.md**: set `MINIMUM`, run the updater, fix what `VersionConsistencyTest` lists.

## 6. Experiments

**Java-only download, check, unpack and run:**

| Platform | SHA-256 | Unpacked with Java only | Runs | Quarantine / Mark of the Web |
|---|---|---|---|---|
| Linux arm64 | matches | 2127 files, no links, longest path 84 | NVIM v0.12.6 | n/a |
| Windows 11 arm64 | matches | 2133 files, longest 82 | NVIM v0.12.6 | no `Zone.Identifier` stream |
| macOS arm64 | matches | 2126 files, longest 84 | NVIM v0.12.6 | no `com.apple.quarantine` |

Downloads took 0.8 to 1.0 s through `release-assets.githubusercontent.com`. On macOS `spctl`
rejects `bin/nvim` (ad-hoc signed, not notarized), but it runs, because Gatekeeper only checks
quarantined files. A browser download for a hand install does get quarantined, hence the README's
`xattr -dr com.apple.quarantine <folder>` for extracting by hand.

**Proxy**: section 4.

## 7. Dedicated Neovim only, checked at start

The plug-in runs only:

- **the dedicated Neovim** (no startup option), and only if it passes the check below; or
- **one program** the startup option names by its absolute path, unchecked and unsupported, for
  systems with no official build (musl, NixOS without nix-ld, FreeBSD). A folder is refused,
  nothing is searched (section 8).

Rejected: searching. A folder given as the Neovim was once searched four levels deep, and
`nvim --version` ran on every `bin/nvim` with a `share/nvim/runtime` beside it: programs the user
never named (a planted layout in a cloned repository below a folder picked by mistake). Likewise
`PATH` and the usual install folders. With Get Neovim working, none of that is needed.

**The check** (`util/InstallTree`, `PluginNeovim.verified`): the tree hash of every file in the
install, `<sha256>  <path>` lines in UTF-8 byte order hashed again, which is exactly
`find . -type f | LC_ALL=C sort | xargs sha256sum | sha256sum` and can be checked by hand. The
updater computes it from the archive's entries while it downloads (`<code>.tree` in the data file),
the installer checks the unpacked folder against it before the swap, `UpdateNeovimDownloads.java install` checks it
in every CI run on all three systems, and the plug-in checks it at Eclipse's start (in the
background, `EarlyStartup`, which decides whether Get Neovim opens at once) and again in full
before every Neovim start, the first included (`Activator` forgets the kept answer). The first
start does not reuse EarlyStartup's answer, because that one may be hours old, and a runtime file
edited in place changes none of the four modification times the answer is kept by (the home,
`bin`, the program, `share/nvim/runtime`). It takes 0.1 to 0.2 s for 2,100 files on Linux. Files
the operating system adds (`.DS_Store`, `Thumbs.db`, `desktop.ini`, `._*`) are left out; a link or
other special file fails it. The whole `bin` folder counts, not just the program: Windows loads
DLLs from the program's folder first, and the official `bin` holds `lua51.dll` beside `nvim.exe`.

A failed check runs nothing of the install, not even `--version`: an older plug-in's Neovim and a
damaged one look alike ("The dedicated Neovim is missing, or its files differ from Neovim 0.12.6's
release"), and Get Neovim replaces it. Not protection against malware already on the machine, which
could change `init.lua` or `eclipse.ini` as easily; what it gives is that nothing found by
searching runs, and that corruption is caught.

## 8. A Neovim of your own is a startup option

A Neovim of one's own is named by the Java system property `neovim4eclipse.nvim`
(`util/NeovimOption`), given when Eclipse starts:

```bash
eclipse -vmargs -Dneovim4eclipse.nvim=/usr/local/bin/nvim
```

or the line `-Dneovim4eclipse.nvim=/usr/local/bin/nvim` after `-vmargs` in `eclipse.ini`. Unset or
blank, the dedicated Neovim runs. The value's rules are section 7's: an absolute program path,
unchecked and unsupported; a folder, a command name or a relative path is refused.

**Why not a preference** (the "Neovim program" field with *Browse…*, which it replaced):

- **The UI tests.** With a preference, the test Eclipse started with it blank and got `-Dnvim.path`
  only when each editor test set it; with no dedicated Neovim installed, Get Neovim opened by itself
  at the test Eclipse's start and the run went on inside its event loop (seen on macOS and Windows).
  A system property is there before the plug-in starts: the test pom's `nvim-path` profile passes
  `-Dnvim.path` on as `-Dneovim4eclipse.nvim` (only when given: an empty system property reaches the
  test Eclipse as the text `null`). Writing the value into a `-pluginCustomization` file was
  rejected: it also changed the preference's default in the test Eclipse.
- **Imported preferences.** A preference without a confirmation dialog assumed that importing an
  `.epf` file or running an Oomph setup that sets it is a deliberate act. But an `.epf` is a file
  teams pass around for formatter and editor settings, and one line in it would make the plug-in
  run any program unchecked. A startup option is set only by whoever starts Eclipse or can edit its
  installation.
- **One source, read once.** The program is fixed for the run; nothing restarts Neovim when a field
  changes, and no typed half-path can be run.

**The cost:** naming a Neovim of your own means a command line or an `eclipse.ini` line, and a
restart to change it. Who needs it: a system with no official build (FreeBSD, Alpine, NixOS without
nix-ld), or a security fix you need in a newer Neovim before *Get Neovim* offers it.

**`-vmargs` on the command line** adds to `eclipse.ini`'s only when `eclipse.ini` has
`--launcher.appendVmargs`, which the EPP packages have (checked on Linux, the committers package;
on macOS, both `Eclipse.app/Contents/MacOS/eclipse -vmargs ...` and `open -a Eclipse --args
-vmargs ...`; on Windows, `eclipse.exe -vmargs ...`; read back with `jcmd VM.command_line`).
Without it, the command line replaces them (docs/design/LOG_LEVEL.md, section 2), which drops
`-XstartOnFirstThread` on macOS. So the command line is documented for one start and `eclipse.ini`
for a permanent setting; editing `eclipse.ini` inside a signed macOS `Eclipse.app` is not needed.

**What the user sees:**

- *Window → Preferences → Neovim*: the *Neovim* group has no field and no *Browse…*, only the note
  (which names the option's program "from -Dneovim4eclipse.nvim"), *Get Neovim* or *Reinstall…*, and
  a help line naming the two cases, with the command on a line of its own (wrapped with the rest, it
  broke at the `/` of its path) (`prefs/NeovimStatusSection`).
- The "Neovim not started" notice's *Fix* for a named program: remove the option (from the command
  line or `eclipse.ini`) and restart Eclipse to use the dedicated Neovim, or set it to a working
  Neovim 0.12.6 or newer.
- Get Neovim's done note: an *Option* row when the option names another Neovim (section 2).
- **A value still stored** from 0.2.0 (the old preference key `nvimExecutable`) is shown once at
  Eclipse's start, with the value and the `-vmargs` line to *Copy*, and then removed ("Neovim
  Program Setting Moved", `util/OldNeovimProgram`, `editor/OldNeovimProgramNotice`). It is not used:
  the dedicated Neovim runs. Get Neovim's start offer waits until that note is closed, and then runs
  off the UI thread (it may hash the dedicated Neovim). No note when Eclipse already runs with the
  option (a second workspace with the old value): the value is only removed. The note's *Instead*
  reads "Only if you named that program yourself and need it (…)", since an imported preferences file can still set
  the old key; and a 0.2.0 folder value is offered as its `bin/nvim`.
- **Copying is never followed by editing.** The copied command quotes a path with a space
  (`-vmargs "-Dneovim4eclipse.nvim=C:\Program Files\…"`); the `eclipse.ini` line never does, and has
  a *Copy* field of its own under it in every dialog that names the option (`NeovimOption.rows`).
- **The option naming a dedicated Neovim's own `bin/nvim`** is that install as the dedicated
  Neovim (`NvimExecutable.find`): checked before every start and called dedicated by the notes, not
  run unchecked as a program of the user's; that very install, so `neovim/mx/bin/nvim` on Apple
  silicon runs `mx`, not the first verified home. The UI tests' `-Dnvim.path` is often that very
  program.

## 9. Classes

- `util/` (no Eclipse): `NeovimRelease` (the data file), `NeovimPlatform` (the codes),
  `ProcessorProbe`, `PluginNeovim` (the layout, the search order, the check, unused entries),
  `InstallTree` (the tree hash), `ArchiveExtractor`, `NeovimDownload`, `ProxyChoice` (which proxies
  to use, SOCKS only), `NeovimInstaller`, `NeovimProcesses`, `NeovimOption`, `NvimExecutable`,
  `OldNeovimProgram`, `GetNeovimText` (the dialogs' words, values and links).
- `prefs/`: `GetNeovim` (flow and Job), `EclipseProxies`, `GetNeovimOffer` (the menu's
  `visibleWhen`), `NeovimStatusSection` (its button and links through `WorkbenchSectionActions`),
  `ExternalBrowser`. In `prefs/` rather than `editor/`
  because the preference page uses them and `prefs/` does not depend on `editor/`.
- `editor/`: `GetNeovimHandler`, `OldNeovimProgramNotice`. `dialogs/`: `HeadedMessageDialog` (and `ActingHeadedDialog`, the welcome's).
