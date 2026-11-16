// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package plugins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import common.Ask;
import common.Folders;
import common.Http;
import common.Shell;
import common.ToolFailure;
import plugins.Pins.Pin;

/**
 * The Neovim plug-ins the tests run (flash, leap, hop, mini.jump2d): fetches them as pinned, and
 * moves a pin.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java releng/tools/plugins/TestPlugins.java                    status: each pin, and whether it is fetched
 *   java releng/tools/plugins/TestPlugins.java fetch              download and unpack what is not there yet
 *   java releng/tools/plugins/TestPlugins.java update flash       download flash's newest commit for vetting
 *   java releng/tools/plugins/TestPlugins.java update flash 5f0f… ... or a commit you name
 *   java releng/tools/plugins/TestPlugins.java accept flash 5f0f… after vetting: look inside, then write the pin
 * </pre>
 *
 * <h2>Why</h2>
 * The tests of the jump labels run the real plug-ins: what breaks them is a plug-in renaming a
 * highlight group or drawing its labels another way, and only the plug-in itself shows that. But
 * the repository holds no plug-in, and a build must not run whatever a repository's head happens
 * to be. So {@code releng/test-plugins.properties} pins each plug-in to a commit and to the
 * SHA-256 of that commit's archive ({@link Pins}), and this tool is the only thing that downloads.
 *
 * <h2>fetch</h2>
 * For each pin: the archive into {@code target/test-plugins/archives/}, its hash compared with
 * the pin's, then unpacked into {@code target/test-plugins/<name>/} with a marker file
 * {@code <name>.pin} beside it that says which commit is there. The tests look for that folder and
 * marker and skip, with the reason, when they are missing or of another commit.
 * <ul>
 *   <li><b>A wrong hash stops the tool</b> and the archive is deleted: the bytes are not what was
 *       looked at. The message names the hash found, which is the value to put into the file if
 *       the commit is the one meant.</li>
 *   <li><b>No network is no failure:</b> the plug-in is left out with a note, and the tests that
 *       need it are skipped. The build still passes offline. An answer that is not the archive
 *       (a 404: the repository or the commit is gone) does stop the tool: that is a fact about
 *       the pin, not about this machine's network.</li>
 * </ul>
 *
 * <h2>update, then accept</h2>
 * Moving a pin takes two steps, with the maintainer's own check of the archive between them, so
 * that nothing opens the archive before it has been scanned:
 * <ol>
 *   <li>{@code update} downloads the commit's archive into {@code target/test-plugins/vetting/}
 *       and prints its path, its SHA-256 and the VirusTotal address of that hash. It only hashes
 *       the bytes: the ZIP is not opened, nothing is unpacked, and it stops there.</li>
 *   <li>The maintainer scans the file (or looks the hash up).</li>
 *   <li>{@code accept} checks that the file is still the one {@code update} left, unpacks it,
 *       shows the license file's first lines and the files that differ from the commit pinned
 *       now, and after a yes writes the two lines of the entry, with the hash of that file.</li>
 * </ol>
 * Neither step commits anything: the change of the pin is yours to review and commit.
 */
public final class TestPluginsTool {

    static final String TOOL = "releng/tools/plugins/TestPlugins.java";
    /** The pins, relative to the repository's root. */
    public static final String PINS_FILE = "releng/test-plugins.properties";
    /** Where the plug-ins are unpacked, relative to the repository's root; the tests look here. */
    public static final String FOLDER = "target/test-plugins";
    private static final String USAGE = "Usage: java " + TOOL
            + " [status | fetch | update <name> [<commit>] | accept <name> <commit>] [--yes] [--dry-run]";

    private final Path root;
    private final Shell shell;
    private final Ask ask;

    TestPluginsTool(Path root, Ask ask) {
        this.root = root;
        this.shell = new Shell(root);
        this.ask = ask;
    }

