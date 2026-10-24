# Plan: routing `:`, `/`, `?` and `q` without guessing wrong

Status: **phases 1 and 2 built**; on `fix/key-routing` from `release/0.1.5` at `04d82d1`
(after the `g??` fix). The decisions are at the end. When a phase is done, change its heading
and add an *As built* section, as [TAB_COMPLETION.md](TAB_COMPLETION.md) does.

## Summary

`core/KeyRouter` decides, for each key typed in normal, visual or operator-pending mode, whether it
goes to Neovim or opens the editor's own command-line box. It must decide on the UI thread, before
Neovim has seen the key, so it guesses from the keys typed so far what Vim's command grammar says
the key is. The guesses are a short list of special cases (`f t r @ m ' \` " q` take an argument,
`g` and `g?` since the `g??` fix, `ZZ`/`ZQ`), and Neovim's own state where it is fresh.

Typing `gqq` showed the second `q` taken for "start recording". Probing a real Neovim (below) found
three wider kinds of error, all the same root cause: a guess that overrides what Neovim knows, or no
guess where one is needed. One of them, mappings in the user's `init.lua` that open the command line
(`nnoremap ; :`), no guessing in the router can fix, because the router does not know the mappings.

Plan: **phase 1** fixes the router's guesses and makes it defer to Neovim whenever Neovim has caught
up, with a test that compares the router with a real Neovim. **Phase 2** lets Neovim tell the
plug-in when a command line opens (`ext_cmdline`), so mappings and any remaining misroute open the
box too. A full native command line (phase 3) stays an idea.

## How it fails today

### Ground truth from Neovim

A probe (Python, msgpack-rpc to `nvim --embed --clean`, Neovim 0.12.4) typed sequences one key at a
time and asked `nvim_get_mode` after each. `!` means `blocking`: Neovim waits for one more key that
belongs to the command, so a `:` `/` `?` there is not a prompt. `c` means a command line opened.

