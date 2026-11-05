// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import static io.github.jm6cnddxbwftaks.neovim4eclipse.editor.UiTestSupport.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.Activator;
import io.github.jm6cnddxbwftaks.neovim4eclipse.EarlyStartup;
import io.github.jm6cnddxbwftaks.neovim4eclipse.core.NeovimSession;
import io.github.jm6cnddxbwftaks.neovim4eclipse.prefs.PreferenceConstants;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimOption;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OldNeovimProgram;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil;

/**
 * What Eclipse's start shows about Neovim, run the way {@code EarlyStartup} runs it: first the
 * once-only note for an old "Neovim program" preference ({@link OldNeovimProgramNotice}), then, when
 * the dedicated Neovim is missing or fails its check, Get Neovim
 * ({@link EditorAttachManager#offerGetNeovimAtStart}).
 *
 * <p>These replace three manual checks: "A start with the checked Neovim asks nothing"
 * (gn-restart-quiet), "An old Neovim program preference is shown once, then removed"
 * (gn-old-setting), and "An imported preferences file cannot name a program to run"
 * (sec-epf-old-key). The words of each dialog are unit-tested ({@code OldNeovimProgramTest},
 * {@code GetNeovimTextTest}) and drawn in the checks gallery; here only which dialog opens, when, and
 * what is left afterwards.
 *
 * <p>How a dialog is seen without stalling the test: a dialog runs its own event loop, so a test
 * waiting for it would wait inside it. A timer ({@code Display.timerExec}, which also runs inside
 * that loop) notes each modal dialog's title, and what was true at that moment, then closes it.
 * Every modal dialog, not only the start's: while this watch runs, {@code UiTestSupport}'s
 * error-dialog watch stays out of its way, so an unexpected error dialog must be closed (and
 * fail the test by its title) here, or it would stall the whole run.
 */
class StartNoticesEndToEndTest extends EndToEndTestBase {

    /** A folder of the test's own (the fragment cannot use JUnit's {@code @TempDir}: its package is not imported). */
    private Path tmp;

    /** The startup option as the test Eclipse got it, put back after a test that changes it. */
    private final String option = System.getProperty(NeovimOption.PROPERTY);