    public static void main(String[] args) {
        try {
            Path root = Path.of("").toAbsolutePath();
            if (!Files.isRegularFile(root.resolve(TOOL))) {
                throw new ToolFailure("run this from the repository's root folder: java " + TOOL);
            }
            Ask.Mode mode = Ask.Mode.ASK;
            List<String> words = new ArrayList<>();
            for (String a : args) {
                switch (a) {
                    case "--yes" -> mode = mode == Ask.Mode.DRY_RUN ? mode : Ask.Mode.YES;
                    case "--dry-run" -> mode = Ask.Mode.DRY_RUN;
                    default -> words.add(a);
                }
            }
            List<String> rerun = new ArrayList<>(List.of("java", TOOL));
            rerun.addAll(words);
            rerun.add("--yes");
            TestPluginsTool tool = new TestPluginsTool(root, new Ask(mode, String.join(" ", rerun)));
            String step = words.isEmpty() ? "status" : words.get(0);
            switch (step) {
                case "status" -> tool.status();
                case "fetch" -> tool.fetch();
                case "update" -> {
                    if (words.size() < 2 || words.size() > 3) {
                        throw new ToolFailure("update needs a plug-in's name, and may take a commit\n" + USAGE);
                    }
                    tool.update(words.get(1), words.size() == 3 ? words.get(2) : null);
                }
                case "accept" -> {
                    if (words.size() != 3) {
                        throw new ToolFailure("accept needs the plug-in's name and the commit that update downloaded\n" + USAGE);
                    }
                    tool.accept(words.get(1), words.get(2));
                }
                default -> throw new ToolFailure("unknown step " + step + "\n" + USAGE);
            }
        } catch (ToolFailure f) {
            System.err.println();
            System.err.println("TestPlugins: " + f.getMessage());
            System.exit(1);
        }
    }

    private List<Pin> pins() {
        return Pins.parse(read(root.resolve(PINS_FILE)));
    }

    /** Each pin, whether it is fetched, and the repository's newest commit where it can be asked. */
    void status() {
        for (Pin pin : pins()) {
            String newest = newestCommit(pin.repository());
            System.out.println(pin.name() + "  " + pin.license() + "  " + pin.repository());
            System.out.println("  pinned   " + pin.commit() + (isFetched(pin) ? "  (fetched)" : "  (not fetched: java " + TOOL + " fetch)"));
            System.out.println("  newest   " + (newest == null ? "unknown (the repository did not answer)"
                    : newest + (newest.equals(pin.commit()) ? "  (the pin)" : "  (java " + TOOL + " update " + pin.name() + ")")));
        }
    }

    /** Whether {@code pin}'s plug-in is unpacked, of that commit and archive. */
    boolean isFetched(Pin pin) {
        Path marker = root.resolve(FOLDER).resolve(pin.name() + ".pin");
        return Files.isDirectory(root.resolve(FOLDER).resolve(pin.name())) && Files.isRegularFile(marker)
                && read(marker).strip().equals(pin.marker());
    }

    /** Fetches every pin that is not there yet; see the class comment for what stops it and what does not. */
    void fetch() {
        int missing = 0;
        for (Pin pin : pins()) {
            if (isFetched(pin)) {
                System.out.println("ok: " + pin.name() + " at " + shortRevision(pin) + ", from an earlier run");
                continue;
            }
            Path archive = root.resolve(FOLDER).resolve("archives").resolve(pin.name() + "-" + pin.commit() + ".zip");
            if (!Files.isRegularFile(archive) && !Http.fetchIfReachable(pin.archiveUrl(), archive)) {
                // Not reachable: no network. An answer that is not the file (a 404) stops the tool instead.
                missing++;
                System.out.println("note: " + pin.name() + " not fetched (no network?); the tests that need it will be skipped");
                continue;
            }
            String got = Http.hash(archive, "SHA-256");
            if (!got.equals(pin.sha256())) {
                delete(archive);
                throw new ToolFailure(pin.archiveUrl() + " has SHA-256 " + got + ", but " + PINS_FILE + " pins " + pin.sha256()
                        + "; it was deleted, not unpacked. If the commit is the one meant, vet the archive and set "
                        + pin.name() + ".sha256 (java " + TOOL + " update " + pin.name() + " " + pin.commit() + " keeps a copy for vetting)");
            }
            // The marker goes first: should the unpacking stop halfway, no marker claims the folder.
            delete(root.resolve(FOLDER).resolve(pin.name() + ".pin"));
            unpack(archive, root.resolve(FOLDER).resolve(pin.name()));
            write(root.resolve(FOLDER).resolve(pin.name() + ".pin"), pin.marker() + "\n");
            System.out.println("ok: " + pin.name() + " at " + shortRevision(pin) + ", SHA-256 as pinned");
        }
        if (missing > 0) {
            System.out.println(missing + " plug-in(s) not fetched; nothing else failed.");
        }
    }

