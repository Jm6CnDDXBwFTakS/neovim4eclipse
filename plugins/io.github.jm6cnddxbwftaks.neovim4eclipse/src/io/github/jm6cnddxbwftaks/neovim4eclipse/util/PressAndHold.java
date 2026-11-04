// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * macOS's accent menu in the {@code :} box: holding a letter, such as E, shows a menu of its accented
 * forms (è é ê ë ...), picked with a digit, the arrows and Enter, or the mouse. The box
 * ({@code editor/CommandLineBox}) asks this class about each key press and release; on macOS only,
 * and only while press-and-hold is on ({@link PressAndHoldSetting}).
 *
 * <h2>Why the box needs it</h2>
 * The box sends every key to Neovim, and in 0.2.0 cancelled each in its text field. So macOS never
 * saw an {@code e} it could replace, and holding E sent {@code e} again and again (key repeat) before
 * the menu showed: {@code :echo "eeeeeeeeee2é"} for E held, 2, {@code "}. What macOS sends, logged on
 * macOS with press-and-hold on (plug-in 0.2.1, US, Dvorak and ABC – AZERTY keyboards):
 * <ul>
 *   <li><b>The menu</b> opens only over a letter the field typed itself (on ABC – AZERTY; on US
 *       keyboards it opened anyway). So the box lets the field type a plain letter, as well as
 *       sending it to Neovim ({@link Kind#SEND_AND_TYPE}); Neovim's view of the line, which comes a
 *       moment later, replaces the field's text as always.</li>
 *   <li><b>Repeats</b> of a held key are presses of the same key with no release in between, about
 *       every 83 ms after a first delay of about 500 ms. In macOS's own fields a letter does not
 *       repeat while press-and-hold is on, so its repeats are not sent ({@link Kind#DROP}); keys that
 *       are no letter (Backspace, the arrows, Space) repeat as before.</li>
 *   <li>Once the key is let go, <b>the arrows and Enter</b> choose an accent as marked text over the
 *       typed {@code e}: {@link MarkedTextInput} handles that, replacing the {@code e}.</li>
 *   <li><b>A digit</b> ({@code 2} for é) is a press of the digit, then, with the same event time, a
 *       second press with the digit's key code that carries the accented letter, then the digit's
 *       release about 90 ms later. The digit is the character, not the key: on AZERTY it is Shift
 *       and the {@code é} key, which SWT reports as that key carrying {@code 2}. The key alone types
 *       {@code é} after the {@code e} there, as in TextEdit. The digit is held back
 *       ({@link Kind#HOLD}); the pick is sent as a Backspace over the {@code e} and the letter
 *       ({@link Kind#PICK}); a digit let go with no pick
 *       is sent then ({@link #keyUp}), and so is one still held when something else comes first
 *       ({@link #interrupt}). Not "a moment later": the box tried that, and the moment came
 *       between the digit's two presses, so the pick was lost ({@code :echo "e2"}).</li>
 *   <li><b>Esc</b> closes the menu; it went to Neovim, which closed the line. The first Esc after a
 *       held letter is the menu's ({@link Kind#DROP}).</li>
 *   <li>Another key, such as {@code x}, closes the menu and is typed as usual. So does anything
 *       else that reaches the box, a click, Tab, a paste ({@link #interrupt}). Shift, Cmd and the
 *       other modifier keys alone do not: the menu stays, as for Shift+2 on AZERTY.</li>
 *   <li><b>A click</b> on an accent is an edit with no key code that puts it over the {@code e}
 *       before the caret: {@link MarkedTextInput} sends a Backspace and the accent. But only a click
 *       within about 0.2 s of letting go of the key arrives: later, SWT 3.135's
 *       {@code Text.shouldChangeTextInRange_replacementString} (macOS, a {@code Text} with a verify
 *       listener, as the box has) reads {@code NSApplication.currentEvent()}, which is then
 *       {@code null}, and fails with a {@code NullPointerException}, so the edit is lost. A field
 *       without a verify listener takes such clicks; TextEdit takes them too. Not worked around:
 *       the box needs its verify listener for pastes and input methods.</li>
 * </ul>
 * <p>Which letters have a menu depends on the input source (on the Mac this was logged on, {@code k}
 * had one too), so the rules hold for every letter: after holding a letter with no menu, the first
 * Esc is lost, and a digit typed at once arrives a moment late.
 *
 * <p>Example, {@code :echo "}, then E held, 2:
 * <pre>
 *   press e                      sent: e, and the field types it
 *   press e, press e, ...        repeats: not sent; the menu may be open
 *   release e
 *   press 2                      held back
 *   press 2 carrying é           the pick: sent &lt;BS&gt; and é       ->  :echo "é
 *   release 2                    nothing more
 * </pre>
 *
 * <p>Not thread-safe: the box calls it on the UI thread.
 */
public final class PressAndHold {

    /** Shift, Ctrl, Option and Cmd as key codes: pressed alone, they leave the menu open. */
    private static final int MODIFIER_KEYS = KeyMapping.SWT_ALT | KeyMapping.SWT_SHIFT | KeyMapping.SWT_CTRL
            | KeyMapping.SWT_COMMAND;

    /** The longest pause before a held key repeats that still counts as one (macOS allows 2 s). */
    static final long LONGEST_REPEAT_DELAY_MS = 2500;

    /** What the box does with a key press. */
    public enum Kind {
        /** Sent to Neovim as usual. */
        SEND,
        /**
         * Sent to Neovim, and typed by the field too, as a plain field types it: a letter, over which
         * macOS may open the menu. Not after a digit held back, which Neovim gets first: the field
         * would show the two out of order until Neovim's view came.
         */
        SEND_AND_TYPE,
        /** Not sent: a repeat of a held letter, or the menu's Esc. */
        DROP,
        /** Not sent yet: a digit that may pick from the menu; sent when let go if it did not ({@link #keyUp}). */
        HOLD,
        /** The menu's pick: Neovim deletes the held letter, then gets the accented one. */
        PICK
    }

    /**
     * What the box does with a key press.
     *
     * @param kind      what to do with this press
     * @param sendFirst the digit held back, in Neovim's notation, which the box sends before anything
     *                  else (it picked nothing); or {@code null}
     */
    public record Key(Kind kind, String sendFirst) {
    }

    /** The key now held down (pressed, not yet let go), or 0. */
    private int downKey;
    private long downTime;
    /** A letter was held: the menu may be showing, until the next key. */
    private boolean menuMayBeOpen;
    /** The key code of the digit held back, or 0: on AZERTY, the {@code é} key's for 2. */
    private int heldDigit;
    /** When the digit held back was pressed: the pick has that same time. */
    private long heldTime;
    /** {@link #heldDigit} in Neovim's notation, as the box sends it if it picks nothing. */
    private String heldKey;

    /**
     * A key press, after {@link MarkedTextInput} found it is not an input method's.
     *
     * @param keyCode    its key code (letters lower case); a modifier key alone (Shift) changes nothing
     * @param character  its character, or 0
     * @param neovimKey  the key in Neovim's notation, kept if it is held back ({@link Kind#HOLD})
     * @param modified   whether Cmd, Ctrl or Option was held
     * @param timeMillis when it happened (the event's time)
     * @param enabled    whether press-and-hold is on ({@link PressAndHoldSetting})
     */
    public Key keyDown(int keyCode, char character, String neovimKey, boolean modified, long timeMillis,
            boolean enabled) {
        if ((keyCode & MODIFIER_KEYS) != 0) {
            return new Key(Kind.SEND, null); // Shift alone, as before Shift+2 on AZERTY: the menu stays
        }
        boolean repeat = keyCode == downKey && timeMillis - downTime <= LONGEST_REPEAT_DELAY_MS;
        downKey = keyCode;
        downTime = timeMillis;
        if (!enabled) {
            return new Key(Kind.SEND, null);
        }
        String sendFirst = null;
        if (heldDigit != 0) {
            // First: the pick is a second press of the digit's key, with no release and the same
            // time, carrying a letter. On AZERTY that may be the key's own é: the time tells them apart.
            boolean pick = keyCode == heldDigit && timeMillis == heldTime && Character.isLetter(character);
            String held = heldKey;
            forgetHeld();
            menuMayBeOpen = false;
            if (pick) {
                return new Key(Kind.PICK, null);
            }
            sendFirst = held; // it picked nothing: there was no menu
        }
        if (repeat && !modified && Character.isLetter(character)) {
            menuMayBeOpen = true;
            return new Key(Kind.DROP, sendFirst); // as in macOS's own fields: a held letter shows its menu
        }
        Key key = keyDownWithMenu(keyCode, character, neovimKey, modified, timeMillis, sendFirst);
        if (key.kind() == Kind.SEND && sendFirst == null && !modified && Character.isLetter(character)) {
            return new Key(Kind.SEND_AND_TYPE, sendFirst); // so that macOS may open its menu over it
        }
        return key;
    }

    private Key keyDownWithMenu(int keyCode, char character, String neovimKey, boolean modified,
            long timeMillis, String sendFirst) {
        if (!menuMayBeOpen || modified) {
            menuMayBeOpen = false;
            return new Key(Kind.SEND, sendFirst);
        }
        if (character >= '1' && character <= '9') {
            heldDigit = keyCode; // the menu's digit, or a digit typed after holding a letter
            heldTime = timeMillis;
            heldKey = neovimKey;
            return new Key(Kind.HOLD, sendFirst);
        }
        menuMayBeOpen = false;
        if (keyCode == 27) {
            return new Key(Kind.DROP, sendFirst); // closes the menu, not Neovim's line
        }
        return new Key(Kind.SEND, sendFirst); // another key closes the menu and is typed
    }

    /**
     * A key was let go.
     *
     * @return the digit held back, in Neovim's notation, for the box to send now: the digit was let
     *         go and no pick came (the pick comes within the digit's own press, with the same event
     *         time, and the release about 90 ms later), so there was no menu; or {@code null}
     */
    public String keyUp(int keyCode) {
        if (keyCode == downKey) {
            downKey = 0;
        }
        if (heldDigit != 0 && keyCode == heldDigit) {
            String held = heldKey;
            forgetHeld();
            menuMayBeOpen = false;
            return held;
        }
        return null;
    }

    /**
     * Something reached the box that is not a key press seen by {@link #keyDown}: a click (in the
     * box, or on an accent in the menu), a paste, Tab (a traversal), or a Vim Ctrl key from
     * Eclipse's key bindings. The menu is closed by it, so the next Esc is Neovim's again; a digit
     * held back is no pick now.
     *
     * @return the digit held back, in Neovim's notation, for the box to send before what came; or
     *         {@code null}
     */
    public String interrupt() {
        String held = heldKey;
        forgetHeld();
        menuMayBeOpen = false;
        return held;
    }

    /**
     * An input method's composition began (the menu's arrows): its keys are no longer ours. A digit
     * still held back is dropped, not sent: Neovim's line must not change while the box shows marked
     * text ({@link MarkedTextInput}). It cannot happen with the menu, whose digit picks at once.
     */
    public void compositionStarted() {
        forgetHeld();
        menuMayBeOpen = false;
    }

    /** Forgets everything: the box opened or closed. */
    public void reset() {
        downKey = 0;
        downTime = 0;
        menuMayBeOpen = false;
        forgetHeld();
    }

    private void forgetHeld() {
        heldDigit = 0;
        heldKey = null;
    }
}
