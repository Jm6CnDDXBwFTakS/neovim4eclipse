// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.prefs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.function.Function;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.jm6cnddxbwftaks.neovim4eclipse.dialogs.HeadedMessageDialog;
import io.github.jm6cnddxbwftaks.neovim4eclipse.gallery.GalleryCamera;
import io.github.jm6cnddxbwftaks.neovim4eclipse.gallery.PicturesFolder;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.GetNeovimText;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HeadedMessage;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimOption;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimPlatform;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimRelease;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable.Kind;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable.Resolved;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimProbe;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.OldNeovimProgram;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.PluginNeovim;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.StartFailureReport;

/**
 * The screenshot gallery for the manual checks: the dialogs and preference-page notes that a
 * person must look at before a release (layout, spacing, wording on screen, the error color), drawn
 * by the plug-in's own code, so looking at the pictures replaces opening each dialog by hand. The
 * pictures are grouped by topic (a short name such as {@code dedicated}), one heading per topic on
 * the page.
 *
 * <p>Not a test, though named like one (Surefire runs only classes named *Test): it asserts only
 * what it needs to reach each state, and writes PNG files plus an {@code index.html} that shows
 * each picture under its topic and what to look for. Run it with the {@code gallery} profile:
 *
 * <pre>
 * xvfb-run -a -s "-screen 0 1600x1000x24" ./mvnw verify -Pgallery -Dnvim.path=...   (Linux)
 * ./mvnw verify -Pgallery -Dnvim.path=...                                           (macOS, Windows)
 * </pre>
 *
 * The files land in {@code tests/...tests/target/gallery/}. Each platform draws its own fonts and
 * spacing, so the gallery is taken once on each.
 *
 * <p>On macOS the look also follows the SDK the test's Java was built with, and the user's
 * accessibility settings. With <i>Show borders</i> on (Accessibility, Display), a Java built for macOS 26 or later
 * (Homebrew's openjdk, for one) gets AppKit's outline around each link (an SWT Link is a text view
 * inside a scroll view on macOS), so the preference-page pictures show a box around the help and
 * Reinstall.... Eclipse.app's own Java, built for an older SDK, draws none with the same setting,
 * and with the setting off neither does (checked with <i>Increase contrast</i> on, which only darkens the frame). SWT does nothing for
 * either setting on macOS ({@code Display.getHighContrast()} returns false there). The box is expected then, not a fault of
 * the plug-in.
 *
 * <h2>How a picture is taken</h2>
 * By {@link GalleryCamera}: the window draws itself into an image on every system, so nothing is
 * read from the screen and the window need not be in front. A picture of one color fails the run.
 *
 * <h2>The states shown</h2>
 * Dialogs are built from the same texts the plug-in shows ({@link GetNeovimText},
 * {@link StartFailureReport}, {@link OldNeovimProgram}), with the buttons their callers give
 * ({@code prefs/GetNeovim}, {@code editor/StartFailureNotice}); a change of buttons there must be
 * copied here. The preference page's Neovim group is a {@link NeovimStatusSection} given the value
 * of the option to show, as {@code NeovimPreferencePageTest} does: this test Eclipse runs with one
 * fixed {@code -Dneovim4eclipse.nvim}, and the blank value finds no dedicated Neovim (the UI tests'
 * data folder is their own, see the test pom's {@code XDG_*}).
 */
@Tag("gallery")
class ChecksGalleryTest {

    /** Width of the window a Neovim group is shown in, as on the preference page. */
    private static final int GROUP_WIDTH = 640;

    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    /** One picture: the topic it belongs to, its file name, and what the person checks in it. */
    private record Shot(String topic, String file, String title, String lookFor) {
    }

    private final Path out = Path.of(System.getProperty("gallery.dir", "target/gallery"));
    private final List<Shot> shots = new ArrayList<>();

    /**
     * Files the pictures need (a stand-in program, a folder), deleted afterwards. Made by hand: the
     * fragment's manifest does not import JUnit's {@code @TempDir} package. In
     * {@link PicturesFolder}, not the system's temp folder, which on Windows is under the account's
     * own folder: the refused-program pictures show these paths.
     */
    private Path temp;

