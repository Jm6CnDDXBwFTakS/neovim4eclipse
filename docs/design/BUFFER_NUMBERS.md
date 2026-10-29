# Design note: a closed file keeps its buffer number, as with Vim's `:bd`

Status: **planned** (2026-10-29), not built. Branch `feature/buffer-numbers` from `release/0.1.10`
at `7bbe802`. The experiments used Neovim 0.12.6 on Linux, headless (`nvim --clean -l`). Decisions
made with the maintainer are marked *(decided)*.

## Summary

In Vim, `:bd` *unloads* a buffer and takes it off the `:ls` list, but keeps it: its number, its
marks and its last cursor position (`'"`). `:e` of the file later brings the same buffer back. The
plug-in instead *wipes* a file's buffer when its last editor closes (`DocumentBuffers.close`,
`nvim_buf_delete` without `unload`, which is `:bwipeout`). So a reopened file gets a new, higher
number, and its marks are gone. Example: a.txt is buffer 1, b.txt 2; close a.txt's editor and open
it again: Vim says 1, the plug-in says 3, and `:b 1` fails.

**Plan (B, *decided*):** do what `:bd` does. When the last editor of a file closes, unload and
unlist its buffer instead of wiping it. When an editor opens on that file again, reuse the buffer.
When Neovim moves into a workspace file that has no editor by itself (`:b 3` or `Ctrl-^` into a
closed file's buffer, `gf`, `'A`), Eclipse opens the file's editor, as Vim edits the file. That last
part is the quickfix plan's "way B" (docs/design/DIAGNOSTICS.md, section 5), built here *(decided
2026-10-29, Q1)*.

**Why:** Neovim already does this; the plug-in only stops undoing it ("Neovim does the work"). The
numbers in `:ls`, `:b N` and `:bd N` then mean what they mean in Vim, and marks survive a close.

Not changed: the undo history is lost when the buffer unloads (as in Vim, unless `'undofile'`), and
jumps into a closed file are dropped (Neovim's default `'jumpoptions'` has `clean`). Restart Neovim
starts the numbers over, as a new Vim does.

## 1. What Neovim does (experiments)

| # | Experiment | Result |
|---|---|---|
| X1 | `:e a.txt`, `:e b.txt`, `:e c.txt`, `:bd a.txt`, `:e a.txt` | a.txt is buffer 1 again. After `:bd` it is listed by `:ls!` only, as `1u`. |
| X2 | `:bw b.txt`, `:e b.txt` | b.txt gets a new number (4): wiping is what renumbers. |
| X3 | `ma`, cursor on line 3, `:bd`, `:e` | `'a` and `'"` are kept, and `:e` puts the cursor back on line 3. |
| X4 | X3 with `'buftype'` `acwrite` or `nofile` (the plug-in's document buffers) | The marks are **lost** on unload (`'a` reads 0). With `swapfile` off or `bufhidden=hide` they are kept: it is the buftype. |
| X5 | X4, but `'buftype'` set back to `''` just before unloading | The marks are kept. |
| X6 | A buffer made as `CREATE_DOCUMENT_BUFFER` makes it, closed as in X5, then `bufadd(name)` | Returns the same number; `bufload` loads it again. Options and `b:neovim4eclipse_file` survive the unload. |
| X7 | Closing as in X5 fires | `BufUnload`, then `BufDelete` (from unlisting). Not `BufWipeout`. |
| X8 | `:b 2` into the closed buffer | Neovim reads the file from disk (`BufReadPre`, `BufReadPost`, `BufEnter`, `BufWinEnter`), the cursor goes to `'"` (line 3), `'a` is there, `swapfile` stays off. |
| X9 | `Ctrl-^` when the alternate file is the closed buffer | Neovim enters and loads it, as X8. |

(X4 is observed, not documented by Neovim as far as I found. Uncertain why; X5 works around it.)

## 2. The plan, by part

### 2.1 Close: unload, not wipe

`DocumentBuffers.close` keeps its first half (forget the buffer in Java, `RELEASE_BUFFER` closes
its windows, return to the attached buffer). The wipe becomes a script `CLOSE_DOCUMENT_BUFFER`:

1. Remove the plug-in's buffer-local autocommands (`BufWriteCmd`, the safety net): they belong to a
   document with an editor. They move into an augroup `neovim4eclipse_document` so that they can
   be cleared for that buffer alone, and set again when the buffer is reused (today
   `DOCUMENT_BUFFER` would add them a second time).
2. `'buftype'` back to `''` (X4, X5), so the marks survive.
3. `nvim_buf_delete(buf, { force = true, unload = true })`, then `'buflisted'` off: `:bd`.

`b:neovim4eclipse_file` stays: it marks the buffer as a closed document's (the safety net's
`BufDelete` notice from step 3 reaches Eclipse after Java has forgotten the buffer, and is ignored,
as today for the wipe).

### 2.2 Open: reuse the closed buffer

`CREATE_DOCUMENT_BUFFER` today makes a new unnamed buffer when one of that name exists, so that two
documents never share one. A closed document's buffer (`b:neovim4eclipse_file` set, and not a live
document: unlisted, or not `acwrite`) is instead reused: the same options as a new one
(`nofile`, `bufhidden=hide`, no swap file), `bufload` if it is not loaded (X8 may have loaded it from
disk), listed again. Eclipse's text is pushed next, as for a new buffer. A live document's buffer of
that name still gets the unnamed fallback.

### 2.3 Neovim moves into a file without an editor: Eclipse opens it (way B)

`:b 3`, `Ctrl-^` and `'A` into a closed document's buffer load it from disk (X8, X9); so do `gf`,
`'A` and `:b name` into a workspace file never opened in this session. Today the plug-in sends Neovim
back ("a closed file is undone", README). With B, `EditorAttachManager.followNeovimInto` opens that
file's editor instead, when the buffer is a file in the Eclipse workspace (as `:e` of a file without
an editor does, `EclipseFileCommands`), then follows as for an open file: the window that jumped
travels into the new editor (docs/design/WINDOWS.md, E2), so `Ctrl-O` there goes back. The attach
finds the buffer by name (2.2) and pushes Eclipse's text. Anything else Neovim enters (`:help`, a
scratch buffer, a file outside the workspace) is still undone, as today.

