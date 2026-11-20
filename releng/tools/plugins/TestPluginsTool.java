// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package plugins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import common.Ask;
import common.Folders;
import common.Http;
import common.Shell;
import common.ToolCache;
import common.ToolFailure;
import plugins.Pins.Pin;

/**
 * The Neovim plug-ins the tests and the gallery run (flash, leap, hop, mini.jump2d, which-key):
 * fetches them as pinned, and moves a pin.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java releng/tools/plugins/TestPlugins.java                    status: each pin, and whether it is fetched
 *   java releng/tools/plugins/TestPlugins.java fetch              download and unpack what is not there yet
 *   java releng/tools/plugins/TestPlugins.java update flash 5f0f… download that commit of flash for vetting
 *   java releng/tools/plugins/TestPlugins.java accept flash 5f0f… 56ec…   after vetting: the commit and the SHA-256 you vetted
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
 * For each pin: the archive into {@code .tool-cache/test-plugins/archives/}, its hash compared with
 * the pin's, then unpacked into {@code .tool-cache/test-plugins/<name>/} with a marker file
 * {@code <name>.pin} beside it that says which commit and archive hash is there. The tests look
 * for that folder and marker and skip, with the reason, when they are missing or of another pin.
 * <ul>
 *   <li><b>A wrong hash stops the tool</b> and the archive is deleted: the bytes are not what was
 *       looked at. The message names the hash found, which is the value to put into the file if
 *       the commit is the one meant.</li>
 *   <li><b>No network is no failure:</b> the plug-in is left out with a note, and the tests that
 *       need it are skipped. The build still passes offline. An answer that is not the archive
 *       (a 404: the repository or the commit is gone) does stop the tool: that is a fact about
 *       the pin, not about this machine's network. So does a connection that is not secure (a
 *       certificate that does not fit, a proxy that opens TLS): something answered, and it was
 *       not the forge.</li>
 * </ul>
 *
 * <h2>update, then accept</h2>
 * When to move a pin, and to which commit, is decided by hand: the tool never asks a repository
 * what is new and never moves a pin by itself. What it takes over is the bookkeeping the tests
 * depend on: the archive's hash, the unpacked copy, the two lines of the entry.
 *
 * <p>Moving a pin takes two steps, with the maintainer's own check of the archive between them, so
 * that nothing opens the archive before it has been scanned:
 * <ol>
 *   <li>{@code update} downloads the commit's archive into {@code .tool-cache/test-plugins/vetting/}
 *       and prints its path, its SHA-256 and the VirusTotal address of that hash. It only hashes
 *       the bytes: the ZIP is not opened, nothing is unpacked, and it stops there.</li>
 *   <li>The maintainer scans the file (or looks the hash up).</li>
 *   <li>{@code accept} takes the SHA-256 that was vetted and compares the file's with it before
 *       opening the ZIP: {@code update} run again can bring other bytes for the same commit (a
 *       forge makes the archive on request), and a file can be replaced. A hash that differs
 *       stops the tool with nothing unpacked. The file is read once, and the bytes that were
 *       hashed are the bytes unpacked. Then it unpacks, shows the license file's first
 *       lines and the files that differ from the commit pinned now, and after a yes writes the
 *       two lines of the entry, with that hash. The yes is typed at a terminal: {@code accept}
 *       takes no {@code --yes} and does nothing without a terminal ({@link #modeFor}).</li>
 * </ol>
 * Neither step commits anything: the change of the pin is yours to review and commit.
 */
public final class TestPluginsTool {

    static final String TOOL = "releng/tools/plugins/TestPlugins.java";
    /** The pins, relative to the repository's root. */
    public static final String PINS_FILE = "releng/test-plugins.properties";
    /** Where the plug-ins are unpacked, relative to the repository's root; the tests look here. */
    public static final String FOLDER = ToolCache.TEST_PLUGINS;
    private static final String USAGE = "Usage: java " + TOOL
            + " [status | fetch | update <name> <commit> | accept <name> <commit> <sha256>] [--dry-run]";

    /**
     * The largest archive that is read, and the most its files may add up to. The pinned plug-ins
     * are well under a megabyte each; an archive far beyond that is not one of theirs, and it is
     * read into memory whole (so that the bytes whose hash was checked are the bytes unpacked).
     */
    static final long MAX_ARCHIVE_BYTES = 50L << 20;
    static final long MAX_UNPACKED_BYTES = 500L << 20;