    @Test
    void takeTheGallery() throws IOException {
        GalleryCamera.clear(out, "[0-9]{2}-.*", "index.html"); // no earlier run's pictures pass for this one's
        Files.createDirectories(out);
        temp = PicturesFolder.newTemp("n4e-gallery");
        try {
            takeEach();
        } finally {
            deleteTree(temp);
        }
    }

    private void takeEach() throws IOException {
        NeovimRelease release = NeovimRelease.bundled();
        NeovimPlatform platform = NeovimPlatform.current().orElseThrow(
                () -> new AssertionError("no Neovim build for this platform: nothing to show"));
        NeovimRelease.Archive archive = release.forPlatform(platform).orElseThrow();
        Path home = NvimExecutable.dedicatedFolder().resolve(platform.code());
        Path program = home.resolve("bin").resolve(WINDOWS ? "nvim.exe" : "nvim");
        Resolved noDedicated = new Resolved(program.toString(), Kind.NO_DEDICATED);

        // Get Neovim, opened by the plug-in at Eclipse's start (prefs/GetNeovimOffer).
        dialog("start-offer", "Get Neovim at a fresh start", GetNeovimText.TITLE, MessageDialog.QUESTION,
                GetNeovimText.confirm(release, archive, home,
                        StartFailureReport.getNeovimInstead(noDedicated, Optional.empty()).orElseThrow()),
                GetNeovimText.CONFIRM_BUTTONS,
                "Why first: not installed, will only run with the dedicated Neovim. Instead last, with two Copy "
                        + "fields (command line, eclipse.ini line) and \"so be sure it is Neovim\". Rows a little "
                        + "apart, nothing cut off.");
        dialog("files-differ", "Get Neovim when the files differ", GetNeovimText.TITLE, MessageDialog.QUESTION,
                GetNeovimText.confirm(release, archive, home, StartFailureReport.getNeovimInstead(noDedicated,
                        Optional.of(new PluginNeovim.Unverified(program))).orElseThrow()),
                GetNeovimText.CONFIRM_BUTTONS, "Why says the files differ from the release and may be damaged.");

        // The preference page's Neovim group.
        // The note as it reads on a machine without the dedicated Neovim: given, as this one may have it.
        NvimProbe.Note none = NvimProbe.note(noDedicated, null, Optional.empty(), NvimExecutable.dedicatedFolder(),
                release.version());
        group("no-dedicated", "Preference page, no dedicated Neovim", "", value -> none,
                "Note in the error color: no dedicated Neovim yet, \"Get Neovim\" installs it into the folder. "
                        + "The Get Neovim button, then the help: the -vmargs command on a row of its own, in a "
                        + "read-only field with Copy, a little apart from the text above; the link "
                        + "\"Using a Neovim of your own\" right under it, in line with the field; then the last "
                        + "paragraph. No field for the program, no Browse.");
        // The dedicated Neovim installed for this one picture (a copy of the -Dnvim.path release), with no
        // option, as a user's Eclipse finds it; the option's value here would be -Dnvim.path itself.
        DedicatedNeovim.withOnlyTheDedicatedNeovim(() -> group("dedicated", "Preference page, dedicated Neovim checked",
                "", ChecksGalleryTest::withoutLeftovers,
                "Note in the normal color: Found: dedicated Neovim, checked, with its path; a Reinstall... link "
                        + "under it; no Get Neovim button."));
        // Get Neovim's done note, without and with the option (prefs/GetNeovim). A topic's pictures stay
        // together: the page starts a new heading whenever the topic changes.
        dialog("dedicated", "Get Neovim done", GetNeovimText.TITLE, MessageDialog.INFORMATION,
                GetNeovimText.done(release.version(), program, archive.sha256(), false, false), List.of("Close"),
                "The program's path and the SHA-256, each with Copy; the VirusTotal report link.");
        Path own = fakeNeovim(release);
        group("own-program", "Preference page, a program of your own", own.toString(), ChecksGalleryTest::withoutLeftovers,
                "Note in the normal color: the program, from -Dneovim4eclipse.nvim, unchecked and unsupported.");
        dialog("done-with-option", "Get Neovim done, with the option set", GetNeovimText.TITLE,
                MessageDialog.INFORMATION,
                GetNeovimText.done(release.version(), program, archive.sha256(), false, true), List.of("Close"),
                "An Option row: Eclipse still runs the program -Dneovim4eclipse.nvim names, until it is removed.");

        // Values the option refuses: the start notice and the page's note for each.
        Path folder = Files.createDirectories(temp.resolve("a folder"));
        Path missing = temp.resolve("missing").resolve("bin").resolve(WINDOWS ? "nvim.exe" : "nvim");
        refused("folder", folder.toString(), Kind.FOLDER, "a folder: name the program inside it");
        refused("bare-name", "nvim", Kind.NOT_ABSOLUTE, "not an absolute path");
        refused("missing", missing.toString(), Kind.MISSING, "no program at that path, with it to copy");

        // Leftovers in the plug-in's folder, listed under the note. Given, not found: most machines have
        // none, so every platform shows the same two, under an error (missing program) to show the colors.
        Path neovim = NvimExecutable.dedicatedFolder();
        // Named as real leftovers are: the archive's name and the version, as 0.2.0 extracted them.
        String extracted = archive.asset().replaceFirst("\\.(tar\\.gz|zip)$", "") + "_" + release.version();
        String leftovers = NvimProbe.unusedLines(List.of("current", extracted), neovim);
        group("leftovers", "Preference page, with leftovers in the Neovim folder", missing.toString(),
                value -> NvimProbe.describe(value).withUnused(leftovers),
                "The note in the error color; under it, in the normal color, \"Not used by the plug-in, you can "
                        + "delete:\" and the two entries, one full path a line.");

        // The note about the removed preference (editor/OldNeovimProgramNotice), with old values as
        // this platform spells them: a Unix path on a Windows picture would show a value no Windows
        // user has stored.
        String oldValue = WINDOWS ? "C:\\Neovim\\bin\\nvim.exe" : "/usr/local/bin/nvim";
        String oldValueWithSpace = WINDOWS ? "C:\\Program Files\\Neovim\\bin\\nvim.exe" : "/opt/my tools/nvim/bin/nvim";
        dialog("old-setting", "Old preference note", OldNeovimProgram.TITLE, MessageDialog.INFORMATION,
                OldNeovimProgram.message(oldValue, "", WINDOWS).orElseThrow(), OldNeovimProgram.BUTTONS,
                "Intro: the dedicated Neovim runs, most setups need nothing more. Was with the old value to "
                        + "copy; Instead \"Only if you named that program yourself and need it\", with the command "
                        + "line and the eclipse.ini line; \"runs unchecked and unsupported\" and \"This note shows "
                        + "once\" last.");
        dialog("old-setting", "Old preference note, a path with a space", OldNeovimProgram.TITLE,
                MessageDialog.INFORMATION,
                OldNeovimProgram.message(oldValueWithSpace, "", WINDOWS).orElseThrow(),
                OldNeovimProgram.BUTTONS,
                "The command line is quoted (\"" + NeovimOption.iniLine(oldValueWithSpace) + "\"); the "
                        + "eclipse.ini line is not.");

        Files.writeString(out.resolve("index.html"), GalleryPage.html(platformName(), shots, out),
                StandardCharsets.UTF_8);
    }

