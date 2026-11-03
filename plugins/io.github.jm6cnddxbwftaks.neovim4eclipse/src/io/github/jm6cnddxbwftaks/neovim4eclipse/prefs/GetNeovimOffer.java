// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.services.IEvaluationService;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimPlatform;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimRelease;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginFolders;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginNeovim;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.ProcessorProbe;

/**
 * Whether the Neovim menu offers <i>Get Neovim</i>: while the plug-in's own folder for this platform
 * ({@code neovim/<code>/}) has no Neovim, or one that fails the check ({@link PluginNeovim#verified}:
 * an older release, or damaged). (The preference page and the "Neovim not started" notice always
 * offer it; the page as its <i>Reinstall...</i> link when the dedicated Neovim is verified,
 * {@code NeovimLocationFieldEditor}.)
 *
 * <h2>Why cached</h2>
 * The answer hashes the install's files (about 0.2 s), and the menu asks while it is being shown, on the UI
 * thread. So the answer is worked out on a background thread and kept; until it is in, the menu
 * shows no entry. When it arrives, or changes after an install ({@link #recheck()}), the menu's
 * {@code visibleWhen} is evaluated again ({@link IEvaluationService#requestEvaluation}).
 */
final class GetNeovimOffer {

    /** The property in {@code plugin.xml}'s {@code visibleWhen}, with its namespace. */
    static final String PROPERTY = "io.github.jm6cnddxbwftaks.neovim4eclipse.offerGetNeovim";

    private static volatile Boolean offered;
    private static final AtomicBoolean CHECKING = new AtomicBoolean();

    private GetNeovimOffer() {
    }

    /** The cached answer; starts working it out if there is none. Never waits. */
    static boolean offered() {
        Boolean known = offered;
        if (known == null) {
            recheck();
            return false;
        }
        return known;
    }

    /** Works the answer out again, in the background, and tells the menu if it changed. */
    static void recheck() {
        if (!CHECKING.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.supplyAsync(GetNeovimOffer::compute).whenComplete((answer, error) -> {
            CHECKING.set(false);
            boolean value = answer != null && answer;
            Boolean before = offered;
            offered = value;
            if (before == null || before != value) {
                requestEvaluation();
            }
        });
    }

    /** Whether the plug-in's own Neovim for the real processor is missing or fails the check (older, damaged). */
    private static boolean compute() {
        Optional<NeovimPlatform> platform = ProcessorProbe.await();
        NeovimRelease release = NeovimRelease.bundled();
        if (platform.isEmpty() || release.forPlatform(platform.get()).isEmpty()) {
            return false; // nothing to offer here
        }
        Path home = PluginNeovim.home(PluginFolders.neovimFolder(), platform.get());
        // Missing, or not the pinned release's files (older, damaged): the same check as at start,
        // so nothing of an unchecked install runs here (not even --version).
        return !PluginNeovim.verified(home, platform.get().windows(), release);
    }

    private static void requestEvaluation() {
        if (!PlatformUI.isWorkbenchRunning()) {
            return;
        }
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            IEvaluationService service = PlatformUI.getWorkbench().getService(IEvaluationService.class);
            if (service != null) {
                service.requestEvaluation(PROPERTY);
            }
        });
    }
}