    private final Path root;
    private final Ask ask;

    TestPluginsTool(Path root, Ask ask) {
        this.root = root;
        this.ask = ask;
    }

    public static void main(String[] args) {
        Shell.requireSupportedShell("TestPlugins");
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
            String step = words.isEmpty() ? "status" : words.get(0);
            mode = modeFor(step, mode);
            // accept is the only step that asks. What Ask prints when there is no terminal to
            // ask on is this, not a command with --yes: accept takes none.
            String rerun = "the same command at a terminal (accept asks a person, and takes no --yes)";
            TestPluginsTool tool = new TestPluginsTool(root, new Ask(mode, rerun));
            switch (step) {
                case "status" -> tool.status();
                case "fetch" -> tool.fetch();
                case "update" -> {
                    if (words.size() != 3) {
                        throw new ToolFailure("update needs the plug-in's name and the commit to move to\n" + USAGE);
                    }
                    tool.update(words.get(1), words.get(2));
                }
                case "accept" -> {
                    if (words.size() != 4) {
                        throw new ToolFailure("accept needs the plug-in's name, the commit that update downloaded and the SHA-256 you vetted\n" + USAGE);
                    }
                    tool.accept(words.get(1), words.get(2), words.get(3));
                }
                default -> throw new ToolFailure("unknown step " + step + "\n" + USAGE);
            }
        } catch (ToolFailure f) {
            System.err.println();
            System.err.println("TestPlugins: " + f.getMessage());
            System.exit(1);
        }
    }

    /**
     * The mode {@code step} runs in. {@code accept} refuses {@code --yes}: a pin moves only on a
     * yes typed at a terminal, after the maintainer's own scan of the archive, so a script (or an
     * AI running the tool) cannot go from {@code update}'s output to a moved pin by itself. The
     * other tools take {@code --yes} for a step approved beforehand; here the approval is the
     * point of the step. {@code --dry-run} stays: it writes nothing.
     */
    static Ask.Mode modeFor(String step, Ask.Mode asked) {
        if (step.equals("accept") && asked == Ask.Mode.YES) {
            throw new ToolFailure("accept takes no --yes: it moves a pin, and asks the person who vetted the archive. Run it at a terminal.");
        }
        return asked;
    }

    private List<Pin> pins() {
        return Pins.parse(read(root.resolve(PINS_FILE)));
    }

    /**
     * Each pin and whether it is fetched. Nothing is asked of a repository here: which commit to
     * move to is the maintainer's choice, made on the plug-in's own page; the tool's part starts
     * once a commit is named.
     */
    void status() {
        for (Pin pin : pins()) {
            System.out.println(pin.name() + "  " + pin.license() + "  " + pin.repository());
            System.out.println("  pinned   " + pin.commit() + (isFetched(pin) ? "  (fetched)" : "  (not fetched: java " + TOOL + " fetch)"));
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
            byte[] bytes = bytesOf(archive);
            String got = sha256(bytes);
            if (!got.equals(pin.sha256())) {
                delete(archive);
                throw new ToolFailure(pin.archiveUrl() + " has SHA-256 " + got + ", but " + PINS_FILE + " pins " + pin.sha256()
                        + "; it was deleted, not unpacked. If the commit is the one meant, vet the archive and set "
                        + pin.name() + ".sha256 (java " + TOOL + " update " + pin.name() + " " + pin.commit() + " keeps a copy for vetting)");
            }
            // The marker goes first: should the unpacking stop halfway, no marker claims the folder.
            delete(root.resolve(FOLDER).resolve(pin.name() + ".pin"));
            unpack(archive.getFileName().toString(), bytes, root.resolve(FOLDER).resolve(pin.name()));
            write(root.resolve(FOLDER).resolve(pin.name() + ".pin"), pin.marker() + "\n");
            System.out.println("ok: " + pin.name() + " at " + shortRevision(pin) + ", SHA-256 as pinned");
        }
        if (missing > 0) {
            System.out.println(missing + " plug-in(s) not fetched; nothing else failed.");
        }
    }

    /**
     * The first step of moving {@code name}'s pin: downloads {@code commit} for vetting and says
     * where it is. The archive is hashed, never opened. The commit is always named: the tool does
     * not look for a newer one, it records the one the maintainer chose.
     */
    void update(String name, String commit) {
        Pin old = Pins.find(pins(), name);
        Pins.checkCommit(commit); // before anything is downloaded: it becomes part of a URL and of a file name
        Pin next = new Pin(name, old.repository(), commit, old.sha256(), old.license());
        Path archive = Http.fetch(next.archiveUrl(), vettingArchive(name, commit));
        String sha = Http.hash(archive, "SHA-256");
        System.out.println();
        System.out.println(name + ": " + old.commit() + " -> " + commit + (commit.equals(old.commit()) ? "  (the pinned commit)" : ""));
        System.out.println("  archive     " + archive);
        System.out.println("  SHA-256     " + sha + (sha.equals(old.sha256()) ? "  (the pinned hash)" : ""));
        System.out.println("  VirusTotal  https://www.virustotal.com/gui/file/" + sha + "  (a report, if the file is known there; else upload the archive)");
        System.out.println("The archive was not opened. Vet it, then: java " + TOOL + " accept " + name + " " + commit + " " + sha);
    }

    /**
     * The second step, after the maintainer has vetted the archive {@link #update} left: unpacks
     * it, shows its license and what changed, and writes the pin after a yes. {@code vetted} is
     * the SHA-256 the maintainer vetted. The file is read once: those bytes must have that hash,
     * and those bytes are what is unpacked, so a file replaced meanwhile changes nothing.
     */
    void accept(String name, String commit, String vetted) {
        String text = read(root.resolve(PINS_FILE));
        Pin old = Pins.find(Pins.parse(text), name);
        Pins.checkCommit(commit); // before anything is opened: it is part of the archive's file name
        Pins.checkSha256(vetted);
        Path archive = vettingArchive(name, commit);
        if (!Files.isRegularFile(archive)) {
            throw new ToolFailure("no archive to accept at " + archive + "; first: java " + TOOL + " update " + name + " " + commit);
        }
        byte[] bytes = bytesOf(archive);
        String sha = sha256(bytes);
        if (!sha.equals(vetted)) {
            throw new ToolFailure(archive + " has SHA-256 " + sha + ", not the " + vetted
                    + " you name; it was not opened. Vet this file, or download it again: java " + TOOL + " update " + name + " " + commit);
        }
        Path unpacked = archive.resolveSibling(name + "-" + commit);
        unpack(archive.getFileName().toString(), bytes, unpacked);

        System.out.println();
        System.out.println(name + ": " + old.commit() + " -> " + commit);
        System.out.println("  archive     " + archive);
        System.out.println("  SHA-256     " + sha + "  (the one you vetted)");
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

    /**
     * Unpacks the archive's {@code bytes} as {@code folder}. An archive of a repository holds one top folder
     * (GitHub: {@code flash.nvim-<commit>/}); that folder becomes {@code folder}, so the plug-in's
     * {@code lua/} is right under it, where Neovim's {@code 'runtimepath'} wants it. An archive
     * of any other shape is not a repository's, and stops the tool.
     */
    static void unpack(String archive, byte[] bytes, Path folder) {
        Path scratch = folder.resolveSibling(folder.getFileName() + ".unpacking");
        Folders.deleteTree(scratch);
        Folders.deleteTree(folder);
        try {
            Files.createDirectories(scratch); // bytes that are no ZIP unpack to nothing: an empty folder, refused below
            Folders.unzip(archive, bytes, scratch, MAX_UNPACKED_BYTES);
            List<Path> entries;
            try (Stream<Path> top = Files.list(scratch)) {
                entries = top.toList();
            }
            if (entries.size() != 1 || !Files.isDirectory(entries.get(0))) {
                throw new ToolFailure(archive + " does not hold one top folder, as a repository's archive does");
            }
            Files.move(entries.get(0), folder, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ToolFailure("cannot unpack " + archive + ": " + e.getMessage());
        } finally {
            Folders.deleteTree(scratch); // also after a ZIP that could not be read: no half-unpacked folder stays
        }
    }

    /** The archive's bytes, read once; an archive larger than {@link #MAX_ARCHIVE_BYTES} stops the tool unread. */
    private static byte[] bytesOf(Path archive) {
        try {
            long size = Files.size(archive);
            if (size > MAX_ARCHIVE_BYTES) {
                throw new ToolFailure(archive + " has " + size + " bytes, more than any plug-in's archive (" + MAX_ARCHIVE_BYTES + "); it was not read");
            }
            return Files.readAllBytes(archive);
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + archive + ": " + e.getMessage());
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java has SHA-256
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
