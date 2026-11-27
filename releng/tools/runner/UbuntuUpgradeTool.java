// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import common.Ask;
import common.Git;
import common.Http;
import common.Settings;
import common.Shell;
import common.ToolFailure;
import common.Version;
import common.VersionCopies;

/**
 * The Ubuntu tool: shows, and moves, the Ubuntu release CI's Linux jobs run on, {@code <ci.ubuntu>}
 * in the root {@code pom.xml} with every {@code runs-on} that copies it, in one commit
 * (docs/UPGRADING.md, "GitHub Actions and the runner").
 * <pre>
 *   java releng/tools/runner/UbuntuUpgrade.java          the release in use, and the ones GitHub offers
 *   java releng/tools/runner/UbuntuUpgrade.java 28.04    move CI to Ubuntu 28.04
 *   flags: --yes, --dry-run (as the Release tool)
 * </pre>
 *
 * <h2>Why a tool</h2>
 * GitHub moves {@code ubuntu-latest} to a new Ubuntu on a day of its choosing (docs/UPGRADING.md
 * says when it last did), and a new Ubuntu brings other fonts, GTK and Pango, which can
 * change what the UI tests measure. Pinned, the move is a commit like any other: made on a branch,
 * its Build compared with the last one, and undone by moving back. The label stands in every
 * workflow's {@code runs-on}, which cannot read a Maven property; the tool rewrites them all.
 *
 * <h2>The steps</h2>
 * <ol>
 *   <li>Reads GitHub's list of runner images ({@link UbuntuFiles#README}) for the x64 Ubuntu
 *       releases and whether each is still a beta ({@link UbuntuFiles#images}). Without a release,
 *       it stops there: the release in use, the ones offered, and the command to move.</li>
 *   <li>With one, checks it ({@link UbuntuFiles#checkTarget}): listed, released, another than
 *       today's. Then, after y/N, the edits: {@code <ci.ubuntu>} and every copy that
 *       {@code releng/version-copies.txt} lists under {@code [ci.ubuntu]}. Only those files may
 *       change. The commit <i>Move CI to Ubuntu 28.04</i>.</li>
 *   <li>It pushes nothing and starts no run: what proves a move is a Build on GitHub's new runner,
 *       which no local run can stand in for. It prints those steps instead.</li>
 * </ol>
 * No changelog entry of its own, unlike the Java tool's: a move of the runner changes nothing a user
 * of the plug-in installs or notices (the pin itself has its entry, for contributors).
 */
public final class UbuntuUpgradeTool {

    static final String TOOL = "releng/tools/runner/UbuntuUpgrade.java";
    private static final String USAGE = "Usage: java " + TOOL + " [<release such as 28.04>] [--yes] [--dry-run]";

    private final Path root;
    private final Git git;
    private final Settings settings;
    private final Ask ask;

    private UbuntuUpgradeTool(Path root, Ask ask) {
        this.root = root;
        this.git = new Git(new Shell(root));
        this.settings = Settings.load(List.of(root.resolve(Settings.COMMON)), Map.of());
        this.ask = ask;
    }

    public static void main(String[] args) {
        Shell.requireSupportedShell("UbuntuUpgrade");
        try {
            Path root = Path.of("").toAbsolutePath();
            if (!Files.isRegularFile(root.resolve(TOOL))) {
                throw new ToolFailure("run this from the repository's root folder: java " + TOOL);
            }
            String release = null;
            Ask.Mode mode = Ask.Mode.ASK;
            List<String> rerun = new ArrayList<>(List.of("java", TOOL));
            for (String a : args) {
                switch (a) {
                    case "--yes" -> mode = mode == Ask.Mode.DRY_RUN ? mode : Ask.Mode.YES;
                    case "--dry-run" -> mode = Ask.Mode.DRY_RUN;
                    default -> {
                        if (a.startsWith("-") || release != null || !UbuntuFiles.isVersion(a)) {
                            throw new ToolFailure("unknown argument " + a + "\n" + USAGE);
                        }
                        release = a;
                        rerun.add(a);
                    }
                }
            }
            rerun.add("--yes");
            UbuntuUpgradeTool tool = new UbuntuUpgradeTool(root, new Ask(mode, Shell.display(rerun)));
            if (release == null) {
                tool.status();
            } else {
                tool.move(release);
            }
        } catch (ToolFailure f) {
            System.err.println();
            System.err.println("UbuntuUpgrade: " + f.getMessage());
            System.exit(1);
        }
    }

