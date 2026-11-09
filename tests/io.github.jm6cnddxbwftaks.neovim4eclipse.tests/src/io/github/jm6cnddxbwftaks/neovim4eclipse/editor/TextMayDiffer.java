// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Leaves a UI test out of the text check at its end ({@code EndToEndTestBase}): a test whose
 * Eclipse document and Neovim buffer may rightly differ when it ends, or that makes them differ on
 * purpose. Example: a test that ends with Neovim mode switched off, while Eclipse edits are not
 * pushed. On a test method, or on a class for all its tests.
 *
 * <p>The repair check still runs: a push that repairs a drift fails such a test too. {@link #value}
 * says why the texts may differ, so that a reader can tell whether it still holds.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
@interface TextMayDiffer {
    /** Why the texts may differ when the test ends. */
    String value();
}
