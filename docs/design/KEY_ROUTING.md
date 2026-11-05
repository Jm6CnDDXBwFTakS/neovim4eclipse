# Design note: routing `:`, `/`, `?` and `q` without guessing wrong

## Summary

Outside insert mode every key goes to Neovim, which alone decides what it means: `:` `/` `?` open a
command line or are the argument of the command before them (`f:`, `r/`, `d/foo`); `q` starts or
stops a recording or is part of `gqq`. The `:` box is a view of Neovim's own command line
(`util/CmdlineState`; docs/ARCHITECTURE.md, "Command line"): it opens when Neovim reports a line
(`cmdline_show`, from `ext_cmdline`), and the recording state comes from Neovim (`reg_recording()`).
`ZZ` and `ZQ` are Neovim mappings to Eclipse's save and close (`core/IdeAction`). Nothing on the UI
thread guesses from the keys typed so far.

This note explains why. The router (`core/KeyRouter`) once had to decide on the UI thread, before
Neovim had seen the key, whether a key went to Neovim or opened the editor's own box, because the
box had its own history, completion and search answering. It guessed from Vim's command grammar.
The three phases below are the steps from that guessing to the current design; what each found
about Neovim still holds, and is why the guessing is gone.

## How guessing failed

The guesses were a short list of special cases (`f t r @ m ' \` " q` take an argument, `g` and
`g?`, `ZZ`/`ZQ`), and Neovim's own state where it was fresh. Typing `gqq` showed the second `q` taken
for "start recording". Probing a real Neovim (below) found three wider kinds of error, all the same
root cause: a guess that overrides what Neovim knows, or no guess where one is needed. One of them,
mappings in the user's `init.lua` that open the command line (`nnoremap ; :`), no guessing in the
router can fix, because the router does not know the mappings.

### Ground truth from Neovim

A probe (Python, msgpack-rpc to `nvim --embed --clean`, Neovim 0.12.4) typed sequences one key at a
time and asked `nvim_get_mode` after each. `!` means `blocking`: Neovim waits for one more key that
belongs to the command, so a `:` `/` `?` there is not a prompt. `c` means a command line opened.

