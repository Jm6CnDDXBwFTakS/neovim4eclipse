// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimProbe;

/**
 * What the preference pages need of Eclipse, which their tests in {@code tests/swt} leave out: the
 * classes {@code plugin.xml} names build on the plug-in's store, and the Neovim group's real probe
 * offers Get Neovim for a refused program only while the dedicated Neovim is missing. The pages'
 * bodies themselves are {@code NeovimPageTest}, {@code StatusBarPageTest} and {@code CtrlKeysPageTest}.
 */
class PreferencePagesUiTest {

    /**
     * Each class {@code plugin.xml} names starts as Eclipse starts it (no-argument constructor, then
     * {@code init}), works on the plug-in's own store, and builds its controls.
     */
    @Test
    void thePagesEclipseOpensBuildOnThePluginsStore() {
        List<Supplier<PreferencePage>> pages = List.of(NeovimPreferencePage::new, StatusBarPreferencePage::new,
                CtrlKeysPreferencePage::new);
        for (Supplier<PreferencePage> made : pages) {
            Shell shell = new Shell(Display.getCurrent());
            PreferencePage page = made.get();
            try {
                ((IWorkbenchPreferencePage) page).init(PlatformUI.getWorkbench());
                page.createControl(shell);
                String name = page.getClass().getName();
                assertSame(Activator.getDefault().getPreferenceStore(), page.getPreferenceStore(), name);
                assertNotNull(page.getControl(), name);
            } finally {
                page.dispose();
                shell.dispose();
            }
        }
    }

    /**
     * {@link NvimProbe#describe} for a program the option names that does not exist: removing the
     * option is the fix while the dedicated Neovim checks out, so the page offers no Get Neovim; without
     * it, installing it is a fix, so the button is offered (the rule: {@code NvimProbeTest}). The
     * dedicated Neovim is put into the tests' own data folder for this ({@link DedicatedNeovim}).
     */
    @Test
    void aRefusedProgramOffersGetNeovimOnlyWithoutTheDedicatedNeovim() {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String missing = windows ? "C:\\no\\such\\nvim.exe" : "/no/such/nvim";
        DedicatedNeovim.remove();
        assertTrue(NvimProbe.describe(missing).getNeovim(), "no dedicated Neovim: Get Neovim is offered");
        DedicatedNeovim.install();
        try {
            assertFalse(NvimProbe.describe(missing).getNeovim(), "the dedicated Neovim checks out: no Get Neovim");
        } finally {
            DedicatedNeovim.remove();
        }
    }
}
