# Design note: one switch for the plug-in's log level

The plug-in's details (level FINE, for example "Ctrl-V … resolves to the Neovim Ctrl-key command")
are turned on with one JVM system property, `-Dneovim4eclipse.logLevel=FINE`, read when the plug-in
starts. It sets the level of all the plug-in's loggers and prints their records to stderr, one line
each. It is the only way to do it: there is no environment variable, no preference and no JUL
properties file (sections 3 and 4 say why).

The alternative it replaced, a JUL properties file passed with `-Djava.util.logging.config.file=…`,
took three steps, and on Windows two traps on top (`eclipse.exe` has no console; `-vmargs` on the
command line replaces `eclipse.ini`'s).

## 1. Behavior

- **Where to put it:**
  - a line `-Dneovim4eclipse.logLevel=FINE` after `-vmargs` in `eclipse.ini`;
  - in a runtime Eclipse launched from the IDE, the launch configuration's *Arguments* tab →
    *VM arguments*, with the output in the Console view of the launching Eclipse;
  - on the command line, `eclipsec.exe --launcher.appendVmargs -vmargs -Dneovim4eclipse.logLevel=FINE`
    (section 3).
- **Values:** JUL level names, in any case: `OFF`, `SEVERE`, `WARNING`, `INFO`, `CONFIG`, `FINE`,
  `FINER`, `FINEST`, `ALL`. Numbers such as `500`, which plain `Level.parse` would take, are refused.
  An unknown value logs one warning naming the valid ones (it reaches the Error Log too); the
  setting is then ignored.
- **Output:** stderr, one line per record: time, level, class (simple name), message, and the
  stack trace if there is one. For example:
  `06:50:12.345 FINE KeyContextDiagnostics: Ctrl-V (M1+V) resolves to the Neovim Ctrl-key command`
- **Files:** shell redirection, not the plug-in: for example `eclipsec.exe … 2> neovim.log`, or
  `2>&1 | tee neovim.log` on Linux and macOS. On Windows this needs `eclipsec.exe`, since
  `eclipse.exe` has no console.
- **The Error Log is unaffected:** WARNING and SEVERE still reach it, even with `OFF` (section 2).
- **Without the switch:** INFO and above go to the console through the JDK's root handler, and
  WARNING and above to the Error Log.

## 2. How

All the plug-in's loggers are named after their classes, under one parent:
`io.github.jm6cnddxbwftaks.neovim4eclipse` (`Activator.PLUGIN_ID`). Setting that parent covers the
whole plug-in, `core/` and `util/` included.

1. **`util/LogLevelSwitch`** (JUL only, no Eclipse, so unit-tested in `tests/unit`):
   - `parse(String)`: the level, or empty for a blank value; an unknown value throws an
     `IllegalArgumentException` whose message is the text the plug-in logs.
   - `apply(Logger parent, Level level, PrintStream out)`:
     - sets the parent's level to the lower of `level` and WARNING, since a logger's own level
       gates all of its handlers, and the Error Log bridge (`ErrorLogHandler`, on the same logger)
       must keep getting warnings;
     - adds a handler at `level` that writes to `out` with a one-line formatter;
     - calls `setUseParentHandlers(false)`, so the plug-in's INFO lines no longer also go to the
       JDK's root console handler and print twice;
     - keeps a strong reference to the logger (JUL holds loggers weakly; `ErrorLogHandler` does
       the same).
   - The switch is an object that remembers what it changed: `remove()` takes the handler away and
     puts back the parent logger's level and `useParentHandlers`, so the tests and a restarted
     plug-in start from the JDK's defaults.
2. **`Activator.start`** reads `System.getProperty("neovim4eclipse.logLevel")` right after
   installing the Error Log bridge (so the warning about an unknown value reaches the Error Log;
   nothing logs in between) and, if set, calls `LogLevelSwitch.apply(…, System.err)`. `stop`
   removes the handler again, as `ErrorLogHandler.uninstall` does.
3. The property name lives in one constant (in `LogLevelSwitch`), so the user guide, the tests and the
   code agree.

**`--launcher.appendVmargs` on the command line**, not only in `eclipse.ini`, keeps the ini's VM
arguments. Tried with `eclipsec.exe` (Eclipse 4.41, Windows) and a copy of `eclipse.ini` without
that line, passed with `--launcher.ini`, plus `-vmargs -XX:+PrintCommandLineFlags`: with the flag,
the ini's `-XX:+UseStringDeduplication` was still in effect; without it, it was gone. The EPP
packages' own `eclipse.ini` already has `--launcher.appendVmargs`, so for most people the trap does
not arise; the user guide still shows the flag, since it costs nothing.

## 3. Why no environment variable

Its one advantage would be avoiding the `-vmargs` trap: on the command line, `-vmargs` replaces all
of `eclipse.ini`'s VM arguments, memory settings included. Both other places avoid that without a
second mechanism: the line in `eclipse.ini` (persistent), or `--launcher.appendVmargs` on the
command line (one run). A runtime Eclipse launched from the IDE takes VM arguments in its launch
configuration anyway. So "one way to do it" holds.

## 4. Why no preference

A level drop-down on the Neovim preference page would be about 60 lines: a field editor, the
Activator's preference listener reapplying the level, a test. It is left out because:
- it is a second way to do the same thing;
- it is saved in the workspace, so a forgotten `FINE` floods the console in every later session (a
  setting that lasts one session only needs custom UI, not a field editor);
- it helps only when Eclipse has a console, and anyone with a console can already pass `-D`.

## 5. Tests

`tests/unit/.../util/LogLevelSwitchTest`:

- `parse`: each level name; lower and mixed case; blank; an unknown name or a number is refused
  with the message.
- `apply` with a `PrintStream` on a byte array:
  - at `FINE`, a FINE record from a child logger (`…neovim4eclipse.editor.X`) reaches the stream,
    on one line;
  - an INFO record is printed once, not again through the root handler;
  - at `OFF`, a WARNING still reaches a second handler on the parent logger (the stand-in for
    the Error Log bridge);
  - `remove()` puts everything back.
- Each test restores the JUL state (level, handlers, `useParentHandlers`), as the loggers are
  global.

The `Activator` glue (reading the property) has no test of its own; it is four lines.
