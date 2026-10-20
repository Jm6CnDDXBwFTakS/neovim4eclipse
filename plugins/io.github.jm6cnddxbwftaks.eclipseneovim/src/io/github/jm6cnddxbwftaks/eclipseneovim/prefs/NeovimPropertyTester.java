// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.prefs;

import org.eclipse.core.expressions.PropertyTester;

/**
 * Property tester for menu/toolbar {@code visibleWhen} expressions.
 * Properties: {@code io.github.jm6cnddxbwftaks.eclipseneovim.showToolbar} (the NVIM toolbar button)
 * and {@code io.github.jm6cnddxbwftaks.eclipseneovim.showMenu} (the Neovim menu in the menu bar).
 *
 * <p>{@code plugin.xml} decides when contributions are visible with <em>core expressions</em>,
 * small XML conditions such as {@code <test property="...showToolbar" value="true"/>}. A property
 * tester answers such a test in Java. It is registered under
 * {@code org.eclipse.core.expressions.propertyTesters} with a namespace and property names; the
 * expression framework calls {@link #test} whenever it re-evaluates the condition. Eclipse caches
 * the answer, so {@link ContributionVisibility#refresh()} asks for a re-evaluation after the preference
 * changes.
 */
public class NeovimPropertyTester extends PropertyTester {

    @Override
    public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        if ("showToolbar".equals(property)) {
            return matches(ContributionVisibility.isShowToolbar(), expectedValue);
        }
        if ("showMenu".equals(property)) {
            return matches(ContributionVisibility.isShowMenu(), expectedValue);
        }
        return false;
    }

    /** {@code <test property="..."/>} without a value asks whether the property is true. */
    private static boolean matches(boolean actual, Object expectedValue) {
        if (expectedValue == null) {
            return actual;
        }
        return actual == Boolean.parseBoolean(String.valueOf(expectedValue));
    }
}