    /**
     * The first step of moving {@code name}'s pin: downloads {@code commit} (the repository's
     * newest when {@code null}) for vetting and says where it is. The archive is hashed, never
     * opened.
     */
    void update(String name, String commit) {
        Pin old = Pins.find(pins(), name);
        String to = commit != null ? commit : newestCommit(old.repository());
        if (to == null) {
            throw new ToolFailure(old.repository() + " did not say what its newest commit is; name the commit: java " + TOOL
                    + " update " + name + " <commit>");
        }
        Pins.checkCommit(to); // before anything is downloaded: it becomes part of a URL and of a file name
        Pin next = new Pin(name, old.repository(), to, old.sha256(), old.license());
        Path archive = Http.fetch(next.archiveUrl(), vettingArchive(name, to));
        String sha = Http.hash(archive, "SHA-256");
        System.out.println();
        System.out.println(name + ": " + old.commit() + " -> " + to + (to.equals(old.commit()) ? "  (the pinned commit)" : ""));
        System.out.println("  archive     " + archive);
        System.out.println("  SHA-256     " + sha + (sha.equals(old.sha256()) ? "  (the pinned hash)" : ""));
        System.out.println("  VirusTotal  https://www.virustotal.com/gui/file/" + sha + "  (a report, if the file is known there; else upload the archive)");
        System.out.println("The archive was not opened. Vet it, then: java " + TOOL + " accept " + name + " " + to);
    }

    /**
     * The second step, after the maintainer has vetted the archive {@link #update} left: unpacks
     * it, shows its license and what changed, and writes the pin after a yes. The hash written is
     * that of the file as it is now, printed again, so that it can be compared with the one vetted.
     */
    void accept(String name, String commit) {
        String text = read(root.resolve(PINS_FILE));
        Pin old = Pins.find(Pins.parse(text), name);
        Pins.checkCommit(commit); // before anything is opened: it is part of the archive's file name
        Path archive = vettingArchive(name, commit);
        if (!Files.isRegularFile(archive)) {
            throw new ToolFailure("no archive to accept at " + archive + "; first: java " + TOOL + " update " + name + " " + commit);
        }
        String sha = Http.hash(archive, "SHA-256");
        Path unpacked = archive.resolveSibling(name + "-" + commit);
        unpack(archive, unpacked);

        System.out.println();
        System.out.println(name + ": " + old.commit() + " -> " + commit);
        System.out.println("  archive     " + archive);
        System.out.println("  SHA-256     " + sha + "  (compare with the one you vetted)");
        System.out.println("  unpacked    " + unpacked);
        System.out.println("  license     recorded as " + old.license() + "; the repository's file begins:");
        for (String line : licenseStart(unpacked)) {
            System.out.println("              " + printable(line));
        }
        if (isFetched(old)) {
            List<String> changes = differences(root.resolve(FOLDER).resolve(name), unpacked);
            System.out.println("  files that differ from the pinned commit: " + (changes.isEmpty() ? "none" : changes.size()));
            changes.forEach(c -> System.out.println("    " + printable(c)));
        } else {
            System.out.println("  (the pinned commit is not fetched, so the files are not compared: java " + TOOL + " fetch first)");
        }
        if (commit.equals(old.commit()) && sha.equals(old.sha256())) {
            System.out.println("The pin is this commit and hash already; nothing to write.");
            return;
        }
        if (!ask.confirm("Write " + name + "'s pin to " + PINS_FILE + " (you have vetted the archive)",
                List.of(name + ".commit=" + commit, name + ".sha256=" + sha))) {
            return;
        }
        write(root.resolve(PINS_FILE), Pins.withPin(text, name, commit, sha));
        System.out.println("Written. If the license changed, correct " + name + ".license by hand. Then: java " + TOOL
                + " fetch, run the tests (java releng/tools/ci/LikeCi.java), and commit " + PINS_FILE + ".");
    }

