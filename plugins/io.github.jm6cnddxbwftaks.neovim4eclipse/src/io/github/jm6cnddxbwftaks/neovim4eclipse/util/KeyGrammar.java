// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.Set;

/**
 * Follows Vim's normal-mode command grammar key by key, to tell what the <em>next</em> key will be
 * to Neovim: the start of a command, the argument of one, a motion, or text being inserted. The
 * key router ({@code core/KeyRouter}) asks it while Neovim has not yet processed the keys typed so
 * far, so Neovim's own state ({@code nvim_get_mode}) describes an earlier key. Once Neovim has
 * caught up, {@link #syncTo} replaces the guess with Neovim's answer.
 *
 * <h2>Why the router needs it</h2>
 * The router decides on the UI thread, before Neovim sees the key, whether {@code :}, {@code /},
 * {@code ?} open the editor's command-line box or go to Neovim, and whether a {@code q} starts a
 * recording. Examples, with what this class says the next key is ({@link Expect}):
 *
 * <pre>
 *   typed      next key is   so a following : / ? ...
 *   d          MOTION        opens the box (d/foo is a search motion)
 *   f          ARGUMENT      goes to Neovim (f: finds a colon)
 *   di         ARGUMENT      goes to Neovim (the text object's name)
 *   di"        COMMAND       opens the box (the text object was di", the quote took no argument)
 *   gqq        COMMAND       opens the box (gqq doubled the operator; that q starts no recording)
 *   g?         MOTION        ? goes to Neovim: g?? encodes the line (see questionDoublesOperator)
 *   [          ARGUMENT      goes to Neovim ([/ is a motion)
 *   o          TEXT          is typed into the new line
 * </pre>
 *
 * <p>The grammar, simplified to what those decisions need: {@code [count]["x][count]command},
 * where a command is a plain key, an operator ({@code d c y < > = ! g~ gu gU g? gq gw g@ zf})
 * followed by a motion, a text object ({@code i}/{@code a} + a key) or the operator doubled
 * ({@code dd}, {@code gqq}, {@code g??}), a command taking one more key ({@code f t F T r m ' `
 * @ q}), or a prefix whose next key belongs to it ({@code g z Z [ ] <C-w> <C-\>}). Visual mode
 * differs: operators apply at once, {@code i}/{@code a} select text objects, {@code o} moves the
 * other end. Keys it does not know count as complete commands: the router then trusts Neovim's
 * answer as soon as Neovim has caught up, milliseconds later.
 *
 * <p>Ground truth: every rule here was compared with Neovim 0.12 typing the keys one at a time
 * (docs/design/KEY_ROUTING.md), and {@code KeyGrammarNeovimTest} keeps doing so for a list of
 * sequences.
 *
 * <p>Not thread-safe: the router uses it from the UI thread only.
 */
public final class KeyGrammar {

    /** What the next key is to Neovim. */
    public enum Expect {
        /** The start of a new command (in normal or visual mode). */
        COMMAND,
        /** A motion, text object or the doubled operator, for an operator typed before. */
        MOTION,
        /** One more key that belongs to the command typed before: never a prompt or a new command. */
        ARGUMENT,
        /** Text: an insert or replace has begun. */
        TEXT
    }

    private enum State {
        COMMAND,
        REGISTER_NAME,
        ARGUMENT,
        RECORDING_REGISTER,
        OPERATOR,
        OPERATOR_G,
        TEXT_OBJECT,
        PREFIX_G,
        PREFIX_Z,
        /** {@code [ ] Z <C-w> <C-\>}: the next key completes the command, whatever it is. */
        PREFIX_ANY,
        TEXT
    }

