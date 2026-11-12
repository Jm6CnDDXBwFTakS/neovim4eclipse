// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package marketplace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import common.Ask;
import common.Folders;
import common.Git;
import common.Settings;
import common.Shell;
import common.ToolFailure;
import common.Version;

/**
 * The Marketplace tool: keeps the Eclipse Marketplace listing's material current, so a submission
 * or a refresh is copying from one page, not hunting for facts (docs/design/MARKETPLACE.md).
 * <pre>
 *   java releng/tools/marketplace/Marketplace.java              the listing page, and what changed since the last submission
 *   java releng/tools/marketplace/Marketplace.java pictures     take the screenshots and record the README demo (Linux)
 *   java releng/tools/marketplace/Marketplace.java logo         render the logo's PNGs from docs/images/logo.svg
 *   java releng/tools/marketplace/Marketplace.java submitted    keep the listing as submitted, after saving it on the website
 *   flags: --yes, --dry-run (as the Release tool)
 * </pre>
 *
 * <h2>The listing page</h2>
 * {@code releng/marketplace/listing.txt} (the form's fields, in form order) and
 * {@code description.md}, with every fact ({@code {neovim}}, {@code {java}}, ...) filled in from
 * its source of truth ({@link Facts}), become {@code target/marketplace/listing.html}: each field
 * with a Copy button, the description rendered and as HTML, the pictures, and a checklist
 * ({@link ListingPage}). The page also lists what changed since the copy in
 * {@code releng/marketplace/submitted/}, the fields to change on the website.
 *
 * <h2>pictures</h2>
 * Runs the {@code screenshots} test group ({@code ScreenshotCaptureTest} and
 * {@code DemoRecordingTest}) with the pinned Neovim under {@code xvfb-run}, so every run draws the
 * same Linux/GTK window at the same size, then joins the demo's pictures into
 * {@code target/marketplace/demo-new.gif} ({@link Gif}). The listing page shows it beside the
 * committed {@code demo.gif} until the two are the same. The three screenshots are not committed: the
 * listing takes them as uploads, and a picture in the repository stays in its history for good.
 * The demo is (the README shows it), after y/N, once you have looked at it: it is public. The
 * pictures' Eclipse keeps its files under {@code pictures.home} (the test pom), so no home folder
 * and with it no account name shows. Linux only: the listing shows Linux/GTK, and Xvfb gives a
 * screen of a known size.
 *
 * <h2>logo</h2>
 * {@code rsvg-convert} draws {@code docs/images/logo.svg} large (512 pixels), ImageMagick's
 * {@code magick} shrinks it with the Lanczos filter, into the toolbar icons (16 and 32 pixels; the
 * Eclipse-mode ones in grey) and the Marketplace logo (the logo on white at 110 by 80, the size
 * the form scales every logo to). Only {@code rsvg-convert}: ImageMagick's own SVG reader drops
 * strokes. The PNGs are written to {@code target/marketplace/logo/} first and committed after y/N
 * only when they differ.
 *
 * <h2>submitted</h2>
 * After saving the listing on the website: writes the filled-in listing to
 * {@code releng/marketplace/submitted/} and commits it after y/N. The next run's page and the
 * Release tool's {@code check} compare with it.
 */
public final class MarketplaceTool {