    /** The release in use, the ones GitHub offers, and what to run next. */
    private void status() {
        String now = UbuntuFiles.versionIn(read("pom.xml"));
        List<UbuntuFiles.Image> images = images();
        System.out.println("CI's Linux jobs run on Ubuntu " + now + " (<" + UbuntuFiles.PROPERTY + "> in pom.xml)");
        System.out.println("GitHub offers (x64, " + UbuntuFiles.README_PAGE + "):");
        for (UbuntuFiles.Image i : images) {
            System.out.println("  ubuntu-" + i.version() + (i.beta() ? "  beta" : "") + (i.version().equals(now) ? "  <- in use" : ""));
        }
        if (images.stream().noneMatch(i -> i.version().equals(now))) {
            System.out.println("warning: GitHub no longer lists ubuntu-" + now + ": a retired image's jobs stop running;"
                    + " move to a listed release");
        }
        Optional<UbuntuFiles.Image> newest = UbuntuFiles.newestReleased(images);
        if (newest.isPresent() && newest.get().version().compareTo(now) > 0) {
            System.out.println();
            System.out.println("Ubuntu " + newest.get().version() + " is released: java " + TOOL + " " + newest.get().version()
                    + " (docs/UPGRADING.md, \"GitHub Actions and the runner\")");
        } else {
            System.out.println();
            System.out.println("ok: no newer released Ubuntu");
        }
    }

    /** Moves {@code <ci.ubuntu>} and its copies to {@code to}, in one commit. */
    private void move(String to) {
        git.checkWorkBranch("move CI to another Ubuntu", Version.inPom(read("pom.xml")));
        if (!git.isClean()) {
            throw new ToolFailure("the working tree has changes (git status); commit or stash them first");
        }
        String from = UbuntuFiles.versionIn(read("pom.xml"));
        UbuntuFiles.checkTarget(from, to, images());
        System.out.println("ok: GitHub lists ubuntu-" + to + " as released" + (to.compareTo(from) < 0 ? " (lower than " + from + ": back to it)" : ""));
        List<VersionCopies.Copy> copies = VersionCopies.parse(read(VersionCopies.FILE)).get(UbuntuFiles.PROPERTY);
        if (copies == null || copies.isEmpty()) {
            throw new ToolFailure(VersionCopies.FILE + " has no [" + UbuntuFiles.PROPERTY + "] section");
        }
        Set<String> files = new LinkedHashSet<>(List.of("pom.xml"));
        copies.forEach(c -> files.add(c.file()));
        List<String> plan = new ArrayList<>();
        plan.add("pom.xml: <" + UbuntuFiles.PROPERTY + ">" + from + "</" + UbuntuFiles.PROPERTY + "> to " + to);
        for (VersionCopies.Copy c : copies) {
            plan.add(c.file() + ": " + c.pattern() + ", " + from + " to " + to);
        }
        String message = "Move CI to Ubuntu " + to;
        String trailer = settings.get("commit.trailer", "");
        boolean committed = git.editAndCommit(ask, message + " (from " + from + "), then commit", plan,
                trailer.isEmpty() ? message : message + "\n\n" + trailer, () -> {
                    edit(from, to, copies);
                    List<String> changed = git.out("diff", "--name-only", "HEAD").lines().toList();
                    List<String> others = changed.stream().filter(f -> !files.contains(f)).toList();
                    if (!others.isEmpty()) {
                        throw new ToolFailure("files other than the Ubuntu release's copies changed: " + others);
                    }
                    return changed;
                });
        if (!committed) {
            return; // a dry run
        }
        System.out.println();
        System.out.println("What stays yours (docs/UPGRADING.md, \"GitHub Actions and the runner\"); nothing is pushed or run:");
        System.out.println("  - Push the branch and run the Build on it (ask before a run), on ubuntu-" + to);
        System.out.println("  - Compare its step \"Runner's text stack\" with the last Build on ubuntu-" + from
                + ": a font or a GTK, Pango or cairo version that changed is the first suspect for a UI test that now fails");
        System.out.println("  - Move the Ubuntu VM for local runs to " + to + " (CONTRIBUTING.md, \"Like CI, on Ubuntu\"),"
                + " and install the fonts the new print lists");
        System.out.println("  - If the Build fails for the runner alone, go back: java " + TOOL + " " + from);
    }

    /** The edits: the property and each listed copy, each found holding {@code from} first. */
    private void edit(String from, String to, List<VersionCopies.Copy> copies) {
        write("pom.xml", UbuntuFiles.withVersion(read("pom.xml"), to));
        for (VersionCopies.Copy c : copies) {
            String text = read(c.file());
            List<String> found = VersionCopies.versionsIn(text, c);
            if (found.isEmpty()) {
                throw new ToolFailure(c.file() + ": nothing matches " + c.pattern() + " (" + VersionCopies.FILE + ")");
            }
            if (found.stream().anyMatch(v -> !v.equals(from))) {
                throw new ToolFailure(c.file() + ": " + c.pattern() + " finds " + found + ", not " + from + "; run VersionConsistencyTest first");
            }
            write(c.file(), VersionCopies.rewrite(text, c, to));
        }
        System.out.println("ok: Ubuntu " + to + " in pom.xml and " + copies.size() + " workflows");
    }

    /** GitHub's x64 Ubuntu releases, from the runner-images README. */
    private static List<UbuntuFiles.Image> images() {
        return UbuntuFiles.images(Http.text(UbuntuFiles.README));
    }

    private String read(String file) {
        try {
            return Files.readString(root.resolve(file));
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + file + ": " + e.getMessage());
        }
    }

    private void write(String file, String text) {
        try {
            Files.writeString(root.resolve(file), text);
        } catch (IOException e) {
            throw new ToolFailure("cannot write " + file + ": " + e.getMessage());
        }
    }
}