    /** Operators that are one key in normal mode. {@code <lt>} is how {@code nvim_input} spells {@code <}. */
    private static final Set<String> OPERATORS = Set.of("d", "c", "y", "<lt>", "<", ">", "=", "!");
    /** Keys after {@code g} that make an operator in normal mode: {@code g? gq gw gu gU g~ g@}. */
    private static final String G_OPERATORS = "?qwuU~@";
    /** Normal-mode keys that start an insert or replace. */
    private static final Set<String> INSERT_KEYS = Set.of("i", "a", "I", "A", "o", "O", "s", "S", "C", "R");
    /** Keys that take one more key: find, till, replace, mark, jump to mark, play a macro. */
    private static final Set<String> ARGUMENT_KEYS = Set.of("f", "F", "t", "T", "r", "m", "'", "`", "@");
    /** Keys that end an insert (and leave visual mode, or cancel a pending command). */
    private static final Set<String> ESCAPE_KEYS = Set.of("<Esc>", "<C-c>", "<C-[>");
    /** Visual-mode keys that act on the selection and return to normal mode. */
    private static final Set<String> VISUAL_ACTIONS = Set.of("d", "x", "X", "D", "y", "Y", "J", "~", "u", "U",
            "<lt>", "<", ">", "=", "!", "p", "P");
    /** Visual-mode keys that replace the selection with typed text. */
    private static final Set<String> VISUAL_INSERTS = Set.of("c", "s", "C", "S", "R", "I", "A");
    /** Keys that start a visual mode. */
    private static final Set<String> VISUAL_KEYS = Set.of("v", "V", "<C-v>");

    private State state = State.COMMAND;
    /** The pending operator ({@code "d"}, {@code "g?"}), or {@code ""} if Neovim reported one this class missed. */
    private String operator;
    private boolean visual;
    /** The key that started the visual mode ({@code v V <C-v>}); the same key ends it. */
    private String visualKind;
    /** In visual mode, the pending argument ({@code r}) ends the visual mode once given. */
    private boolean argumentEndsVisual;
    /** The previous key was a digit of a count, so a {@code 0} continues it ({@code 10j}) instead of being a motion. */
    private boolean counting;

    /** What the next key is to Neovim, as far as the keys fed here tell. */
    public Expect expect() {
        return switch (state) {
            case COMMAND -> Expect.COMMAND;
            case OPERATOR -> Expect.MOTION;
            case TEXT -> Expect.TEXT;
            default -> Expect.ARGUMENT;
        };
    }

    /** Whether the next key names the register a recording goes into ({@code q} was typed). */
    public boolean expectsRecordingRegister() {
        return state == State.RECORDING_REGISTER;
    }

    /**
     * Whether a {@code ?} now doubles the pending operator instead of starting a search motion:
     * after {@code g?}, {@code g??} encodes the line, as {@code dd} deletes one.
     */
    public boolean questionDoublesOperator() {
        return state == State.OPERATOR && "g?".equals(operator);
    }

    /** Whether a visual mode is active, as far as the keys tell. */
    public boolean isVisual() {
        return visual;
    }

    /** Forgets everything: a new command starts, in normal mode. */
    public void reset() {
        state = State.COMMAND;
        operator = null;
        visual = false;
        visualKind = null;
        argumentEndsVisual = false;
        counting = false;
    }

    /**
     * Takes Neovim's answer about the keys it has processed ({@code nvim_get_mode}), which is right
     * where this class's guess may not be. Call it only when no keys are on their way to Neovim.
     * What Neovim does not report (which operator, which command waits) is kept from the keys.
     *
     * @param mode     Neovim's mode string ({@code "n"}, {@code "no"}, {@code "v"}, {@code "i"} ...)
     * @param blocking whether Neovim waits for one more key of the current command (after {@code f},
     *                 {@code g}, a half-typed mapping ...)
     */
    public void syncTo(String mode, boolean blocking) {
        if (mode == null) {
            return;
        }
        if (VimModes.isTyping(mode)) {
            reset();
            state = State.TEXT;
            return;
        }
        boolean operatorPending = VimModes.isOperatorPending(mode);
        visual = VimModes.isVisual(mode);
        visualKind = !visual ? null : mode.startsWith("v") ? "v" : mode.startsWith("V") ? "V" : "<C-v>";
        if (blocking && counting) {
            // 2, d3: Neovim reports blocking after a count too, but waits for the rest of the
            // command, which may still be : / ? (2: opens :.,.+1). Keep the command state.
            if (operatorPending && state != State.OPERATOR) {
                startOperator("");
            }
            return;
        }
        counting = false;
        if (blocking) {
            if (expect() != Expect.ARGUMENT) {
                state = State.ARGUMENT; // something we did not follow (a mapping prefix ...) waits for a key
            }
            if (operatorPending && operator == null) {
                operator = "";
            }
        } else if (operatorPending) {
            if (state != State.OPERATOR) {
                startOperator(operator == null ? "" : operator);
            }
        } else if (mode.startsWith("n") || visual) {
            state = State.COMMAND;
            operator = null;
            argumentEndsVisual = false;
        }
        // Other modes (command line, hit-enter prompt ...) leave the guess as it is.
    }