| Typed | Neovim after each key | What the router does with the last key | Result |
|-------|-----------------------|----------------------------------------|--------|
| `gqq` then `:` | `g`→n! `q`→no `q`→n `:`→c | the 2nd `q` sets *REGISTER* (recording), so `:` goes to Neovim as its "register" | Neovim's own command line opens, invisible; `recordingByKeys` is wrongly true |
| `di"` then `:` (also `yi'`, ``di` ``, `vi"`) | `"`→n `:`→c | `"`, `'`, `` ` `` set *ARGUMENT* although after `i`/`a` they name a text object | same: invisible Neovim command line. `vi"` then `:` is common |
| `[/`, `]/`, `d[/` | `[`→n! `/`→n | `[`, `]` not tracked: `/` opens the search box unless Neovim already reported `[` | box opens for a motion; right only when typed slowly |
| `<C-w>q` then `:` | `q` closes a window | `q` after `<C-w>` sets *REGISTER* | as `gqq` |
| `g??` | `?`→no `?`→n | fixed in `04d82d1` | — |
| `"aq`, `@q`, `mq`, `dt"`, `ya(`, `guu`, `2d/x` | as expected | right | — |

### Mappings

A second probe attached a UI with `ext_cmdline` and defined `nnoremap ; :` and
`nnoremap <Space>s :%s/`. Typing `;` or `<Space>s` put Neovim in command-line mode (`c`), with
`%s/` already typed for the second. The router sends `;` and `s` to Neovim like any key, so the
box never opens: the user sees nothing while Neovim waits in its own command line. These are
common mappings in Vim users' configurations; nothing in the README mentions it. (Not yet tried in
a running Eclipse; the probe shows Neovim's side.)

While a mapping is half typed (`<Space>` above), `nvim_get_mode` reports `n!`, the same as after
`f`. So when no keys are in flight, Neovim's `blocking` flag is a reliable answer to "is the next
key a fresh command?", mappings included.

### Root cause

The router's own tracking (`Pending`) is applied first, and Neovim's state only when the router has
no opinion (`isNeovimWaitingForThisKey`). A wrong guess therefore wins even when Neovim has long
caught up. And no guess covers keys that mappings give a new meaning.

## Phase 1: fix the guesses, prefer Neovim's answer (built)

### As built

- `util/KeyGrammar` replaces `KeyRouter.Pending` and `NvimKeys.takesArgument` (removed). The router
  keeps one flag of its own, for the held-back `Z` of `ZZ`/`ZQ`.
- **Counts are an exception to "blocking means argument"**, found by the differential test: after
  `2` or `d3` Neovim reports `blocking` (it waits for more digits), yet `:` or `/` still opens a
  command line (`2:` is `:.,.+1`). The grammar keeps its own state while a count is being typed.
- **`z` inside an operator is a prefix** (`dzj`, and `zfzf` spelled out), also found by the test.
- The Ctrl-key commands, which send their key to Neovim without the router, now tell it which key
  went (`KeyRouter.sentElsewhere`), so `<C-v>` and `<C-w>` count for the next key.
- `KeyGrammarNeovimTest` types 335 sequences (every operator with every kind of continuation, and
  the rest of the grammar) into Neovim 0.12; all agree. It waits for `nvim_get_mode` to give the same
  answer twice, since it is answered before queued keys are processed.
- Not done: a `:` typed into Neovim's command line opened by `!{motion}` (`:.!`) or a mapping is
  still the user's problem until phase 2.

### Plan

1. **Neovim first when it is current.** When no keys are in flight, decide prompt keys from Neovim's
   `(mode, blocking)`: `blocking` → the key goes to Neovim; otherwise `:` `/` `?` open the box,
   except `?` while the operator is `g?` (tracked locally, as now: `v:operator` cannot be read
   while Neovim waits, see *Experiments*). The local `Pending` state is used only while keys are
   in flight. This alone fixes every row above when typed at normal speed.
2. **Better local guesses for fast typing**, in a small pure class (`util/KeyGrammar`, unit-tested)
   that replaces `Pending` and `NvimKeys.takesArgument`:
   - after an operator (`d c y < > = ! g~ gu gU g? gq gw zf g@`), `i`/`a` select a text object,
     so the next key (`"`, `'`, `` ` ``, `(`, `w` ...) is its name and takes no argument;
   - a doubled operator (`dd`, `gqq`, `g??`, `gUU`) ends the command: its last key is neither
     an argument command nor `q`-starts-recording;
   - prefixes whose next key always belongs to them: `g`, `z`, `[`, `]`, `<C-w>`, `<C-\>`;
   - unknown keys: no guess, the key goes to Neovim, and the next prompt key is decided by step 1
     once Neovim catches up (milliseconds), or opens the box if keys are still in flight.
3. **A differential test** in `tests/unit` (integration profile, real Neovim): a corpus of sequences
   (the table above, all operators x text objects x argument commands, counts, registers, visual
   mode), each typed into Neovim key by key; after each prefix it checks that the router's decision
   for `:` `/` `?` and `q` matches Neovim's mode. New cases become one line in the corpus. This is
   the guard that the grammar matches Vim's, and it will find cases this note missed.
4. Docs: KEYS.md and ARCHITECTURE.md ("Arguments vs prompts"), CHANGELOG *Fixed*, and a Known
   limitations entry for mappings until phase 2.

Size: one class replaced by a tested one, two to three commits. No behavior change for keys that work
today.

## Phase 2: let Neovim say when a command line opens (built)

### As built

- `NeovimConnection.TERMINAL_UI` sets `ext_cmdline` only. `util/CmdlineEvent` reads `cmdline_show`
  and `cmdline_hide` out of `redraw`; `util/CmdlineWatcher` decides which lines are the user's.
- **The plug-in's own lines** are announced by `CommandRunner`'s typing (`NeovimSession`'s
  `CommandRunnerNeovim.type` calls `expectOwn` for keys starting with `:` `/` `?`); each expectation
  is matched by the next line of that type, in order, and expires after 2 s. Tab completion needs no
  filter by type: in the probe Neovim reported only its line's end (X5), but in the running plug-in
  it may report the line too. So the controller takes over no line while the box is open: then the
  line is the box's own doing (found by the existing Tab completion UI test).
- **`:` lines** are closed in Neovim at once (`<C-c>`) and the box opens with Neovim's text, then
  works as before (Tab completion needs Neovim in normal mode). A recording made meanwhile holds
  `:`, Ctrl-C and the box's typed command (X6): it replays correctly.
- **Search lines** stay open; the box answers them with `<C-u>pattern<CR>`
  (`CommandRunner.answerSearch`, `SearchCommand.body`), which keeps a pending operator (X9), or closes
  them with `<C-c>` on Esc, a click away or an empty line.
- **A search that finds nothing now ends at a "Press ENTER" prompt** (mode `r`), found by two
  integration tests: with the command line external, Neovim shows the pattern and E486 as two
  messages. Probe (X10): the same with `cmdheight` 0 or 2, `nowrapscan`, `nohlsearch`; only
  `shortmess+=S` avoids it, and not `shortmess+=sS`, so an option is no fix (users set their own).
  Instead the prompt, which Eclipse cannot show, is dismissed: `CommandRunner` after its typed
  search or answer (as it already did after `:` commands), and `KeyInput` after any key that leaves
  Neovim at a plain hit-enter prompt (`r`, not `r?` or `rm`), such as a mapping `/TODO<CR>`. The
  message still reaches the message line through `v:errmsg`.
- Tests: `CmdlineEventTest`, `CmdlineWatcherTest` (unit), five `CommandLineBoxTest` cases (SWT), and
  four mappings typed into real Neovim in `CommandBoxEndToEndTest` (UI).
- Known gap: keys typed between Neovim opening its line and the box taking it over (a millisecond or
  so) go into Neovim's line; for a `:` line they are lost with the `<C-c>` unless the
  `cmdline_show` that the box read already had them.

### Plan

Attach the UI with `ext_cmdline: true`. Neovim then sends `cmdline_show` (content, cursor, first
character `:` `/` `?`, prompt, level) and `cmdline_hide` as redraw events (probe: they arrive with
`ext_cmdline` alone, without `ext_linegrid`, so the "terminal-like UI" otherwise stays as it is).

When Neovim shows a command line the plug-in did not open, the plug-in takes it over: it opens the
box prefilled with Neovim's content (`%s/` from the mapping), and either cancels Neovim's line
(`<C-c>`) for `:`, or, for a search started inside an operator (`d/`), keeps it open and on Enter
sends the box's text into it (`<C-u>pattern<CR>`), so the operator is not lost. With this net,
every misroute toward Neovim and every mapping that opens a command line ends in the box.

Questions to settle by experiment first (see below): the plug-in's own typed commands
(`:silent cmd<CR>`, Tab completion's Lua round trip) also produce `cmdline_show` events, which must
be told apart from the user's; what `<C-c>` leaves in a macro being recorded; and whether a prompt
key could then simply always go to Neovim, with the box opened only on `cmdline_show` (one round
trip later), which would remove the router's prompt guessing altogether.

## Phase 3 (idea, not planned): Neovim's own command line

The box becomes a view of Neovim's command line: every key goes to Neovim, the box draws
`cmdline_show`/`cmdline_pos`. History, `<C-r>` registers, command-line mappings and completion
would all be Neovim's own. It replaces the output capture (`core/CommandRunner`) and Tab completion
designs and changes how the box edits text (IME, clipboard), so it is a project of its own; already
listed in ARCHITECTURE.md, *Ideas for future work*.

## Experiments

Done (2026-10-24, Neovim 0.12.4, Linux):

- **X1 Ground truth per key**: the table above.
- **X2 `ext_cmdline` without `ext_linegrid`**: `cmdline_show`/`cmdline_hide` arrive for `:`, `/`,
  mappings and misrouted keys.
- **X3 Half-typed mapping**: `nvim_get_mode` → `n` with `blocking` true.
- **X4 Requests while Neovim waits**: only `nvim_get_mode` is answered at once; `nvim_eval` waited
  until the command finished. So the router cannot ask for `v:operator` mid-command.

Done before phase 2 (the same day):

- **X5** The plug-in's own `:silent cmd<CR>` produces `cmdline_show` (with its whole text) and
  `cmdline_hide`; Tab completion's Lua call only a `cmdline_hide` (aborted).
- **X6** `qa`, `:`, `<C-c>`, `:silent echo 2<CR>`, `q`: register `a` holds `:^C:silent echo 2^M`.
- **X7** `cmdline_show` arrives about 0.2 ms after `:`; keys sent right after `:` are in its content.
- **X9** While Neovim's command line is open (also a search line inside `d/`), `nvim_exec_lua` is
  answered at once; `<C-u>foo<CR>` into the open `d/` line deletes up to `foo`; while recording, the
  register holds `:^Usilent let ...^M` and `@:` is the command.
- **X8** (the old behavior in Eclipse with `nnoremap ; :`) was not tried: phase 2 replaced it the
  same day, and its end-to-end test types that mapping.

## Decisions

1. Phase 1 as described, including the differential test against a real Neovim *(decided,
   2026-10-24)*.
2. Phase 2 right after phase 1 *(decided)*.
3. No Known limitations entry for mappings that open the command line, since phase 2 follows
   directly (the question was left open; revisit if phase 2 is delayed).
