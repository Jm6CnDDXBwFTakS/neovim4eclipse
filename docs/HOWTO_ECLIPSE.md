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
   `-Dneovim4eclipse.logLevel=FINE` to the launch configuration's *Arguments* tab, *VM arguments*.
   They appear in this Eclipse's Console view (README, "If `Ctrl-V` does nothing").

## Common problems

| Symptom | Fix |
|---------|-----|
| `nvim` not found | Launch Eclipse from a shell where `which nvim` works |
| Stale behavior | Project → Clean; confirm the launch uses the workspace plug-in |
| Freeze on `ciw` | Ensure `inputAsync` is used (keys not processed on UI thread) |
| Extra blank line on attach | `BufferSynchronizer.documentToLines` must drop Eclipse's trailing empty line |
| Caret off by a few columns on non-ASCII lines | Columns must go through `Utf8Columns` (see ARCHITECTURE) |
| Import fails: "'Updating classpath' has encountered a problem. Resource '/shared' does not exist." | A `build.properties` names a folder outside its project (`../shared/src/`), which PDE cannot resolve; keep every folder inside the project. `ProjectLayoutTest` (`tests/unit`) checks this, and that the folders the test POMs add with build-helper exist. After moving folders, *Maven → Update Project* on the imported projects |

## Manual commands

- **Neovim → Neovim Mode**, a checkbox (also the Neovim toolbar button, which shows the logo, `Ctrl+Shift+M`).
  The button is pressed with the color logo in Neovim mode, raised with a grey logo in Eclipse mode,
  and greyed out while the active editor has no Neovim (`Ctrl+Shift+M` then does nothing). Its
  tooltip says why: not a text editor (an image, a form page), or a text editor that *Attach to
  Active Editor* (`Ctrl+Shift+N`) would attach Neovim to.
- **Neovim → Preferences...** opens the Neovim preference page with its sub-pages (Status Bar, Ctrl Keys)
  expanded.
- *Attach to Active Editor* (`Ctrl+Shift+N`) and *Detach from Active Editor* have no menu entries,
  since attaching is automatic; run them from Quick Access (`Ctrl+3`) if needed.
