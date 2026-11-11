// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

/**
 * The plug-in's preferences: keys and defaults ({@link io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants},
 * {@link io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceInitializer}) and the pages under
 * Window &gt; Preferences &gt; Neovim.
 *
 * <p>Eclipse keeps preferences in scopes. The <em>default</em> scope holds values supplied by code at
 * start-up and is never saved; the <em>instance</em> scope holds what the user changed and is saved in
 * the workspace. Reading a key returns the instance value if there is one, else the default. The
 * pages write through an {@link org.eclipse.jface.preference.IPreferenceStore}; the
 * {@link io.github.jm6cnddxbwftaks.neovim4eclipse.Activator} listens to the store and applies changes
 * while Eclipse runs.
 *
 * <h2>Two classes per page</h2>
 * Each page is a body in JFace alone ({@code NeovimPage}, {@code StatusBarPage},
 * {@code CtrlKeysPage}) and the class {@code plugin.xml} names, a subclass of it a few lines long
 * ({@code NeovimPreferencePage}, ...). The extension point {@code org.eclipse.ui.preferencePages}
 * wants an {@code IWorkbenchPreferencePage}, an interface of the workbench jar, and a no-argument
 * constructor; the subclass adds that, and hands the body what only Eclipse has: the plug-in's
 * preference store, and for the Neovim page its section's probe and actions
 * ({@code WorkbenchSectionActions}) and the menu's visibility after Apply. The bodies take those
 * through their constructors, so the SWT tests ({@code tests/swt}, no workbench) build them on an
 * in-memory {@code PreferenceStore} with the plug-in's defaults ({@code PreferenceDefaults}).
 * Example: {@code new StatusBarPage(store).createControl(shell)}. The compiler keeps the bodies
 * free of the workbench: {@code tests/swt/pom.xml} lists them, without the workbench jar.
 *
 * <p>{@link io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.NeovimPropertyTester} and
 * {@link io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.ContributionVisibility} let {@code plugin.xml}
 * show or hide the toolbar button and the Neovim menu through {@code visibleWhen} expressions, which is evaluated
 * without running plug-in code until the expression needs the value.
 */
package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;
