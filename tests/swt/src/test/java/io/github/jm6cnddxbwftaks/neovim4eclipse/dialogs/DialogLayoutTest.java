// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.LayoutCheck;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GetNeovimText;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimPlatform;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimRelease;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable.Kind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable.Resolved;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OldNeovimProgram;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginNeovim;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StartFailureReport;

/**
 * Every dialog the checks gallery pictures shows all its text: no label, Copy field or button cut
 * off, nothing over the window's edge, no two texts on top of each other ({@link LayoutCheck}). The
 * dialogs are built from the plug-in's real messages, as {@code ChecksGalleryTest} builds them, on
 * this platform's own fonts.
 *
 * <p>This takes over the "nothing cut off" part of the gallery card (gl-gallery in
 * {@code docs/MANUAL_CHECKS.md}): a person still looks at the pictures for the overall impression,
 * but a dialog that no longer fits fails here, on every platform the tests run on. Example: a longer
 * Get Neovim button label, or a path in a Copy field wider than its column, is reported with the
 * text that does not fit.
 */
class DialogLayoutTest {

    private static final String PROGRAM = "/home/u/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim";

    @Test
    void getNeovimsDialogsShowAllTheirText() throws IOException {
        NeovimRelease release = release();
        NeovimRelease.Archive archive = release.forPlatform(NeovimPlatform.byCode("la").orElseThrow()).orElseThrow();
        Path program = Path.of(PROGRAM);
        Path home = program.getParent().getParent();
        Resolved missing = new Resolved(PROGRAM, Kind.NO_DEDICATED);
        fits("Get Neovim at a fresh start", MessageDialog.QUESTION, GetNeovimText.confirm(release, archive, home,
                StartFailureReport.getNeovimInstead(missing, Optional.empty()).orElseThrow()), GetNeovimText.CONFIRM_BUTTONS);
        fits("Get Neovim when the files differ", MessageDialog.QUESTION, GetNeovimText.confirm(release, archive, home,
                StartFailureReport.getNeovimInstead(missing, Optional.of(new PluginNeovim.Unverified(program)))
                        .orElseThrow()), GetNeovimText.CONFIRM_BUTTONS);
        fits("Get Neovim done", MessageDialog.INFORMATION,
                GetNeovimText.done(release.version(), program, archive.sha256(), false, false), List.of("Close"));
        fits("Get Neovim done, with the option set", MessageDialog.INFORMATION,
                GetNeovimText.done(release.version(), program, archive.sha256(), false, true), List.of("Close"));
    }

    @Test
    void theStartNoticeShowsAllItsTextForEachRefusedValue() {
        for (Resolved refused : List.of(new Resolved("/home/u/own/nvim-linux-arm64", Kind.FOLDER),
                new Resolved("nvim", Kind.NOT_ABSOLUTE), new Resolved("/no/such/folder/bin/nvim", Kind.MISSING))) {
            fits("Neovim not started: " + refused.kind(), MessageDialog.ERROR,
                    StartFailureReport.message(refused, new IOException("Cannot run program"), List.of(),
                            Optional.empty(), true, path -> null),
                    List.of("Open Preferences", "Close"));
        }
    }

    @Test
    void theOldPreferenceNoteShowsAllItsText() {
        fits("Old preference note", MessageDialog.INFORMATION,
                OldNeovimProgram.message("/usr/local/bin/nvim", "", false).orElseThrow(), OldNeovimProgram.BUTTONS);
        fits("Old preference note, a Windows path with a space", MessageDialog.INFORMATION,
                OldNeovimProgram.message("C:\\Program Files\\Neovim\\bin\\nvim.exe", "", true).orElseThrow(),
                OldNeovimProgram.BUTTONS);
    }

    /** Opens the dialog as the plug-in does, at its own size, and asserts that it fits. */
    private static void fits(String what, int kind, HeadedMessage message, List<String> buttons) {
        Display display = Display.getDefault(); // this thread becomes the UI thread, as JFace wants
        HeadedMessageDialog dialog = new HeadedMessageDialog(null, what, kind, message, 0, buttons.toArray(String[]::new));
        dialog.setBlockOnOpen(false);
        dialog.open();
        try {
            while (display.readAndDispatch()) {
                // let the layout settle
            }
            assertEquals(List.of(), LayoutCheck.problems(dialog.getShell()), what);
        } finally {
            dialog.close();
        }
    }

    /**
     * The release the plug-in pins, read from its data file: this module compiles the plug-in's
     * sources but does not copy its data files, so {@link NeovimRelease#bundled()} has nothing to read.
     */
    private static NeovimRelease release() throws IOException {
        Path file = Path.of("../../plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/src/io/github/jm6cnddxbwftaks"
                + "/neovim4eclipse/util/neovim-downloads.properties");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return NeovimRelease.parse(reader);
        }
    }
}