    /** The "Neovim not started" notice and the preference page's note for a value the option refuses. */
    private void refused(String name, String value, Kind kind, String reason) throws IOException {
        // As editor/StartFailureNotice shows it, with the dedicated Neovim installed (so no Get Neovim button).
        dialog("refused", "Neovim not started: " + name, StartFailureReport.TITLE, MessageDialog.ERROR,
                StartFailureReport.message(new Resolved(value, kind), new IOException("Cannot run program"),
                        List.of(), Optional.empty(), true, path -> null),
                List.of("Open Preferences", "Close"),
                "Program row with the value to copy; Reason: " + reason + "; Fix: remove -Dneovim4eclipse.nvim.");
        group("refused", "Preference page: " + name, value, ChecksGalleryTest::withoutLeftovers,
                "Note in the error color: " + reason + ".");
    }

    /** Opens a dialog as the plug-in does, pictures it, and closes it. */
    private void dialog(String topic, String title, String shellTitle, int kind, HeadedMessage message,
            List<String> buttons, String lookFor) {
        HeadedMessageDialog dialog = new HeadedMessageDialog(null, shellTitle, kind, message, 0,
                buttons.toArray(String[]::new));
        dialog.setBlockOnOpen(false);
        dialog.open();
        try {
            save(dialog.getShell(), topic, title, lookFor);
        } finally {
            dialog.close();
        }
    }