| Typed | Neovim after each key | What the guessing router did with the last key | Result |
|-------|-----------------------|----------------------------------------|--------|
| `gqq` then `:` | `g`→n! `q`→no `q`→n `:`→c | the 2nd `q` set *REGISTER* (recording), so `:` went to Neovim as its "register" | Neovim's own command line opens, invisible; `recordingByKeys` wrongly true |
| `di"` then `:` (also `yi'`, ``di` ``, `vi"`) | `"`→n `:`→c | `"`, `'`, `` ` `` set *ARGUMENT* although after `i`/`a` they name a text object | same: invisible Neovim command line. `vi"` then `:` is common |
| `[/`, `]/`, `d[/` | `[`→n! `/`→n | `[`, `]` not tracked: `/` opened the search box unless Neovim already reported `[` | box opens for a motion; right only when typed slowly |
| `<C-w>q` then `:` | `q` closes a window | `q` after `<C-w>` set *REGISTER* | as `gqq` |
| `g??` | `?`→no `?`→n | `g?` tracked as an operator | right |
| `"aq`, `@q`, `mq`, `dt"`, `ya(`, `guu`, `2d/x` | as expected | right | — |

### Mappings

A second probe attached a UI with `ext_cmdline` and defined `nnoremap ; :` and
`nnoremap <Space>s :%s/`. Typing `;` or `<Space>s` put Neovim in command-line mode (`c`), with
`%s/` already typed for the second. A router that sends `;` and `s` to Neovim like any key never
opens its own box: the user sees nothing while Neovim waits in its own command line. These are
common mappings in Vim users' configurations.

While a mapping is half typed (`<Space>` above), `nvim_get_mode` reports `n!`, the same as after
`f`. So when no keys are in flight, Neovim's `blocking` flag is a reliable answer to "is the next
key a fresh command?", mappings included.

### Root cause

The router's own tracking (`Pending`) was applied first, and Neovim's state only when the router had
no opinion (`isNeovimWaitingForThisKey`). A wrong guess therefore won even when Neovim had long
caught up. And no guess covers keys that mappings give a new meaning.

## Phase 1: fix the guesses, prefer Neovim's answer

Replaced by phase 3 (`util/KeyGrammar` is gone); kept for what it found.

- **Neovim first when it is current.** When no keys were in flight, prompt keys were decided from
  Neovim's `(mode, blocking)`: `blocking` → the key goes to Neovim; otherwise `:` `/` `?` open the
  box, except `?` while the operator is `g?`, tracked locally because `v:operator` cannot be read
  while Neovim waits (X4). The local state was used only while keys were in flight.
- **Better local guesses for fast typing**, in `util/KeyGrammar` (unit-tested), which replaced
  `KeyRouter.Pending` and `NvimKeys.takesArgument`; the router kept one flag of its own, for the
  held-back `Z` of `ZZ`/`ZQ`:
  - after an operator (`d c y < > = ! g~ gu gU g? gq gw zf g@`), `i`/`a` select a text object,
    so the next key (`"`, `'`, `` ` ``, `(`, `w` ...) is its name and takes no argument;
  - a doubled operator (`dd`, `gqq`, `g??`, `gUU`) ends the command: its last key is neither
    an argument command nor `q`-starts-recording;
  - prefixes whose next key always belongs to them: `g`, `z`, `[`, `]`, `<C-w>`, `<C-\>`;
  - unknown keys: no guess, the key goes to Neovim, and the next prompt key is decided from
    Neovim's state once it catches up (milliseconds), or opens the box if keys are still in flight.
  - The Ctrl-key commands, which send their key to Neovim without the router, told it which key
    went (`KeyRouter.sentElsewhere`), so `<C-v>` and `<C-w>` counted for the next key.
- **A differential test** (`KeyGrammarNeovimTest`, `tests/unit`, integration profile) typed 335
  sequences (every operator with every kind of continuation, counts, registers, visual mode, the
  table above) into Neovim 0.12 key by key and compared the grammar's decision for `:` `/` `?` and
  `q` with Neovim's mode after each prefix; all agreed. It waited for `nvim_get_mode` to give the
  same answer twice, since `nvim_get_mode` is answered before queued keys are processed.
- Two rules the test found:
  - **Counts are an exception to "blocking means argument"**: after `2` or `d3` Neovim reports
    `blocking` (it waits for more digits), yet `:` or `/` still opens a command line (`2:` is
    `:.,.+1`).
  - **`z` inside an operator is a prefix** (`dzj`, and `zfzf` spelled out).
- Left open: a `:` typed into a Neovim command line that `!{motion}` (`:.!`) or a mapping opened.

## Phase 2: let Neovim say when a command line opens

The UI is attached with `ext_cmdline` (`NeovimConnection.TERMINAL_UI`). Neovim then sends
`cmdline_show` (content, cursor, first character `:` `/` `?`, prompt, level) and `cmdline_hide` as
redraw events, which `util/CmdlineEvent` reads. They arrive with `ext_cmdline` alone, without
`ext_linegrid` (X2); `TERMINAL_UI` turns on `ext_linegrid`, `ext_messages` and `ext_popupmenu` as well
(docs/ARCHITECTURE.md, "Command line").

**The plug-in's own lines** are told apart by `util/CmdlineWatcher`. The plug-in still types some
command lines itself (`NeovimSession.commandAsync` types `:cmd<CR>`, so that macros and `@:` see
it), and Neovim reports those too (X5). Before typing keys that start with `:` `/` `?`, the session
calls `expectOwn`; each expectation is matched by the next top-level line of that type, in order,
and expires after 2 s (`OWN_LINE_TIMEOUT_MS`), so one that no line fulfils cannot hide a user's line
for long. Tab completion is Neovim's own, in its command line, so its reports need no filter (in the probe
Neovim reported only the line's end, X5).

How phase 2 used these events before phase 3: a line the plug-in did not open was taken over. A `:`
line was closed in Neovim at once (`<C-c>`) and the box opened with Neovim's text (a recording made
meanwhile holds `:`, Ctrl-C and the box's typed command, X6, and replays correctly); a search line
stayed open, and the box answered it with `<C-u>pattern<CR>`, which keeps a pending operator (X9).
Its known gap, keys typed between Neovim opening its line and the box taking it over, lost with the
`<C-c>`, is gone with phase 3, where every key goes into Neovim's line anyway.

**A search that finds nothing ended at a "Press ENTER" prompt** (mode `r`) with only `ext_cmdline`:
Neovim shows the pattern and E486 as two messages. Probe (X10): the same with `cmdheight` 0 or 2,
`nowrapscan`, `nohlsearch`; only `shortmess+=S` avoids it, and not `shortmess+=sS`, so an option is
no fix (users set their own). With `ext_messages` Neovim never waits at such a prompt
(docs/ARCHITECTURE.md, "Messages and output"), so nothing in the plug-in dismisses one any more.

Tests: `CmdlineEventTest`, `CmdlineWatcherTest` (unit), `CommandLineBoxTest` (SWT), and mappings
that open a command line typed into real Neovim in `CommandBoxEndToEndTest` (UI).

## Phase 3: Neovim's own command line

Every `:`, `/`, `?` goes to Neovim, and the box is a view of Neovim's command line
(`util/CmdlineState`), drawn from `cmdline_show`/`cmdline_pos`. History, `<C-r>` registers,
command-line mappings and completion are Neovim's own; the box's own history, completion and
search answering are gone, and so is phase 2's take-over: every line is shown. With that, nothing
on the UI thread needs to guess, so `util/KeyGrammar` was removed and `ZZ`/`ZQ` became Neovim
mappings (`core/IdeAction`). How the box edits text (keys, pastes, input methods) is in
docs/ARCHITECTURE.md, "Command line".

## Experiments

Neovim 0.12.4, Linux:

- **X1 Ground truth per key**: the table above.
- **X2 `ext_cmdline` without `ext_linegrid`**: `cmdline_show`/`cmdline_hide` arrive for `:`, `/`,
  mappings and misrouted keys.
- **X3 Half-typed mapping**: `nvim_get_mode` → `n` with `blocking` true.
- **X4 Requests while Neovim waits**: only `nvim_get_mode` is answered at once; `nvim_eval` waited
  until the command finished. So `v:operator` cannot be asked for mid-command.
- **X5** The plug-in's own `:silent cmd<CR>` produces `cmdline_show` (with its whole text) and
  `cmdline_hide`; Tab completion's Lua call only a `cmdline_hide` (aborted).
- **X6** `qa`, `:`, `<C-c>`, `:silent echo 2<CR>`, `q`: register `a` holds `:^C:silent echo 2^M`.
- **X7** `cmdline_show` arrives about 0.2 ms after `:`; keys sent right after `:` are in its content.
- **X8** (the guessing router in Eclipse with `nnoremap ; :`) was not tried; `CommandBoxEndToEndTest`
  types that mapping against the current design.
- **X9** While Neovim's command line is open (also a search line inside `d/`), `nvim_exec_lua` is
  answered at once; `<C-u>foo<CR>` into the open `d/` line deletes up to `foo`; while recording, the
  register holds `:^Usilent let ...^M` and `@:` is the command.
- **X10** With `ext_cmdline` and without `ext_messages`, a search that finds nothing ends at a
  hit-enter prompt; options that avoid it: phase 2.
