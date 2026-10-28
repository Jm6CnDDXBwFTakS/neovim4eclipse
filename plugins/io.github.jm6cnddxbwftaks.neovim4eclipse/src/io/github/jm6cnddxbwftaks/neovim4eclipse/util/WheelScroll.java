// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * How many lines Neovim's window scrolls for one vertical mouse-wheel or touchpad event that SWT
 * reports in the editor (docs/design/VIEWPORT.md, decisions D1 and D9). Positive is down, as
 * {@code Ctrl-E}; negative is up, as {@code Ctrl-Y}.
 *
 * <p><b>The rule: the lines the operating system reports.</b> SWT's {@code Event.count} is already
 * a number of lines, with the system's speed, acceleration and momentum in it, and positive means
 * up (the wheel turned away from you). So Neovim scrolls as far as Eclipse itself would have.
 * Measured with {@code docs/design/probes/WheelProbe.java}:
 * <ul>
 *   <li>macOS (X10): a wheel notch is {@code count} −1 (one line down); a trackpad sends an event
 *       about every 16 ms with {@code count} ±1, and a flick's momentum rises to −13 per event.</li>
 *   <li>Linux, GTK, in a VM on that Mac (X11): every event, wheel and trackpad alike, is
 *       {@code count} ±3 (three lines, as Eclipse moves 78 pixels of 26-pixel lines); a flick
 *       sent 364 events in about a second.</li>
 * </ul>
 * Nothing in an event tells a trackpad from a wheel, so one rule serves both. No event of 0 lines
 * was seen, so nothing is added up: SWT reports whole lines. A real Linux or Windows touchpad is
 * not measured yet (D9); a measurement from one becomes a case in {@code WheelScrollTest}.
 *
 * <p><b>Pages.</b> Windows can be set to scroll "one screen at a time": SWT then reports
 * {@link #SCROLL_PAGE} in {@code Event.detail}, and {@code count} is in pages. A page scrolls the
 * editor's rows less two, as {@code Ctrl-F} does, so two lines stay in view.
 *
 * <p>Rejected (D9): one Neovim wheel step ({@code 'mousescroll'}, 3 lines) per event, which made a
 * macOS trackpad scroll three times as fast as Eclipse and a flick about 500 lines.
 */
public final class WheelScroll {

    /** {@code SWT.SCROLL_LINE}: {@code count} is in lines (the value SWT uses; util has no SWT). */
    public static final int SCROLL_LINE = 1;
    /** {@code SWT.SCROLL_PAGE}: {@code count} is in pages. */
    public static final int SCROLL_PAGE = 2;

    private WheelScroll() {
    }

    /**
     * The lines to scroll for one event.
     *
     * @param count    SWT's {@code Event.count}: lines (or pages), positive up
     * @param detail   SWT's {@code Event.detail}: {@link #SCROLL_PAGE} for pages, anything else lines
     * @param pageRows the editor's height in rows, for pages
     * @return lines to scroll, positive down; 0 for nothing. Example: {@code lines(-3, SCROLL_LINE, 40)}
     *         is 3, {@code lines(1, SCROLL_PAGE, 40)} is −38.
     */
    public static int lines(int count, int detail, int pageRows) {
        if (detail == SCROLL_PAGE) {
            return -count * Math.max(1, pageRows - 2);
        }
        return -count;
    }
}