    public static final String TOOL = "releng/tools/marketplace/Marketplace.java";
    private static final String USAGE = "Usage: java " + TOOL + " [pictures | logo | submitted] [--yes] [--dry-run]";
    /** Where the page and its pictures go. */
    static final String OUT = "target/marketplace";
    static final String LOGO_SVG = "docs/images/logo.svg";
    static final String MARKETPLACE_LOGO = "docs/images/marketplace-logo.png";
    static final String DEMO = "docs/images/demo.gif";
    /** The font DemoRecordingTest uses, which must be installed for pictures. */
    static final String DEMO_FONT = "JetBrains Mono NL";
    /** Where pictures puts a new recording of the demo, in {@link #OUT}, apart from the committed one's copy. */
    static final String NEW_DEMO = "demo-new.gif";
    static final String ICONS = "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/icons/";
    /** The screenshots, in the order the listing shows them after the demo. */
    static final List<String> SCREENSHOTS = List.of("01-visual-mode.png", "02-command-line.png", "03-problems.png");
    private static final String TEST_FRAGMENT = "tests/io.github.jm6cnddxbwftaks.neovim4eclipse.tests";
    /** As {@code ScreenshotCaptureTest} wants it: room for its window, in full color (Xvfb's default is 640x480 at 8 bits). */
    private static final String SCREEN = "-screen 0 1600x1000x24";

    private final Path root;
    private final Shell shell;
    private final Git git;
    private final Settings settings;
    private final Ask ask;

    private MarketplaceTool(Path root, Ask ask) {
        this.root = root;
        this.shell = new Shell(root);
        this.git = new Git(shell);
        this.settings = Settings.load(root.resolve(Settings.COMMON), Map.of());
        this.ask = ask;
    }