    /** A Neovim group as the preference page lays it out, for the option's value {@code configured}. */
    private void group(String topic, String title, String configured, Function<String, NvimProbe.Note> describe,
            String lookFor) {
        Shell window = new Shell(Display.getCurrent());
        try {
            window.setLayout(new GridLayout());
            Group group = new Group(window, SWT.NONE);
            group.setText("Neovim");
            group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
            group.setLayout(new GridLayout());
            new NeovimStatusSection(group, () -> configured, describe, new WorkbenchSectionActions());
            window.setSize(GROUP_WIDTH, 600); // the width the note and the help wrap in; the height is fitted below
            window.open();
            // The note is probed off the UI thread; it reads "Looking for Neovim..." until it answers.
            GalleryCamera.waitUntil("the Neovim group's note for " + title, () -> !looking(group));
            // The answer changed the note's text and hid or showed rows. GTK measures the new text only once
            // its event loop has run (until then a label reports its old size), and the rows move only when
            // the layout requested runs: run both first, then lay out afresh with the caches dropped
            // (changed = true). Without this the window kept the height of "Looking for Neovim..." and the
            // picture showed that note, its Get Neovim button and all.
            GalleryCamera.drain(window.getDisplay());
            window.layout(true, true);
            // Fit the height to the wrapped text: computeSize takes the inside's width, so the note and the
            // help wrap as at GROUP_WIDTH (pack() would measure them unwrapped, one long line each).
            window.setSize(GROUP_WIDTH, window.computeSize(window.getClientArea().width, SWT.DEFAULT, true).y);
            GalleryCamera.drain(window.getDisplay());
            window.layout(true, true);
            // Active, as a user sees the page (GalleryCamera.activate: only while the test Eclipse is the
            // active program), with the focus on the window itself, not its first link: a focused link
            // draws a focus rectangle, which the picture would show (activating gives it focus).
            GalleryCamera.activate(window);
            window.forceFocus();
            save(window, topic, title, lookFor);
        } finally {
            window.dispose();
        }
    }

    /**
     * Draws {@code shell}'s inside into a PNG named after the topic and its place in the gallery, for
     * example {@code 03-no-dedicated.png}.
     */
    private void save(Shell shell, String topic, String title, String lookFor) {
        shell.layout(true, true);
        String file = String.format("%02d-%s.png", shots.size() + 1, topic);
        GalleryCamera.save(shell, shell.getClientArea(), out.resolve(file), title);
        shots.add(new Shot(topic, file, title, lookFor));
    }




    /**
     * A stand-in for a Neovim of the user's own: a script that answers {@code --version} as the
     * offered Neovim does, so the note reports a program, in the normal color, without running a real
     * one. Example output: {@code NVIM v0.12.6}.
     */
    private Path fakeNeovim(NeovimRelease release) throws IOException {
        String version = "NVIM v" + release.version();
        if (WINDOWS) {
            return Files.writeString(temp.resolve("nvim.cmd"), "@echo " + version + "\r\n");
        }
        Path script = Files.writeString(temp.resolve("nvim"), "#!/bin/sh\necho '" + version + "'\n");
        if (!script.toFile().setExecutable(true)) {
            throw new IOException("cannot make " + script + " executable");
        }
        return script;
    }