    @AfterEach
    void restoreTheOptionAndThePreference() throws java.io.IOException {
        if (tmp != null) {
            try (var paths = Files.walk(tmp)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        if (option == null) {
            System.clearProperty(NeovimOption.PROPERTY);
        } else {
            System.setProperty(NeovimOption.PROPERTY, option);
        }
        store().setToDefault(PreferenceConstants.REMOVED_NVIM_PROGRAM);
    }

    /**
     * With a Neovim that checks out, the start opens no dialog: the user's everyday start. Runs the
     * real start step, {@link EarlyStartup#earlyStartup}, off the UI thread as the workbench does:
     * the dedicated Neovim's check, the old-preference note, then Get Neovim's offer when no
     * {@code -Dneovim4eclipse.nvim} is given. Without {@code -Dnvim.path} the test Eclipse runs
     * exactly that way (no option, the dedicated Neovim); with it, the offer is skipped as for a
     * user who names their own Neovim.
     */
    @Test
    void aStartWithACheckedNeovimOpensNoDialog() {
        String configured = Activator.getDefault().getNvimExecutable();
        assumeTrue(NvimExecutable.find(configured).kind() != NvimExecutable.Kind.NO_DEDICATED,
                "no Neovim that checks out here, so a start would offer Get Neovim");
        List<String> seen = watchStartDialogs(() -> null);
        AtomicBoolean finished = new AtomicBoolean();
        CompletableFuture.runAsync(() -> {
            new EarlyStartup().earlyStartup();
            // Queued after whatever the start queued for the UI thread, so once it runs, any
            // dialog the start asked for has been opened (and noted).
            Display.getDefault().asyncExec(() -> finished.set(true));
        });
        waitUntil("the start's steps", TIMEOUT_MS, finished::get);
        assertEquals(List.of(), seen, "dialogs at a start with " + (configured == null || configured.isBlank()
                ? "no option" : configured));
    }

    /**
     * Without the option, a stored value shows the note once and is removed the moment it shows;
     * what comes next at the start waits for Close; the next start shows nothing.
     */
    @Test
    void anOldNeovimProgramPreferenceIsShownOnceAndRemoved() {
        System.clearProperty(NeovimOption.PROPERTY);
        store().setValue(PreferenceConstants.REMOVED_NVIM_PROGRAM, "/usr/local/bin/nvim");
        AtomicBoolean thenRan = new AtomicBoolean();
        List<String> seen = watchStartDialogs(() -> "stored='" + store().getString(PreferenceConstants.REMOVED_NVIM_PROGRAM)
                + "' then=" + thenRan.get());

        OldNeovimProgramNotice.showOnce(() -> thenRan.set(true));
        waitUntil("the start's next step, after the note", TIMEOUT_MS, thenRan::get);
        assertEquals(List.of(OldNeovimProgram.TITLE + " [stored='' then=false]"), seen,
                "the note, shown with the value already removed and the next step waiting");

        seen.clear();
        thenRan.set(false);
        OldNeovimProgramNotice.showOnce(() -> thenRan.set(true));
        // Nothing stored: the next step ran at once, on this thread, and nothing was queued to show.
        assertTrue(thenRan.get(), "nothing stored: the next step runs at once");
        assertEquals(List.of(), seen, "the next start shows no note");
    }

    /** With the option set, the stored value is only removed: the user already named their Neovim. */
    @Test
    void withTheOptionSetTheOldValueIsRemovedWithoutANote() {
        System.setProperty(NeovimOption.PROPERTY, "/opt/nvim/bin/nvim");
        store().setValue(PreferenceConstants.REMOVED_NVIM_PROGRAM, "/usr/local/bin/nvim");
        AtomicBoolean thenRan = new AtomicBoolean();
        List<String> seen = watchStartDialogs(() -> null);

        OldNeovimProgramNotice.showOnce(() -> thenRan.set(true));
        waitUntil("the start's next step", TIMEOUT_MS, thenRan::get);
        assertEquals(List.of(), seen);
        assertEquals("", store().getString(PreferenceConstants.REMOVED_NVIM_PROGRAM), "removed");
    }

    /**
     * A preferences file (.epf, as a project or a colleague may hand one around) that sets the old
     * key cannot make the plug-in run a program: the key is no longer read for a start. Before
     * 0.2.1 this import ran the named program, unchecked. Example: the file names a script that
     * writes a marker; after the import and a Neovim restart the marker is still missing, and the
     * next start shows the value in the once-only note.
     */
    @Test
    void anImportedPreferencesFileCannotNameAProgramToRun() throws Exception {
        assumeFalse(PlatformUtil.isWindows(), "a shell script stands in for the planted program");
        tmp = Files.createTempDirectory("n4e-epf");
        Path marker = tmp.resolve("ran");
        Path fake = Files.writeString(tmp.resolve("fake-nvim"), "#!/bin/sh\necho ran >> '" + marker + "'\n");
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
        String epf = "file_export_version=3.0\n/instance/" + Activator.PLUGIN_ID + "/"
                + PreferenceConstants.REMOVED_NVIM_PROGRAM + "=" + fake + "\n";
        Platform.getPreferencesService().importPreferences(new ByteArrayInputStream(epf.getBytes(StandardCharsets.UTF_8)));
        assertEquals(fake.toString(), store().getString(PreferenceConstants.REMOVED_NVIM_PROGRAM), "the import took");

        Activator.getDefault().restartNeovim();
        NeovimSession fresh = Activator.getDefault().currentSession();
        // Not waitForAttach: its settle asks the base's session, which the restart stopped.
        waitUntil("Neovim attached again", TIMEOUT_MS, () -> {
            NeovimEditorController c = EditorAttachManager.getInstance().getActiveController();
            return c != null && c.getEditor() == editor && !c.isClosed();
        });
        String progpath = eval(fresh, "v:progpath");
        assertNotEquals(fake.toString(), progpath, "the planted program is not the one that runs");
        assertFalse(Files.exists(marker), "nothing ran the planted program");

        System.clearProperty(NeovimOption.PROPERTY);
        AtomicBoolean thenRan = new AtomicBoolean();
        List<String> seen = watchStartDialogs(() -> null);
        OldNeovimProgramNotice.showOnce(() -> thenRan.set(true));
        waitUntil("the start's next step", TIMEOUT_MS, thenRan::get);
        assertEquals(List.of(OldNeovimProgram.TITLE), seen, "the next start shows the value once");
        assertFalse(Files.exists(marker), "showing the value runs nothing either");
    }

    /**
     * Until the test ends, notes each modal dialog that opens (its title, plus {@code state} at that
     * moment in brackets when it gives one) and closes it.
     */
    private List<String> watchStartDialogs(java.util.function.Supplier<String> state) {
        Display display = Display.getCurrent();
        List<String> seen = new ArrayList<>();
        Runnable ended = UiTestSupport.dialogWatchStarted();
        AtomicBoolean done = new AtomicBoolean();
        widget.addDisposeListener(e -> done.set(true));
        int modal = SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL;
        display.timerExec(50, new Runnable() {
            @Override
            public void run() {
                if (done.get() || display.isDisposed()) {
                    ended.run();
                    return;
                }
                for (Shell shell : display.getShells()) {
                    if (shell.isVisible() && (shell.getStyle() & modal) != 0) {
                        String now = state.get();
                        seen.add(now == null ? shell.getText() : shell.getText() + " [" + now + "]");
                        shell.close();
                    }
                }
                display.timerExec(50, this);
            }
        });
        return seen;
    }

    private static IPreferenceStore store() {
        return Activator.getDefault().getPreferenceStore();
    }

    /** Neovim's answer without blocking the UI thread (a restarted Neovim's attach still needs it). */
    private static String eval(NeovimSession target, String expression) {
        CompletableFuture<String> answer = new CompletableFuture<>();
        target.evalAsync(expression, answer::complete);
        waitUntil("Neovim's answer to " + expression, TIMEOUT_MS, answer::isDone);
        return answer.join();
    }
}
