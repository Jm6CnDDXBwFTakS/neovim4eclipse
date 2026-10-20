# Eclipse PDE setup

For a headless CI build of the plug-in and update site, use Maven/Tycho (`./mvnw clean verify`); see [PACKAGING.md](PACKAGING.md).


1. Install **Eclipse IDE** with **PDE** and a **Java 21** JRE registered under Installed JREs.
2. *File → Import → Maven → Existing Maven Projects*, and select the repository root (the `.project` files
   are not in git; m2e creates them).
3. Ensure `lib/*.jar` exist: run `./mvnw generate-resources` once from the repository root.
4. Open `META-INF/MANIFEST.MF` → Overview → **Launch an Eclipse application** (or a custom Eclipse Application launch config).
5. Runtime JRE: Java 21.
6. Open a `.txt` / source file — the first attach logs `Started Neovim pid=...` and `NeovimSession ready`
   (java.util.logging, level INFO). Each attach and detach is logged at FINE: to see those lines, add
   `-Declipseneovim.logLevel=FINE` to the launch configuration's *Arguments* tab, *VM arguments*.
   They appear in this Eclipse's Console view (README, "If `Ctrl-V` does nothing").

## Common problems

| Symptom | Fix |
|---------|-----|
| `nvim` not found | Launch Eclipse from a shell where `which nvim` works |
| Stale behavior | Project → Clean; confirm the launch uses the workspace plug-in |
| Freeze on `ciw` | Ensure `inputAsync` is used (keys not processed on UI thread) |
| Extra blank line on attach | `BufferSynchronizer.documentToLines` must drop Eclipse's trailing empty line |
| Caret off by a few columns on non-ASCII lines | Columns must go through `Utf8Columns` (see ARCHITECTURE) |

## Manual commands

- **Neovim → Toggle Neovim / Eclipse Mode** (also the *NVIM* toolbar button, `Ctrl+Shift+M`)
- **Neovim → Preferences...** opens the Neovim preference page with its sub-pages (Status Bar, Ctrl Keys)
  expanded.
- *Attach to Active Editor* (`Ctrl+Shift+N`) and *Detach from Active Editor* have no menu entries,
  since attaching is automatic; run them from Quick Access (`Ctrl+3`) if needed.