    /** A commit's first twelve digits, enough to tell commits apart in a line of output. */
    private static String shortRevision(Pin pin) {
        return pin.commit().substring(0, 12);
    }

    private Path vettingArchive(String name, String commit) {
        return root.resolve(FOLDER).resolve("vetting").resolve(name + "-" + commit + ".zip");
    }

    /** The commit the repository's default branch is at, by {@code git ls-remote}; {@code null} if it cannot be asked. */
    private String newestCommit(String repository) {
        Shell.Result r = shell.capture(List.of("git", "ls-remote", repository, "HEAD"));
        String first = r.ok() ? r.out().strip().split("\\s+", 2)[0] : "";
        return first.matches("[0-9a-f]{40}") ? first : null;
    }

    /**
     * Unpacks {@code archive} as {@code folder}. An archive of a repository holds one top folder
     * (GitHub: {@code flash.nvim-<commit>/}); that folder becomes {@code folder}, so the plug-in's
     * {@code lua/} is right under it, where Neovim's {@code 'runtimepath'} wants it. An archive
     * of any other shape is not a repository's, and stops the tool.
     */
    private static void unpack(Path archive, Path folder) {
        Path scratch = folder.resolveSibling(folder.getFileName() + ".unpacking");
        Folders.deleteTree(scratch);
        Folders.deleteTree(folder);
        Folders.unzip(archive, scratch);
        try (Stream<Path> top = Files.list(scratch)) {
            List<Path> entries = top.toList();
            if (entries.size() != 1 || !Files.isDirectory(entries.get(0))) {
                throw new ToolFailure(archive + " does not hold one top folder, as a repository's archive does");
            }
            Files.move(entries.get(0), folder, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ToolFailure("cannot unpack " + archive + ": " + e.getMessage());
        } finally {
            Folders.deleteTree(scratch);
        }
    }

    /** The first lines of the license file in {@code folder}, or a note that there is none. */
    private static List<String> licenseStart(Path folder) {
        try (Stream<Path> files = Files.list(folder)) {
            Path license = files.filter(p -> p.getFileName().toString().toUpperCase().matches("(LICEN[SC]E|COPYING)(\\..*)?"))
                    .sorted().findFirst().orElse(null);
            if (license == null) {
                return List.of("(no LICENSE file at the top of the repository)");
            }
            return read(license).lines().map(String::strip).filter(l -> !l.isEmpty()).limit(4).toList();
        } catch (IOException e) {
            return List.of("(cannot list " + folder + ": " + e.getMessage() + ")");
        }
    }

    /** The files that are in only one of the two folders, or differ: {@code + path}, {@code - path}, {@code ~ path}. */
    static List<String> differences(Path before, Path after) {
        Map<String, String> a = hashes(before);
        Map<String, String> b = hashes(after);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : b.entrySet()) {
            String was = a.get(e.getKey());
            if (was == null) {
                out.add("+ " + e.getKey());
            } else if (!was.equals(e.getValue())) {
                out.add("~ " + e.getKey());
            }
        }
        a.keySet().stream().filter(k -> !b.containsKey(k)).forEach(k -> out.add("- " + k));
        return out;
    }

    private static Map<String, String> hashes(Path folder) {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.put(folder.relativize(file).toString().replace('\\', '/'), Http.hash(file, "SHA-256"));
            }
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + folder + ": " + e.getMessage());
        }
        return out;
    }

    /** {@code text} without control characters: a line or a file name of an archive not yet vetted is shown, not obeyed by the terminal. */
    private static String printable(String text) {
        return text.replaceAll("\\p{Cntrl}", "?");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + file + ": " + e.getMessage());
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolFailure("cannot write " + file + ": " + e.getMessage());
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.out.println("  (could not delete " + file + ": " + e.getMessage() + ")");
        }
    }
}