    /** Deletes {@code root} and what it holds, deepest first; only the few files this test made. */
    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String platformName() {
        return System.getProperty("os.name") + " " + System.getProperty("os.arch");
    }

    /**
     * The note as the page finds it, without this machine's leftovers in the plug-in's folder: they
     * differ from machine to machine, so only the leftovers picture lists them, and it lists the
     * same two on every machine (otherwise, on a machine with leftovers, every page picture lists them
     * and the leftovers picture matches another one byte for byte).
     */
    private static NvimProbe.Note withoutLeftovers(String configured) {
        return NvimProbe.describe(configured).withUnused(null);
    }

    /** Whether a note still says it is looking (its probe has not answered). */
    private static boolean looking(Control control) {
        if (control instanceof Label label && label.getText().startsWith("Looking for")) {
            return true;
        }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                if (looking(child)) {
                    return true;
                }
            }
        }
        return false;
    }



    /**
     * The gallery's {@code index.html}: each picture under its topic, with what to look for. A plain,
     * self-contained page (styles inline, pictures inside it as {@code data:} URIs, see
     * {@link GalleryCamera#dataUri}), readable in a browser or an app's file pane and publishable as
     * it is.
     */
    static final class GalleryPage {

        private GalleryPage() {
        }

        static String html(String platform, List<Shot> shots, Path dir) throws IOException {
            StringBuilder body = new StringBuilder();
            String topic = null;
            for (Shot shot : shots) {
                if (!shot.topic().equals(topic)) {
                    topic = shot.topic();
                    body.append("<h2><code>").append(escape(topic)).append("</code></h2>\n");
                }
                body.append("<figure><figcaption><b>").append(escape(shot.title())).append("</b> ")
                        .append(escape(shot.lookFor())).append("</figcaption>").append(GalleryCamera.img(dir.resolve(shot.file()), escape(shot.title())))
                        .append("</figure>\n");
            }
            return """
                    <!doctype html>
                    <html lang="en"><head><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>Neovim for Eclipse Checks Gallery</title>
                    <style>
                    :root { --bg: #f7f7f4; --fg: #1d1f1c; --muted: #5d6259; --line: #d9dbd3; --card: #ffffff; }
                    @media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
                      --bg: #171916; --fg: #e8e9e4; --muted: #a3a89d; --line: #33362f; --card: #20231f; color-scheme: dark } }
                    :root[data-theme="dark"] { --bg: #171916; --fg: #e8e9e4; --muted: #a3a89d; --line: #33362f;
                      --card: #20231f; color-scheme: dark }
                    body { background: var(--bg); color: var(--fg); font: 15px/1.5 system-ui, sans-serif; margin: 0;
                      padding-block: 24px; padding-left: max(16px, env(safe-area-inset-left, 0px));
                      padding-right: max(16px, env(safe-area-inset-right, 0px)); }
                    main { max-width: 760px; margin: 0 auto; }
                    h1 { font-size: 1.5rem; margin: 0 0 4px; text-wrap: balance; }
                    h2 { font-size: 1rem; margin: 32px 0 8px; border-top: 1px solid var(--line); padding-top: 16px; }
                    p.sub { color: var(--muted); margin: 0; }
                    figure { margin: 0 0 20px; background: var(--card); border: 1px solid var(--line);
                      border-radius: 6px; padding: 12px; }
                    figcaption { margin-bottom: 10px; max-width: 65ch; }
                    img { display: block; max-width: 100%; height: auto; border: 1px solid var(--line); }
                    code { font: 0.95em ui-monospace, monospace; }
                    </style></head><body><main>
                    <h1>Neovim for Eclipse checks gallery</h1>
                    <p class="sub">Taken on {platform} by ChecksGalleryTest (-Pgallery).</p>
                    <p class="sub">Search highlighting, light and dark: <a href="search.html">search.html</a> (SearchGalleryTest).</p>
                    {body}</main></body></html>
                    """.replace("{platform}", escape(platform)).replace("{body}", body); // not formatted(): the CSS has % signs
        }

        private static String escape(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        }
    }
}
