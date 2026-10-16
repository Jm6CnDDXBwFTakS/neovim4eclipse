// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

/**
 * The plug-in's preferences: keys and defaults ({@link io.github.jm6cnddxbwftaks.eclipseneovim.prefs.PreferenceConstants},
 * {@link io.github.jm6cnddxbwftaks.eclipseneovim.prefs.PreferenceInitializer}) and the pages under
 * Window &gt; Preferences &gt; Neovim.
 *
 * <p>Eclipse keeps preferences in scopes. The <em>default</em> scope holds values supplied by code at
 * start-up and is never saved; the <em>instance</em> scope holds what the user changed and is saved in
 * the workspace. Reading a key returns the instance value if there is one, else the default. The
 * pages write through an {@link org.eclipse.jface.preference.IPreferenceStore}; the
 * {@link io.github.jm6cnddxbwftaks.eclipseneovim.Activator} listens to the store and applies changes
 * while Eclipse runs.
 *
 * <p>{@link io.github.jm6cnddxbwftaks.eclipseneovim.prefs.NeovimPropertyTester} and
 * {@link io.github.jm6cnddxbwftaks.eclipseneovim.prefs.ToolbarVisibility} let {@code plugin.xml}
 * show or hide the toolbar button through a {@code visibleWhen} expression, which is evaluated
 * without running plug-in code until the expression needs the value.
 */
package io.github.jm6cnddxbwftaks.eclipseneovim.prefs;
