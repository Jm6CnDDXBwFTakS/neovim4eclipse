// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.prefs;

import org.eclipse.core.expressions.PropertyTester;

/**
 * Property tester for menu/toolbar {@code visibleWhen} expressions.
 * Property: {@code io.github.jm6cnddxbwftaks.eclipseneovim.showToolbar}
 *
 * <p>{@code plugin.xml} decides when contributions are visible with <em>core expressions</em>,
 * small XML conditions such as {@code <test property="...showToolbar" value="true"/>}. A property
 * tester answers such a test in Java. It is registered under
 * {@code org.eclipse.core.expressions.propertyTesters} with a namespace and property names; the
 * expression framework calls {@link #test} whenever it re-evaluates the condition. Eclipse caches
 * the answer, so {@link ToolbarVisibility#refresh()} asks for a re-evaluation after the preference
 * changes.
 */
public class NeovimPropertyTester extends PropertyTester {

    @Override
    public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        if ("showToolbar".equals(property)) {
            boolean show = ToolbarVisibility.isShowToolbar();
            if (expectedValue == null) {
                return show;
            }
            return show == Boolean.parseBoolean(String.valueOf(expectedValue));
        }
        return false;
    }
}
