// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package runner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import common.ToolFailure;

/**
 * The Ubuntu tool's rules and text edits, as plain functions, so that {@code tests/unit} checks them
 * without a network or a checkout to change ({@code UbuntuUpgradeTest}).
 *
 * <h2>The Ubuntu release</h2>
 * {@code <ci.ubuntu>} in the root {@code pom.xml} (for example {@code 26.04}) names the Ubuntu
 * release of GitHub's runner every Linux job uses; each workflow's label ({@code ubuntu-26.04}) is a copy
 * ({@code releng/version-copies.txt}, section {@code [ci.ubuntu]}). The UI tests draw text, so the
 * runner's fonts, GTK and Pango decide how wide a line is: a new Ubuntu can change a test's result.
 * Pinned, such a move is a commit of its own, made with this tool, instead of the day GitHub moves
 * {@code ubuntu-latest}.
 *
 * <h2>Where the releases come from</h2>
 * GitHub's list of runner images, the table in the README of
 * <a href="https://github.com/actions/runner-images">actions/runner-images</a> ({@link #README}).
 * Example row: {@code | Ubuntu 26.04<br>… | x64 | `ubuntu-latest` or `ubuntu-26.04` | …}. Only an
 * image named exactly {@code Ubuntu 26.04} (before its badge) counts as released; anything more in
 * the name ({@code <sup>beta</sup>}, "preview") marks a release still in its trial. arm64 rows
 * (labels ending in {@code -arm}) are other machines. A table that changed shape stops the tool with
 * a message, never a guess.
 */
public final class UbuntuFiles {

    /** The property in the root {@code pom.xml}; the section of {@code releng/version-copies.txt} with its copies. */
    public static final String PROPERTY = "ci.ubuntu";

    /** GitHub's list of runner images, raw. */
    public static final String README = "https://raw.githubusercontent.com/actions/runner-images/main/README.md";

    /** Where a person reads the same list. */
    public static final String README_PAGE = "https://github.com/actions/runner-images#available-images";

    private static final Pattern POM_VERSION = Pattern.compile("<ci\\.ubuntu>(\\d{2}\\.\\d{2})</ci\\.ubuntu>");
    /** A release as the tool takes it: {@code 26.04}. */
    private static final Pattern VERSION = Pattern.compile("\\d{2}\\.\\d{2}");
    /** A released image's name, the cell's text before its badge ({@code <br>![…](…)}): {@code Ubuntu 26.04}. */
    private static final Pattern RELEASED_NAME = Pattern.compile("Ubuntu \\d{2}\\.\\d{2}");
    /** An x64 Ubuntu runner's label in the table: {@code `ubuntu-26.04`}, not {@code `ubuntu-26.04-arm`}. */
    private static final Pattern LABEL = Pattern.compile("`ubuntu-(\\d{2}\\.\\d{2})`");

    /** An Ubuntu release GitHub lists for x64 runners: {@code 26.04}, and whether it is still a beta. */
    public record Image(String version, boolean beta) {
    }

    private UbuntuFiles() {
    }

    /** The release {@code <ci.ubuntu>} names; fails unless there is exactly one. */
    public static String versionIn(String pom) {
        Matcher m = POM_VERSION.matcher(pom);
        if (!m.find()) {
            throw new ToolFailure("pom.xml has no <ci.ubuntu> with a release such as 26.04");
        }
        String version = m.group(1);
        if (m.find()) {
            throw new ToolFailure("pom.xml has more than one <ci.ubuntu>");
        }
        return version;
    }

    /** {@code pom} with {@code <ci.ubuntu>} set to {@code version}; the rest as it was. */
    public static String withVersion(String pom, String version) {
        versionIn(pom); // exactly one, or a clear failure
        return POM_VERSION.matcher(pom).replaceFirst("<ci.ubuntu>" + version + "</ci.ubuntu>");
    }

    /** Whether {@code text} is a release as the tool takes it: two digits, a dot, two digits ({@code 28.04}). */
    public static boolean isVersion(String text) {
        return VERSION.matcher(text).matches();
    }

