# Design note: one switch for the plug-in's log level

Status: **implemented** (2026-10-20). Written 2026-10-20 against `develop` at `0eaf5ae`. Decisions made
with the maintainer are marked *(decided)*. Where the code differs from this plan, "As built" at the
end says how.

## Summary

Today, seeing the plug-in's details (level FINE, for example "Ctrl-V … resolves to the Neovim
Ctrl-key command") takes a JUL properties file passed with `-Djava.util.logging.config.file=…`,
explained in the README's "If `Ctrl-V` does nothing" section. That is three steps, and on Windows two
traps on top (`eclipse.exe` has no console; `-vmargs` on the command line replaces `eclipse.ini`'s).

**Plan:** one JVM system property, `-Declipseneovim.logLevel=FINE` *(decided)*, read when the
plug-in starts. It sets the level of all the plug-in's loggers and prints them to stderr, one line
each. Files come from shell redirection, not from the plug-in *(decided)*. It is the only way to do
it: no environment variable, no preference *(decided)*, and the README drops the properties-file
method *(decided)*.

Size: about 80 lines of code and 120 of tests, in one commit.

## 1. Behavior

- **Where to put it:**
  - a line `-Declipseneovim.logLevel=FINE` after `-vmargs` in `eclipse.ini`;
  - in a runtime Eclipse launched from the IDE, the launch configuration's *Arguments* tab →
    *VM arguments*, with the output in the Console view of the launching Eclipse;
  - on the command line, `eclipsec.exe --launcher.appendVmargs -vmargs -Declipseneovim.logLevel=FINE`
    (section 3).
- **Values:** JUL level names, in any case: `OFF`, `SEVERE`, `WARNING`, `INFO`, `CONFIG`, `FINE`,
  `FINER`, `FINEST`, `ALL`. An unknown value prints one warning naming the valid ones; the setting
  is then ignored.
- **Output:** stderr, one line per record: time, level, class (simple name), message, and the
  stack trace if there is one. For example:
  `06:50:12.345 FINE KeyContextDiagnostics: Ctrl-V (M1+V) resolves to the Neovim Ctrl-key command`
- **Files:** shell redirection, for example `eclipsec.exe … 2> neovim.log`, or `2>&1 | tee neovim.log`
  on Linux and macOS. On Windows this needs `eclipsec.exe`, since `eclipse.exe` has no console.
- **The Error Log is unaffected:** WARNING and SEVERE still reach it, even with `OFF` (section 2).
- **Without the switch:** exactly as today, with INFO and above on the console through the JDK's root
  handler, and WARNING and above in the Error Log.

## 2. How

All the plug-in's loggers are named after their classes, under one parent:
`io.github.jm6cnddxbwftaks.eclipseneovim` (`Activator.PLUGIN_ID`). Setting that parent covers the
whole plug-in, `core/` and `util/` included.

1. **`util/LogLevelSwitch`** (JUL only, no Eclipse, so unit-tested in `tests/unit`):
   - `parse(String)`: the level, or empty for a blank value; an unknown value gives an error text
     listing the valid names.
   - `apply(Logger parent, Level level, PrintStream out)`:
     - sets the parent's level to the lower of `level` and WARNING, since a logger's own level
       gates all of its handlers, and the Error Log bridge (`ErrorLogHandler`, on the same logger)
       must keep getting warnings;
     - adds a handler at `level` that writes to `out` with a one-line formatter;
     - calls `setUseParentHandlers(false)`, so the plug-in's INFO lines no longer also go to the
       JDK's root console handler and print twice;
     - keeps a strong reference to the logger (JUL holds loggers weakly; `ErrorLogHandler` does
       the same).
2. **`Activator.start`**, first thing, before anything logs: read
   `System.getProperty("eclipseneovim.logLevel")`; if set, `LogLevelSwitch.apply(…, System.err)`;
   on an unknown value, one WARNING (it reaches the Error Log too). In `stop`, remove the handler,
   as `ErrorLogHandler.uninstall` does.
3. The property name in one constant (in `LogLevelSwitch`), so the README, the tests and the code
   agree.

**Checked:** `--launcher.appendVmargs` works on the command line too, not only in `eclipse.ini`. Tried
with `eclipsec.exe` (Eclipse 4.41, Windows) and a copy of `eclipse.ini` without that line, passed
with `--launcher.ini`, plus `-vmargs -XX:+PrintCommandLineFlags`: with `--launcher.appendVmargs` on the
command line, the ini's `-XX:+UseStringDeduplication` was still in effect; without it, it was gone.
The EPP packages' own `eclipse.ini` already has `--launcher.appendVmargs`, so for most people the
trap does not arise; the README still shows the flag, since it costs nothing.

## 3. Why no environment variable *(decided)*

The one advantage of an environment variable would be avoiding the `-vmargs` trap: on the command
line, `-vmargs` replaces all of `eclipse.ini`'s VM arguments, memory settings included. Both
alternatives avoid that without a second mechanism: the line in `eclipse.ini` (persistent), or
`--launcher.appendVmargs` on the command line (one run). A runtime Eclipse launched from the IDE
takes VM arguments in its launch configuration anyway. So "one way to do it" holds.

## 4. Why no preference *(decided)*

A level drop-down on the Neovim preference page would be about 60 lines: a field editor, the
Activator's preference listener reapplying the level, a test. It was left out because:
- it is a second way to do the same thing;
- it is saved in the workspace, so a forgotten `FINE` floods the console in every later session (a
  setting that lasts one session only needs custom UI, not a field editor);
- it helps only when Eclipse has a console, and anyone with a console can already pass `-D`.

## 5. Tests (`tests/unit`)

- `parse`: each level name; lower and mixed case; blank; an unknown name gives the error text.
- `apply` with a `PrintStream` on a byte array:
  - at `FINE`, a FINE record from a child logger (`…eclipseneovim.editor.X`) reaches the stream,
    on one line;
  - an INFO record is printed once, not again through the root handler;
  - at `OFF`, a WARNING still reaches a second handler on the parent logger (the stand-in for
    the Error Log bridge);
  - the handler is removed again by the cleanup method.
- Restore the JUL state after each test (level, handlers, `useParentHandlers`), as the loggers are
  global.

## 6. Docs and commit

- **README:** the "If `Ctrl-V` does nothing" section shows only the switch (the three places from
  section 1, and the redirection example), replacing the properties file, the `FileHandler` block
  and the Windows paragraph. The table of log lines with their levels stays.
- **`docs/HOWTO_ECLIPSE.md`:** the launch-configuration line.
- **`CHANGELOG.md`:** an *Added* entry under *Unreleased*.
- **This note:** status set to done.
- **One commit**, in UTC (`TZ=UTC git commit`).

## As built

- `LogLevelSwitch.parse` throws an `IllegalArgumentException` for an unknown value (its message is
  the text the plug-in logs) instead of returning an error text. Only the nine level names are
  accepted: plain `Level.parse` would also take numbers such as `500`.
- `LogLevelSwitch` is an object that remembers what it changed: `remove()` takes the handler away
  and puts back the parent logger's level and `useParentHandlers`, so the tests and a restarted
  plug-in start from the JDK's defaults.
- `Activator.start` applies the switch right after installing the Error Log bridge, not before it,
  so the warning about an unknown value reaches the Error Log too. Nothing logs in between.
- Tests: `tests/unit/.../util/LogLevelSwitchTest` (8 tests). The `Activator` glue (reading the
  property) has no test of its own; it is four lines.
