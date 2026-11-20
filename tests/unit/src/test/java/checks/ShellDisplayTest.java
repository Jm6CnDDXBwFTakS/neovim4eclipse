// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import common.Shell;

/**
 * {@link Shell#display}: a tool prints a command (its {@code --yes} rerun line, a step it is about
 * to run) so that a person can paste it back into a POSIX shell, the only kind the tools run from
 * ({@link Shell#requireSupportedShell}, also tested here).
 */
class ShellDisplayTest {

    @Test
    void aWordWithASpaceGoesInSingleQuotes() {
        assertEquals("java JavaUpgrade.java 25 --java '/opt/my jdk' --yes",
                Shell.display(List.of("java", "JavaUpgrade.java", "25", "--java", "/opt/my jdk", "--yes")));
        assertEquals("ls 'C:\\Program Files\\jdk-25\\'", Shell.display(List.of("ls", "C:\\Program Files\\jdk-25\\")),
                "Git Bash's Windows path, even one ending in a backslash: nothing is special inside single quotes");
    }

    @Test
    void aSingleQuoteAndAnEmptyWordStayOneWord() {
        assertEquals("echo 'it'\\''s'", Shell.display(List.of("echo", "it's")));
        assertEquals("echo ''", Shell.display(List.of("echo", "")));
    }

    /** Only the allowed characters stay bare; anything else, even a character no shell reads today, is quoted. */
    @Test
    void onlyPlainWordsStayBare() {
        assertEquals("git push origin release/0.2.31 -Dnvim.path=/opt/n:v@1,2+3%4 a_b",
                Shell.display(List.of("git", "push", "origin", "release/0.2.31", "-Dnvim.path=/opt/n:v@1,2+3%4", "a_b")));
        for (String w : List.of("HEAD~1", "~/x", "a#b", "a!b", "[a]", "{a,b}", "a^b", "a*", "a?", "$HOME", "`x`", "$(x)",
                "a;b", "a&b", "a|b", "a<b", "a>b", "(a)", "a\\b", "\"a\"", "=a", "caf\u00e9", "a b")) {
            String shown = Shell.display(List.of("echo", w));
            assertTrue(shown.startsWith("echo '") && shown.endsWith("'"), w + " is quoted: " + shown);
        }
    }

    @Test
    void aFirstWordWithAnEqualsSignIsQuotedSoItIsNoAssignment() {
        assertEquals("'a=b' echo hi", Shell.display(List.of("a=b", "echo", "hi")));
        assertEquals("env a=b", Shell.display(List.of("env", "a=b")), "a later one is a plain word");
    }

    @Test
    void aWordWithAControlCharacterIsNotShown() {
        for (String w : List.of("a\nrm -rf x", "a\tb", "a\u001b[2Jb", "a\u2028b", "\u202eexe.txt", "a\u200bb", "a\u2066b")) {
            assertEquals("echo " + Shell.WORD_NOT_SHOWN + " --yes", Shell.display(List.of("echo", w, "--yes")));
        }
    }

    @Test
    void theMavenWrapperOnWindowsIsShownAsGitBashRunsIt() {
        assertEquals("./mvnw -q -pl x compile", Shell.display(List.of("cmd", "/c", ".\\mvnw.cmd", "-q", "-pl", "x", "compile")));
        assertEquals("./mvnw verify", Shell.display(Shell.maven("verify")), "on this system too");
    }

    /** A failure message names the command as it ran, the Windows Maven start included, quoted as display quotes. */
    @Test
    void aFailureNamesTheCommandAsItRan() {
        assertEquals("cmd /c '.\\mvnw.cmd' -q verify", Shell.ran(List.of("cmd", "/c", ".\\mvnw.cmd", "-q", "verify")));
        assertEquals("git push origin '/opt/my dir'", Shell.ran(List.of("git", "push", "origin", "/opt/my dir")));
    }

    @Test
    void cmdAndPowerShellAreRefusedGitBashAndOtherSystemsAreNot() {
        assertEquals("Release: runs on Linux, macOS and in Git Bash on Windows, not from cmd or PowerShell. Run it from Git Bash.",
                Shell.refusal("Release", true, null, "cmd.exe").orElseThrow());
        assertTrue(Shell.refusal("Release", true, " ", "bash.exe").isPresent(), "an empty MSYSTEM is no Git Bash");
        for (String shell : List.of("cmd.exe", "powershell.exe", "pwsh.exe", "PowerShell.EXE")) {
            assertTrue(Shell.refusal("Release", true, "MINGW64", shell).isPresent(), shell + " opened from Git Bash, MSYSTEM inherited");
        }
        assertFalse(Shell.refusal("Release", true, "MINGW64", "bash.exe").isPresent(), "Git Bash");
        assertFalse(Shell.refusal("Release", true, "MINGW64", "java.exe").isPresent(), "a tool another tool starts");
        assertTrue(Shell.refusal("Release", true, "MINGW64", "").isPresent(), "a parent Windows does not name: MSYSTEM alone is not enough");
        assertTrue(Shell.refusal("Release", true, "MINGW64", "winpty.exe").isPresent(), "any parent outside the known set");
        assertTrue(Shell.refusal("Release", true, null, "bash.exe").isPresent(), "bash.exe alone is not enough either");
        assertFalse(Shell.refusal("Release", true, "MINGW64", "Bash.EXE").isPresent(), "names ignore case on Windows");
        assertFalse(Shell.refusal("Release", false, null, "").isPresent(), "Linux, macOS");
    }