    /**
     * The x64 Ubuntu releases the runner-images README lists, newest first. It reads the table whose
     * header has the columns {@code Image}, {@code Architecture} and {@code YAML Label} (found by
     * name, not position), and takes each row whose image starts with {@code Ubuntu}, whose
     * architecture is {@code x64} and whose labels include {@code `ubuntu-NN.NN`}. Released only when
     * the image's name, before its badge, is exactly {@code Ubuntu NN.NN}: a known form, so that a
     * new way of marking a trial release ({@code <sup>beta</sup>}, "preview") counts as not
     * released instead of slipping through. A release listed twice is released only if every row
     * is. Example: the row {@code | Ubuntu 26.04<br>… | x64 | `ubuntu-latest` or `ubuntu-26.04` | …}
     * gives 26.04, released. Fails, saying where to look, when no such table or no Ubuntu row is found: the
     * README changed shape, and the tool does not guess.
     */
    public static List<Image> images(String readme) {
        List<String> lines = readme.lines().toList();
        int image = -1;
        int architecture = -1;
        int label = -1;
        List<Image> found = new ArrayList<>();
        for (String line : lines) {
            if (!line.strip().startsWith("|")) {
                image = -1; // a table ends at its first line that is not a row
                continue;
            }
            List<String> cells = cells(line);
            if (image < 0) {
                image = indexOf(cells, "image");
                architecture = indexOf(cells, "architecture");
                label = indexOf(cells, "yaml label");
                if (image < 0 || architecture < 0 || label < 0) {
                    image = -1;
                }
                continue; // the header row itself
            }
            int needed = Math.max(image, Math.max(architecture, label));
            if (cells.size() <= needed || !cells.get(image).startsWith("Ubuntu")
                    || !cells.get(architecture).equalsIgnoreCase("x64")) {
                continue; // the separator row (| --- |), another system, or an arm64 machine
            }
            Matcher m = LABEL.matcher(cells.get(label));
            if (m.find()) {
                String name = cells.get(image);
                int badge = name.toLowerCase(Locale.ROOT).indexOf("<br"); // <br>, <br/>, <br />
                boolean released = RELEASED_NAME.matcher((badge < 0 ? name : name.substring(0, badge)).strip()).matches();
                String version = m.group(1);
                boolean trial = !released || found.stream().anyMatch(i -> i.version().equals(version) && i.beta());
                found.removeIf(i -> i.version().equals(version));
                found.add(new Image(version, trial));
            }
        }
        if (found.isEmpty()) {
            throw new ToolFailure("no x64 Ubuntu runner found in the runner-images README's table (columns Image, Architecture,"
                    + " YAML Label): it changed shape. Read " + README_PAGE + " and update runner/UbuntuFiles.images");
        }
        if (found.stream().allMatch(Image::beta)) {
            // Every release a trial is no table GitHub would keep: more likely a new way of writing
            // the name (the badge after <br/> instead of <br>), which the known form no longer takes.
            throw new ToolFailure("every x64 Ubuntu runner in the runner-images README reads as a beta: its image names changed"
                    + " shape. Read " + README_PAGE + " and update runner/UbuntuFiles.images");
        }
        found.sort(Comparator.comparing(Image::version).reversed());
        return List.copyOf(found);
    }

    /** The newest release GitHub lists as released (not beta), if any. */
    public static Optional<Image> newestReleased(List<Image> images) {
        return images.stream().filter(i -> !i.beta()).max(Comparator.comparing(Image::version));
    }

    /**
     * Checks that the pin may move from {@code from} to {@code to}: another release, which GitHub
     * lists for x64 runners and no longer as a beta (a beta's runners come without the service
     * GitHub promises for released ones, and change without notice). A lower release is allowed:
     * pinning back to the last one is how a broken move is undone (docs/UPGRADING.md).
     */
    public static void checkTarget(String from, String to, List<Image> images) {
        if (to.equals(from)) {
            throw new ToolFailure("CI already runs on Ubuntu " + to);
        }
        Optional<Image> listed = images.stream().filter(i -> i.version().equals(to)).findFirst();
        if (listed.isEmpty()) {
            throw new ToolFailure("GitHub lists no x64 runner ubuntu-" + to + " (" + README_PAGE + "); it has: "
                    + images.stream().map(i -> i.version() + (i.beta() ? " (beta)" : "")).toList());
        }
        if (listed.get().beta()) {
            throw new ToolFailure("ubuntu-" + to + " reads as not released: one of its image names in GitHub's list is not exactly"
                    + " \"Ubuntu " + to + "\" (a beta, or a row GitHub renamed). Read " + README_PAGE
                    + "; if it is released under a new name, update runner/UbuntuFiles.images");
        }
    }

    /** A table row's cells, without the outer pipes, each stripped. */
    private static List<String> cells(String row) {
        String s = row.strip();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : s.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static int indexOf(List<String> cells, String name) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }
}