    /** Whether the last key was a digit of a count ({@code 2}, {@code d3}): the command is still to come. */
    public boolean afterCount() {
        return counting;
    }

    /**
     * The editor's own prompt opened for {@code :}, {@code /} or {@code ?}. Its command or search
     * runs by another route and ends any pending operator; until it does, Neovim's answer
     * ({@link #syncTo}) keeps the operator if one is still pending.
     */
    public void promptOpened() {
        state = State.COMMAND;
        operator = null;
        visual = false;
        visualKind = null;
    }

    /**
     * Follows one key sent to Neovim.
     *
     * @param key       the key in Neovim notation ({@code "d"}, {@code "<lt>"}, {@code "<C-w>"})
     * @param recording whether a macro is being recorded: then {@code q} stops it and takes no key
     */
    public void feed(String key, boolean recording) {
        boolean wasCounting = counting;
        counting = isCount(key, wasCounting) && (state == State.COMMAND || state == State.OPERATOR);
        if (counting) {
            return; // 3dd, d3j, 10j: a count changes nothing about what comes next
        }
        switch (state) {
            case TEXT -> {
                if (ESCAPE_KEYS.contains(key)) {
                    reset();
                }
            }
            case REGISTER_NAME -> state = State.COMMAND;
            case RECORDING_REGISTER -> state = State.COMMAND;
            case ARGUMENT, TEXT_OBJECT, PREFIX_ANY -> completeCommand();
            case OPERATOR -> operatorKey(key);
            case OPERATOR_G -> {
                if ("'".equals(key) || "`".equals(key)) {
                    state = State.ARGUMENT; // dg'a: g' takes a mark
                } else {
                    completeCommand(); // dgg, or g?g? (the doubled operator spelled out)
                }
            }
            case PREFIX_G -> gKey(key);
            case PREFIX_Z -> {
                if ("f".equals(key) && !visual) {
                    startOperator("zf");
                } else {
                    completeCommand();
                }
            }
            case COMMAND -> commandKey(key, recording);
        }
    }

    private void commandKey(String key, boolean recording) {
        if (ESCAPE_KEYS.contains(key)) {
            reset();
            return;
        }
        switch (key) {
            case "\"" -> state = State.REGISTER_NAME;
            case "q" -> state = recording ? State.COMMAND : State.RECORDING_REGISTER;
            case "g" -> state = State.PREFIX_G;
            case "z" -> state = State.PREFIX_Z;
            case "[", "]", "Z", "<C-w>", "<C-\\>" -> state = State.PREFIX_ANY;
            default -> {
                if (ARGUMENT_KEYS.contains(key)) {
                    state = State.ARGUMENT;
                    argumentEndsVisual = visual && "r".equals(key);
                } else if (visual) {
                    visualCommand(key);
                } else {
                    normalCommand(key);
                }
            }
        }
    }

    private void normalCommand(String key) {
        if (OPERATORS.contains(key)) {
            startOperator(key);
        } else if (VISUAL_KEYS.contains(key)) {
            visual = true;
            visualKind = key;
        } else if (INSERT_KEYS.contains(key)) {
            state = State.TEXT;
        }
        // Anything else (a motion, x, p, u, a key this class does not know) is a complete command.
    }

