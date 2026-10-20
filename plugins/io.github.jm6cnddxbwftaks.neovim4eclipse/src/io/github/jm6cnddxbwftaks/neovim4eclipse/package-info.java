// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

/**
 * Neovim for Eclipse: Vim editing in Eclipse text editors, performed by an embedded Neovim process.
 *
 * <h2>Packages</h2>
 * <pre>
 *   (this package)  OSGi/plug-in entry points: Activator, EarlyStartup
 *   editor          Eclipse glue: which editor is attached, keys, caret, status bar, command line
 *   sync            keeps an Eclipse document and its Neovim buffer in step
 *   prefs           Window &gt; Preferences &gt; Neovim
 *   core            the Neovim process and msgpack-rpc session      (no Eclipse imports)
 *   util            pure logic: key notation, columns, diffs, parsing (no Eclipse imports)
 * </pre>
 *
 * <p>Dependencies point downwards: {@code editor} uses {@code sync}, {@code core} and {@code util};
 * {@code core} uses {@code util}; {@code util} uses nothing of the plug-in. {@code core} and
 * {@code util} are compiled by {@code tests/unit} without any Eclipse jar on the class path, so an
 * {@code org.eclipse} import there breaks the build. That rule is what keeps them unit-testable.
 *
 * <p>New to Eclipse plug-ins or the Neovim API? Start with {@code docs/LEARNING.md}.
 */
package io.github.jm6cnddxbwftaks.neovim4eclipse;