This is the quickfix plan's way B *(decided 2026-10-29, Q1: general now rather than closed documents
only)*: the mechanism and its risk are the same, so it is built once. Quickfix (phase 2 of
DIAGNOSTICS.md) then only adds the lists, `:Eproblems`, `:Etasks` and `:copen`.

**The risk, and the experiment before building** (DIAGNOSTICS.md section 5): Neovim has just read
the file from disk (`BufReadPre`, `BufReadPost`, filetype detection, the user's `BufRead`
autocommands, a swap file, an undo history starting there), while the plug-in fills a document's
buffer from Eclipse's text. Step 3 starts with a probe in the test Eclipse of what that buffer holds
when the attach takes it over: options, filetype, swap file, undo, cursor, and whether the user's
autocommands ran twice. Then the reuse in 2.2 turns that buffer into a document buffer.

`:e #` needs nothing: `HOST_COMMANDS` already gives `:e` of a file without an editor to Eclipse.

### 2.4 What else assumes the wipe

| Place | Today | With B |
|---|---|---|
| `wipe_left_over` (`DOCUMENT_BUFFER`) | wipes unloaded buffers named like a written file | already skips buffers with `b:neovim4eclipse_file`: unchanged |
| `HOST_COMMANDS` `edits_an_open_document` | loaded document buffers only | unchanged: a closed one is not loaded (or, after X8, not listed) |
| `:bd 3` of a closed file's number | E516-like "no editor" message | the same: nothing to close |
| `:ls` | closed files gone | closed files gone from `:ls`, shown by `:ls!` with `u`, as in Vim |
| Tests that wait for a wipe | `NeovimBufferIntegrationTest` (buffers a and b), `NeovimWindowsIntegrationTest`, `TwoEditorsEndToEndTest` (`:ls!` count) | rewritten to Vim's rules: same number after reopening, `:b N` reopens |

## 3. Steps (one commit each)

1. **Close unloads** (2.1), with the augroup. Lua specs: the number, `'a` and `'"` survive a close;
   the plug-in's autocommands are gone from the closed buffer. Integration tests adjusted.
2. **Open reuses** (2.2). Lua spec: `CREATE_DOCUMENT_BUFFER` returns the closed buffer's number.
   Integration test: close and reopen a document, same number, `'a` still there. UI test:
   `:ls` numbers after closing and reopening an editor.
3. **Neovim moving into a workspace file without an editor opens it** (2.3, way B): the probe
   first, then the change. UI tests: `:b N` and `Ctrl-^` into a closed file, `gf` and `'A` into a
   file never opened, each followed by `Ctrl-O` back; `:help` still undone.
4. **Docs**: README (`:ls`, `:bd`, "a closed file is undone"), WINDOWS.md, ARCHITECTURE, LEARNING
   (a walkthrough of a close and a reopen), CHANGELOG.

## 4. Open questions

- **Q1** 2.3's limit: *(decided 2026-10-29)* general now: any workspace file Neovim moves into
  (the quickfix plan's way B), not only closed documents.
- **Q2** Should the reopened editor's caret go to `'"` (X8 restores it in Neovim), or stay where
  Eclipse opens a file (line 1, or Eclipse's own remembered place)? Proposed: Neovim's, since
  Neovim is the one that remembered it; the attach would then take Neovim's cursor instead of
  pushing Eclipse's caret.