    private void visualCommand(String key) {
        if ("i".equals(key) || "a".equals(key)) {
            state = State.TEXT_OBJECT; // vi" extends the selection; the visual mode stays
        } else if (VISUAL_KEYS.contains(key)) {
            if (key.equals(visualKind)) {
                visual = false; // v in charwise visual ends it
                visualKind = null;
            } else {
                visualKind = key; // V in charwise visual switches to linewise
            }
        } else if (VISUAL_INSERTS.contains(key)) {
            visual = false;
            visualKind = null;
            state = State.TEXT;
        } else if (VISUAL_ACTIONS.contains(key)) {
            visual = false;
            visualKind = null;
        }
        // o, O, motions and others keep the visual mode.
    }

    private void gKey(String key) {
        if (visual) {
            if (key.length() == 1 && G_OPERATORS.indexOf(key.charAt(0)) >= 0 || "J".equals(key)) {
                visual = false; // g? gu gq ... act on the selection at once
                visualKind = null;
            }
            state = State.COMMAND;
        } else if (key.length() == 1 && G_OPERATORS.indexOf(key.charAt(0)) >= 0) {
            startOperator("g" + key);
        } else if ("'".equals(key) || "`".equals(key) || "r".equals(key)) {
            state = State.ARGUMENT; // g'a, g`a, gra
        } else if ("i".equals(key) || "I".equals(key)) {
            state = State.TEXT; // gi, gI
        } else if ("v".equals(key)) {
            visual = true; // gv: the previous selection, kind unknown
            visualKind = null;
            state = State.COMMAND;
        } else {
            state = State.COMMAND; // gg, gj, gJ ...
        }
    }

    private void startOperator(String op) {
        state = State.OPERATOR;
        operator = op;
    }

    private void operatorKey(String key) {
        if (VISUAL_KEYS.contains(key)) {
            return; // dvj forces the motion charwise
        }
        if (doubles(key)) {
            completeCommand(); // dd, cc, gqq, g??, g~~, >>
        } else if ("g".equals(key)) {
            state = State.OPERATOR_G;
        } else if ("i".equals(key) || "a".equals(key)) {
            state = State.TEXT_OBJECT;
        } else if ("f".equals(key) || "F".equals(key) || "t".equals(key) || "T".equals(key) || "'".equals(key)
                || "`".equals(key)) {
            state = State.ARGUMENT;
        } else if ("[".equals(key) || "]".equals(key) || "z".equals(key)) {
            state = State.PREFIX_ANY; // d[/ dzj (z: fold motions zj zk, and zfzf spelled out)
        } else if (ESCAPE_KEYS.contains(key)) {
            reset();
        } else {
            completeCommand(); // a motion (dw, d$), or a key that cancels the operator (dZ)
        }
    }

    /**
     * Whether {@code key} repeats the pending operator, which applies it to whole lines: the key
     * itself for one-key operators ({@code dd}, {@code <lt><lt>}), the second key for the {@code g}
     * operators ({@code gqq}, {@code g??}). {@code zf} has no such form: in {@code zff} the second
     * {@code f} is the find motion, waiting for its character.
     */
    private boolean doubles(String key) {
        if ("<".equals(operator) || "<lt>".equals(operator)) {
            return "<lt>".equals(key) || "<".equals(key);
        }
        if (operator.length() == 1) {
            return key.equals(operator);
        }
        return operator.startsWith("g") && key.equals(operator.substring(1));
    }

    /** The command typed so far is complete: an operator applies, an insert may begin. */
    private void completeCommand() {
        boolean insert = "c".equals(operator);
        operator = null;
        if (argumentEndsVisual) {
            visual = false;
            visualKind = null;
            argumentEndsVisual = false;
        }
        state = insert ? State.TEXT : State.COMMAND;
    }

    /** {@code 1}-{@code 9} start a count; {@code 0} continues one but alone is a motion. */
    private static boolean isCount(String key, boolean counting) {
        if (key.length() != 1) {
            return false;
        }
        char c = key.charAt(0);
        return c >= '1' && c <= '9' || c == '0' && counting;
    }
}