    public static void main(String[] args) {
        try {
            Path root = Path.of("").toAbsolutePath();
            if (!Files.isRegularFile(root.resolve(TOOL))) {
                throw new ToolFailure("run this from the repository's root folder: java " + TOOL);
            }
            String command = "listing";
            Ask.Mode mode = Ask.Mode.ASK;
            for (String a : args) {
                switch (a) {
                    case "--yes" -> mode = mode == Ask.Mode.DRY_RUN ? mode : Ask.Mode.YES;
                    case "--dry-run" -> mode = Ask.Mode.DRY_RUN;
                    case "pictures", "logo", "submitted" -> {
                        if (!command.equals("listing")) {
                            throw new ToolFailure("one command at a time\n" + USAGE);
                        }
                        command = a;
                    }
                    default -> throw new ToolFailure("unknown argument " + a + "\n" + USAGE);
                }
            }
            String rerun = "java " + TOOL + (command.equals("listing") ? "" : " " + command) + " --yes";
            MarketplaceTool tool = new MarketplaceTool(root, new Ask(mode, rerun));
            switch (command) {
                case "pictures" -> tool.pictures();
                case "logo" -> tool.logo();
                case "submitted" -> tool.submitted();
                default -> tool.page();
            }
        } catch (ToolFailure f) {
            System.err.println();
            System.err.println("Marketplace: " + f.getMessage());
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- the listing

    /** The listing as the sources say now, facts filled in. */
    static Listing current(Path root) {
        return Listing.of(Facts.text(root, Listing.FIELDS), Facts.text(root, Listing.DESCRIPTION), Facts.read(root));
    }

    /** The listing as last submitted, if it has been. */
    public static Optional<Listing> submitted(Path root) {
        Path fields = root.resolve(Listing.SUBMITTED).resolve("listing.txt");
        if (!Files.isRegularFile(fields)) {
            return Optional.empty();
        }
        String folder = Listing.SUBMITTED + "/";
        return Optional.of(new Listing(Listing.parseFields(Facts.text(root, folder + "listing.txt")),
                Facts.text(root, folder + "description.md")));
    }

    /**
     * What to change on the website: {@link Listing#changesSince} the submitted copy, or one line
     * saying nothing was submitted yet. The Release tool's {@code check} prints the same.
     */
    public static List<String> changes(Path root) {
        Listing now = current(root);
        return submitted(root).map(now::changesSince)
                .orElse(List.of("nothing submitted yet: every field is new (after saving the listing: java " + TOOL + " submitted)"));
    }

    /** Writes {@code target/marketplace/listing.html} with the logo and the pictures there are. */
    private void page() {
        Listing listing = current(root);
        List<String> changes = changes(root);
        Path out = root.resolve(OUT);
        // Copies of the committed files, made afresh each run so the page never shows an old one.
        copy(root.resolve(MARKETPLACE_LOGO), out.resolve("marketplace-logo.png"));
        copy(root.resolve(DEMO), out.resolve("demo.gif"));
        List<String> images = new ArrayList<>(List.of("marketplace-logo.png", "demo.gif"));
        Path recorded = out.resolve(NEW_DEMO);
        if (Files.isRegularFile(recorded) && !sameBytes(recorded, root.resolve(DEMO))) {
            images.add(NEW_DEMO); // a recording not committed (yet): shown, so it can be compared
        }
        SCREENSHOTS.stream().filter(s -> Files.isRegularFile(out.resolve(s))).forEach(images::add);
        Path page = out.resolve("listing.html");
        write(page, ListingPage.html(listing, images, changes));
        System.out.println("The listing page: " + page.toUri());
        if (SCREENSHOTS.stream().anyMatch(s -> !images.contains(s))) {
            System.out.println("note: no screenshots yet; take them with: java " + TOOL + " pictures (Linux)");
        }
        System.out.println();
        System.out.println("Since the last submission:");
        changes.forEach(c -> System.out.println("  " + c));
    }

    // ---------------------------------------------------------------- pictures

    private void pictures() {
        if (!System.getProperty("os.name").startsWith("Linux")) {
            throw new ToolFailure("pictures runs on Linux only: the listing shows Linux/GTK, and Xvfb gives a screen of a known size");
        }
        if (!Shell.onPath("xvfb-run")) {
            throw new ToolFailure("no xvfb-run on PATH; on Debian or Ubuntu: sudo apt install xvfb");
        }
        // DemoRecordingTest draws the demo in this font; fontconfig would quietly put another in its place.
        if (!Shell.onPath("fc-match") || !shell.out(List.of("fc-match", DEMO_FONT)).contains(DEMO_FONT)) {
            throw new ToolFailure("the demo's font, " + DEMO_FONT + ", is not installed; on Debian or Ubuntu: sudo apt install"
                    + " fonts-jetbrains-mono, elsewhere https://www.jetbrains.com/lp/mono/");
        }
        // Before the long run: the demo is committed at the end, on a work branch.
        git.checkWorkBranch("record the demo", Version.inPom(Facts.text(root, "pom.xml")));
        Path shots = root.resolve(TEST_FRAGMENT).resolve("target/screenshots");
        if (ask.mode() == Ask.Mode.DRY_RUN) {
            System.out.println("[dry run] would install the pinned Neovim into target/like-ci/ (as LikeCi does), then run: "
                    + Shell.display(mavenUnderXvfb("<pinned nvim>")));
            System.out.println("[dry run] then join " + shots.resolve("demo") + " into " + OUT + "/" + NEW_DEMO + " and offer to commit it as " + DEMO);
            return;
        }
        String nvim = pinnedNeovim();
        Folders.deleteTree(shots);
        List<String> maven = mavenUnderXvfb(nvim);
        System.out.println(Shell.display(maven));
        if (shell.show(maven) != 0) {
            throw new ToolFailure("the screenshots run failed (see above)");
        }
        Path out = root.resolve(OUT);
        for (String s : SCREENSHOTS) {
            copy(shots.resolve(s), out.resolve(s));
        }
        Path gif = out.resolve(NEW_DEMO);
        Gif.write(Gif.readFrames(shots.resolve("demo")), gif);
        System.out.println("ok: " + OUT + "/" + NEW_DEMO + ", " + size(gif) + " (the committed one: " + size(root.resolve(DEMO)) + ")");
        page();
        System.out.println();
        if (sameBytes(gif, root.resolve(DEMO))) {
            System.out.println("ok: " + DEMO + " is this recording already");
            return;
        }
        if (!git.isClean()) {
            // The pictures are made; only the commit needs a clean tree (Git#editAndCommit).
            System.out.println("note: the working tree has changes, so " + DEMO + " was left as it is; to take the new one, look at "
                    + gif.toUri() + " and copy it: cp " + OUT + "/" + NEW_DEMO + " " + DEMO);
            return;
        }
        commit("Replace " + DEMO + " with " + OUT + "/" + NEW_DEMO + " and commit; look at it first, as it is public: "
                + gif.toUri(), List.of("cp " + OUT + "/" + NEW_DEMO + " " + DEMO),
                "Record the README demo again", () -> {
                    copy(gif, root.resolve(DEMO));
                    return List.of(DEMO);
                });
    }

    /** The screenshots run: {@code ./mvnw verify -Pscreenshots} under {@code xvfb-run}, with {@code nvim}. */
    private static List<String> mavenUnderXvfb(String nvim) {
        List<String> maven = new ArrayList<>(List.of("xvfb-run", "-a", "-s", SCREEN));
        maven.addAll(Shell.maven("-B", "-ntp", "verify", "-Pscreenshots", "-Dnvim.path=" + nvim));
        return maven;
    }

    /** The pinned Neovim, installed as {@code LikeCi} installs it (into {@code target/like-ci/}); its program's path. */
    private String pinnedNeovim() {
        List<String> install = Shell.java("releng/UpdateNeovimDownloads.java", "install", "auto", root.resolve("target/like-ci").toString());
        String out = shell.out(install).strip();
        return out.lines().reduce((a, b) -> b).orElseThrow(() -> new ToolFailure("the Neovim install printed no path"));
    }

    // ---------------------------------------------------------------- logo

    /** A PNG the logo command makes: where it goes, its size, whether grey. */
    record Icon(String file, int size, boolean grey) {
    }

    /** The toolbar icons: the logo in color for Neovim mode, in grey for Eclipse mode (util/ToggleButtonState). */
    static final List<Icon> ICON_FILES = List.of(
            new Icon(ICONS + "n4e-mode-on.png", 16, false), new Icon(ICONS + "n4e-mode-on@2x.png", 32, false),
            new Icon(ICONS + "n4e-mode-off.png", 16, true), new Icon(ICONS + "n4e-mode-off@2x.png", 32, true));

    /** {@code magick} shrinking {@code big} to {@code icon}'s size, keeping transparency, no time stamps in the file. */
    static List<String> iconCommand(String big, Icon icon, String out) {
        List<String> c = new ArrayList<>(List.of("magick", big, "-filter", "Lanczos", "-resize", icon.size() + "x" + icon.size()));
        if (icon.grey()) {
            c.addAll(List.of("-colorspace", "Gray"));
        }
        c.addAll(List.of("-strip", "PNG32:" + out));
        return c;
    }

    /** {@code magick} putting the 76-pixel logo centered on white at 110 by 80, the form's logo size. */
    static List<String> marketplaceLogoCommand(String small, String out) {
        return List.of("magick", small, "-gravity", "center", "-background", "white", "-extent", "110x80",
                "-alpha", "off", "-depth", "8", "-strip", out);
    }

    private void logo() {
        for (String program : List.of("rsvg-convert", "magick")) {
            if (!Shell.onPath(program)) {
                throw new ToolFailure("no " + program + " on PATH; on Debian or Ubuntu: sudo apt install librsvg2-bin imagemagick;"
                        + " on macOS: brew install librsvg imagemagick");
            }
        }
        Path work = root.resolve(OUT).resolve("logo");
        Folders.deleteTree(work);
        mkdirs(work);
        String big = work.resolve("logo-512.png").toString();
        String small = work.resolve("logo-76.png").toString();
        run(List.of("rsvg-convert", "-w", "512", "-h", "512", "-o", big, LOGO_SVG));
        run(List.of("rsvg-convert", "-w", "76", "-h", "76", "-b", "white", "-o", small, LOGO_SVG));
        List<String[]> made = new ArrayList<>(); // rendered file, its place in the repository
        for (Icon icon : ICON_FILES) {
            String out = work.resolve(Path.of(icon.file()).getFileName()).toString();
            run(iconCommand(big, icon, out));
            made.add(new String[] {out, icon.file()});
        }
        String logo = work.resolve("marketplace-logo.png").toString();
        run(marketplaceLogoCommand(small, logo));
        made.add(new String[] {logo, MARKETPLACE_LOGO});

        List<String[]> changed = made.stream().filter(m -> !sameBytes(Path.of(m[0]), root.resolve(m[1]))).toList();
        if (changed.isEmpty()) {
            System.out.println("ok: the committed PNGs are what " + LOGO_SVG + " renders to");
            return;
        }
        git.checkWorkBranch("commit the logo's PNGs", Version.inPom(Facts.text(root, "pom.xml")));
        requireClean();
        List<String> plan = changed.stream().map(m -> "cp " + root.relativize(Path.of(m[0])) + " " + m[1]).toList();
        commit("Copy the new PNGs (look at them first, in " + work.toUri() + ") and commit", plan,
                "Render the logo's PNGs again from " + LOGO_SVG, () -> {
                    for (String[] m : changed) {
                        copy(Path.of(m[0]), root.resolve(m[1]));
                    }
                    return changed.stream().map(m -> m[1]).toList();
                });
    }

    // ---------------------------------------------------------------- submitted

    private void submitted() {
        Listing listing = current(root);
        List<String> changes = changes(root);
        if (submitted(root).isPresent() && changes.isEmpty()) {
            System.out.println("ok: " + Listing.SUBMITTED + " already holds this listing");
            return;
        }
        git.checkWorkBranch("keep the submitted listing", Version.inPom(Facts.text(root, "pom.xml")));
        requireClean();
        String header = """
                # SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
                # SPDX-License-Identifier: EPL-2.0
                #
                # The Marketplace listing as last saved on marketplace.eclipse.org, facts filled in
                # (java releng/tools/marketplace/Marketplace.java submitted). The tool compares the
                # listing with this copy and lists the fields to change on the website.

                """;
        commit("Keep the listing as submitted in " + Listing.SUBMITTED + "/ and commit", changes,
                "Keep the Marketplace listing as submitted", () -> {
                    mkdirs(root.resolve(Listing.SUBMITTED));
                    write(root.resolve(Listing.SUBMITTED).resolve("listing.txt"), header + listing.fieldsText());
                    write(root.resolve(Listing.SUBMITTED).resolve("description.md"), listing.description());
                    return List.of(Listing.SUBMITTED + "/listing.txt", Listing.SUBMITTED + "/description.md");
                });
    }

    // ---------------------------------------------------------------- shared

    private void commit(String what, List<String> plan, String message, java.util.function.Supplier<List<String>> edit) {
        String trailer = settings.get("commit.trailer", "");
        git.editAndCommit(ask, what, plan, trailer.isEmpty() ? message : message + "\n\n" + trailer, edit);
    }

    private void requireClean() {
        if (!git.isClean()) {
            throw new ToolFailure("the working tree has changes (git status); commit or stash them first");
        }
    }

    private void run(List<String> command) {
        if (shell.show(command) != 0) {
            throw new ToolFailure(Shell.display(command) + " failed (see above)");
        }
    }

    private static boolean sameBytes(Path a, Path b) {
        try {
            return Files.isRegularFile(b) && Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b));
        } catch (IOException e) {
            throw new ToolFailure("cannot compare " + a + " with " + b + ": " + e.getMessage());
        }
    }

    private static void copy(Path from, Path to) {
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ToolFailure("cannot copy " + from + " to " + to + ": " + e.getMessage());
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolFailure("cannot write " + file + ": " + e.getMessage());
        }
    }

    private static void mkdirs(Path folder) {
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new ToolFailure("cannot make " + folder + ": " + e.getMessage());
        }
    }

    private static String size(Path file) {
        try {
            return (Files.size(file) + 1023) / 1024 + " KB";
        } catch (IOException e) {
            return "size unknown";
        }
    }
}