    /** The marker is a redirection from a file that does not exist: pasted back, the shell runs nothing. */
    @Test
    void aPastedMarkerRunsNothing() throws Exception {
        assumeTrue(!Shell.WINDOWS && Files.isExecutable(Path.of("/bin/sh")), "needs a POSIX sh");
        Path dir = Files.createTempDirectory("marker");
        Path ran = dir.resolve("ran");
        String line = Shell.display(List.of("touch", ran.toString(), "a\nb"));
        Process p = new ProcessBuilder("/bin/sh", "-c", line).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor() != 0, "the shell stops: " + out);
        assertFalse(Files.exists(ran), "touch did not run");
        Files.delete(dir);
    }

    /**
     * Every program under {@code releng/tools} with a {@code main} refuses cmd and PowerShell
     * first: a tool through {@code Shell.requireSupportedShell}, a single-file program (LikeCi,
     * Proxy, ArchiveCheck) through its own copy, which must say the same. A launcher, which only
     * starts its tool by {@code Class.forName}, leaves the check to the tool.
     */
    @Test
    void everyProgramChecksTheShellFirst() throws IOException {
        Path tools = root().resolve("releng/tools");
        Pattern main = Pattern.compile("public static void main\\(String\\[\\] args\\)[^{]*\\{\\s*\\n\\s*(\\S[^\\n]*)");
        String copy = "System.err.println(tool + \"" + Shell.WHERE_TOOLS_RUN.substring(0, Shell.WHERE_TOOLS_RUN.indexOf(" Run")) + "\"\n"
                + "                    + \" Run it from Git Bash.\");";
        List<String> wrong = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        try (Stream<Path> files = Files.walk(tools)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String name = tools.relativize(file).toString().replace('\\', '/');
                String text = Files.readString(file);
                if (!ANY_MAIN.matcher(text).find()) {
                    continue;
                }
                var m = main.matcher(text);
                String tool = file.getFileName().toString().replace(".java", "");
                if (!m.find()) {
                    wrong.add(name + " (a main this test cannot read)");
                } else if (m.group(1).equals("Shell.requireSupportedShell(\"" + tool.replace("Tool", "") + "\");")) {
                    checked.add(name);
                } else if (m.group(1).equals("requireSupportedShell(\"" + tool + "\");")) {
                    if (text.contains(copy)) {
                        checked.add(name);
                    } else {
                        wrong.add(name + " (its copy of the message differs from Shell.WHERE_TOOLS_RUN)");
                    }
                } else if (!LAUNCHER.matcher(text).find()) {
                    wrong.add(name);
                }
            }
        }
        assertEquals(List.of(), wrong, "a program's main starts with Shell.requireSupportedShell(\"<Tool>\") (common/Shell)");
        // The copies are one method, word for word, so a condition changed in one shows here.
        List<String> bodies = new ArrayList<>();
        for (String single : List.of("ci/LikeCi.java", "checks/Proxy.java", "neovim/ArchiveCheck.java")) {
            String text = Files.readString(tools.resolve(single));
            int start = text.indexOf("    private static void requireSupportedShell(String tool) {");
            assertTrue(start >= 0, single + " has no copy of requireSupportedShell");
            bodies.add(text.substring(start, text.indexOf("\n    }\n", start)));
        }
        assertEquals(1, bodies.stream().distinct().count(), "LikeCi, Proxy and ArchiveCheck hold the same requireSupportedShell");
        assertTrue(bodies.get(0).contains(Shell.GIT_BASH_PARENTS.stream().map(n -> "\"" + n + "\"").collect(java.util.stream.Collectors.joining(", "))),
                "the copies allow the parents Shell.GIT_BASH_PARENTS names");
        assertEquals(List.of("checks/InstallCheckTool.java", "checks/ManualChecksTool.java", "checks/Proxy.java", "ci/LikeCi.java",
                "dependencies/DependenciesTool.java", "eclipse/EclipseUpgradeTool.java", "javarelease/JavaUpgradeTool.java",
                "marketplace/MarketplaceTool.java", "maven/MavenUpgradeTool.java", "neovim/ArchiveCheck.java",
                "neovim/NeovimUpgradeTool.java", "plugins/TestPluginsTool.java", "release/ReleaseTool.java"), checked,
                "the programs found; a new one joins this list");
    }

    /** Any method named main, however it is written ({@code main (String... a)}), so that none is skipped unread. */
    private static final Pattern ANY_MAIN = Pattern.compile("\\bmain\\s*\\(");

    /** A launcher: a single file that only starts its tool, {@code Class.forName("release.ReleaseTool")}, which checks. */
    private static final Pattern LAUNCHER = Pattern.compile("Class\\.forName\\(\"[a-z]+\\.[A-Za-z]+Tool\"\\)");

    private static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("AGENTS.md"))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "no AGENTS.md above the working folder");
        return dir;
    }
}
