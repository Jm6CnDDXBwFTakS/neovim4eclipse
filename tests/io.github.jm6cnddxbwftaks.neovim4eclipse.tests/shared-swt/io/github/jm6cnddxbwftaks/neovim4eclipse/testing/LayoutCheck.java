// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.testing;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;

/**
 * Whether a laid-out window shows all its text: what a person used to check by eye in the checks
 * gallery ("nothing cut off"), asked of the widgets instead, so it runs on every platform the tests
 * run on and needs no stored picture.
 *
 * <p>Three rules, for every visible control below the window:
 * <ul>
 *   <li><b>Its text fits.</b> A label, link, button or text field is at least as large as it asks to
 *       be ({@link Control#computeSize}). A wrapping one (a {@code SWT.WRAP} label, any link) is
 *       asked at its own width, so a note that wraps onto four lines needs four lines' height. Example: a Copy field half as wide as the path
 *       it holds, or a button narrower than "Get Neovim 0.12.6...", is reported.</li>
 *   <li><b>It is inside its parent's client area,</b> so nothing hangs over the window's edge.</li>
 *   <li><b>It does not overlap a sibling,</b> so no two texts are drawn over each other.</li>
 * </ul>
 * One pixel is allowed either way, for rounding in the platforms' own size computations. Ask it of a
 * window that is open and laid out: some platforms measure wrapped text only once it is shown.
 *
 * <p>Shared by {@code tests/swt} (the dialogs) and the UI tests (the preference page's group), from
 * the UI test fragment's {@code shared-swt/} folder: it needs SWT, which {@code tests/unit} and
 * {@code tests/text} (the users of {@code shared/}) do not have.
 */
public final class LayoutCheck {

    /** Pixels of rounding allowed. */
    private static final int SLACK = 1;

    private LayoutCheck() {
    }

    /** What does not fit in {@code root}'s layout, one line each; empty when everything shows. */
    public static List<String> problems(Composite root) {
        List<String> problems = new ArrayList<>();
        check(root, problems);
        return problems;
    }

    private static void check(Composite parent, List<String> problems) {
        Rectangle area = parent.getClientArea();
        List<Control> shown = new ArrayList<>();
        for (Control child : parent.getChildren()) {
            // Its own flag, not isVisible(): that is false for every control of a window not shown yet,
            // and the check would pass a window it never looked at.
            if (!child.getVisible()) {
                continue;
            }
            Rectangle b = child.getBounds();
            if (b.width <= 0 || b.height <= 0) {
                continue; // a control laid out to nothing (an empty row) shows no text either
            }
            if (b.x < -SLACK || b.y < -SLACK || b.x + b.width > area.width + SLACK
                    || b.y + b.height > area.height + SLACK) {
                problems.add(describe(child) + " reaches outside its parent: " + b + " in " + area);
            }
            for (Control other : shown) {
                Rectangle overlap = b.intersection(other.getBounds());
                if (overlap.width > SLACK && overlap.height > SLACK) {
                    problems.add(describe(child) + " overlaps " + describe(other));
                }
            }
            shown.add(child);
            textFits(child, b, problems);
            if (child instanceof Composite composite) {
                check(composite, problems);
            }
        }
    }

    private static void textFits(Control control, Rectangle b, List<String> problems) {
        boolean text = control instanceof Label || control instanceof Link || control instanceof Button
                || control instanceof Text;
        if (!text || (control.getStyle() & SWT.SEPARATOR) != 0) {
            return;
        }
        // A Link wraps at its width without SWT.WRAP (GTK, Windows' SysLink and Cocoa alike): the
        // preference page's help is one, laid out on several lines.
        boolean wraps = (control.getStyle() & SWT.WRAP) != 0 || control instanceof Link;
        Point wanted = wraps ? control.computeSize(b.width, SWT.DEFAULT) : control.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        if (wanted.y > b.height + SLACK || (!wraps && wanted.x > b.width + SLACK)) {
            problems.add(describe(control) + " is cut off: it needs " + wanted.x + "x" + wanted.y + ", has "
                    + b.width + "x" + b.height);
        }
    }

    private static String describe(Control control) {
        String text = control instanceof Label l ? l.getText()
                : control instanceof Link l ? l.getText()
                : control instanceof Button bt ? bt.getText()
                : control instanceof Text t ? t.getText() : "";
        String shortText = text.length() > 50 ? text.substring(0, 47) + "..." : text;
        return control.getClass().getSimpleName() + (shortText.isEmpty() ? "" : " '" + shortText.replace('\n', ' ') + "'");
    }
}
