// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Keeps the promise the README's <i>Settings</i> section makes: every name a user sets or reads
 * stays as it is. A user's {@code init.lua}, {@code eclipse.ini}, key bindings and saved preferences
 * hold these names; a renamed one is silently ignored, so the user's choice is lost without a word.
 *
 * <p>{@link #PROMISED} is the list. The test fails when
 * <ul>
 *   <li>a promised name is no longer in the code, as after a rename: put the old name back, or, if
 *       the change is wanted, follow the promise (a release that still accepts the old name and
 *       warns about it, a changelog entry) and only then change the list. A name whose part of the
 *       plug-in is gone, so that it had no effect left to keep, moves to {@link #REMOVED};</li>
 *   <li>a {@link #REMOVED} name is still in the code, or missing from the README's list of
 *       removed names;</li>
 *   <li>a promised name is missing from the README's <i>Settings</i> section;</li>
 *   <li>the code gains a name of a kind users set (an {@code init.lua} switch, a preference key, a
 *       command of {@code plugin.xml}, an Ex command) that is neither promised nor listed in
 *       {@link #NOT_PROMISED}: decide which it is, and document a promised one in the README.</li>
 * </ul>
 *
 * <p>Example: renaming {@code statusMode} to {@code statusShowMode} in {@code PreferenceConstants}
 * fails {@link #everyPromisedNameIsStillInTheCode()} with the old name and the file it was looked
 * for in.
 */
class SettingsNamesTest {

    private static final String PLUGIN = "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/";
    private static final String SRC = PLUGIN + "src/io/github/jm6cnddxbwftaks/neovim4eclipse/";
    private static final String PREFERENCES = SRC + "prefs/PreferenceConstants.java";
    private static final String LUA = SRC + "core/LuaScripts.java";
    private static final String PLUGIN_XML = PLUGIN + "plugin.xml";
    private static final String COMMAND_ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.commands.";

    /**
     * Every promised name, with the file whose text must contain it, as the code spells it there
     * (a Lua read {@code vim.g.neovim4eclipse_folds}, a Java string {@code "statusMode"}, a
     * {@code plugin.xml} id).
     */
    private static final Map<String, List<String>> PROMISED = Map.ofEntries(
            // init.lua switches (README, Settings: "In init.lua")
            Map.entry("vim.g.neovim4eclipse_ide_keys", List.of(LUA, "vim.g.neovim4eclipse_ide_keys")),
            Map.entry("vim.g.neovim4eclipse_folds", List.of(LUA, "vim.g.neovim4eclipse_folds")),
            Map.entry("vim.g.neovim4eclipse_problems", List.of(LUA, "vim.g.neovim4eclipse_problems")),
            Map.entry("vim.g.neovim4eclipse_search_highlight", List.of(LUA, "vim.g.neovim4eclipse_search_highlight")),
            Map.entry("vim.g.neovim4eclipse_highlights", List.of(LUA, "vim.g.neovim4eclipse_highlights")),
            Map.entry("vim.g.neovim4eclipse_jump_label_group_prefixes", List.of(LUA, "vim.g.neovim4eclipse_jump_label_group_prefixes")),
            Map.entry("vim.g.neovim4eclipse_floating_windows", List.of(LUA, "vim.g.neovim4eclipse_floating_windows")),
            Map.entry("vim.g.neovim4eclipse_task_tags", List.of(LUA, "vim.g.neovim4eclipse_task_tags")),
            // set by the plug-in, read by a user's config
            // In backticks: every vim.g.neovim4eclipse_* row of the README contains the bare name.
            Map.entry("`vim.g.neovim4eclipse`", List.of(SRC + "util/NvimExecutable.java", "GUARD_VARIABLE = \"neovim4eclipse\"")),
            Map.entry("Neovim4EclipseFolds", List.of(LUA, "pattern = 'Neovim4EclipseFolds'")),
            Map.entry("`neovim4eclipse`", List.of(LUA, "nvim_create_namespace('neovim4eclipse')")),
            // Ex commands
            Map.entry(":Eclipse", List.of(LUA, "nvim_create_user_command('Eclipse'")),
            Map.entry(":Eproblems", List.of(LUA, "nvim_create_user_command('Eproblems'")),
            Map.entry(":Etasks", List.of(LUA, "nvim_create_user_command('Etasks'")),
            // startup options
            Map.entry("-Dneovim4eclipse.nvim", List.of(SRC + "util/NeovimOption.java", "\"neovim4eclipse.nvim\"")),
            Map.entry("-Dneovim4eclipse.logLevel", List.of(SRC + "util/LogLevelSwitch.java", "\"neovim4eclipse.logLevel\"")),
            // preference keys
            Map.entry("showToggleButton", List.of(PREFERENCES, "\"showToggleButton\"")),
            Map.entry("showMenu", List.of(PREFERENCES, "\"showMenu\"")),
            Map.entry("showWelcome", List.of(PREFERENCES, "\"showWelcome\"")),
            Map.entry("statusMode", List.of(PREFERENCES, "\"statusMode\"")),
            Map.entry("ctrlKeyNormal.", List.of(PREFERENCES, "\"ctrlKeyNormal.\"")),
            Map.entry("ctrlKeyInsert.", List.of(PREFERENCES, "\"ctrlKeyInsert.\"")),
            // Eclipse's own settings: key bindings, the color, the dedicated Neovim's folder
            Map.entry(".toggleMode", List.of(PLUGIN_XML, "id=\"" + COMMAND_ID + "toggleMode\"")),
            Map.entry(".attach", List.of(PLUGIN_XML, "id=\"" + COMMAND_ID + "attach\"")),
            Map.entry(".detach", List.of(PLUGIN_XML, "id=\"" + COMMAND_ID + "detach\"")),
            Map.entry(".restart", List.of(PLUGIN_XML, "id=\"" + COMMAND_ID + "restart\"")),
            Map.entry(".getNeovim", List.of(PLUGIN_XML, "id=\"" + COMMAND_ID + "getNeovim\"")),
            Map.entry(".openPreferences", List.of(PLUGIN_XML, "id=\"" + COMMAND_ID + "openPreferences\"")),
            Map.entry(".welcome", List.of(PLUGIN_XML, "id=\"" + COMMAND_ID + "welcome\"")),
            Map.entry(".reportProblem", List.of(PLUGIN_XML, "id=\"" + COMMAND_ID + "reportProblem\"")),
            Map.entry(".ctrl.a", List.of(PLUGIN_XML, "id=\"" + COMMAND_ID + "ctrl.a\"")),
            Map.entry(".ctrl.z", List.of(PLUGIN_XML, "id=\"" + COMMAND_ID + "ctrl.z\"")),
            Map.entry("io.github.jm6cnddxbwftaks.neovim4eclipse.searchMatch",
                    List.of(PLUGIN_XML, "id=\"io.github.jm6cnddxbwftaks.neovim4eclipse.searchMatch\"")),
            Map.entry("neovim4eclipse-plugin", List.of(SRC + "util/PluginFolders.java", "NAME = \"neovim4eclipse-plugin\"")));

    /**
     * Names removed, with the release that removed them, as the README's <i>Settings</i> lists them
     * under "Removed". Each had no effect left when it went: a stored value or an {@code init.lua}
     * line that still holds one is not read, and nothing fails (Eclipse keeps a preference key no
     * code asks for, unread). Example: {@code flashOnError}, whose flash Neovim's own error message
     * replaced. The code must not read them again: a user's old value would come back.
     */
    private static final Map<String, String> REMOVED = Map.of(
            "vim.g.neovim4eclipse_viewport", "0.3.3",
            "vim.g.neovim4eclipse_completion_menu", "0.3.0",
            "flashOnError", "0.3.0",
            "statusShowcmd", "0.3.0",
            "statusStatusline", "0.3.0",
            "statusStatuslineWidth", "0.3.0",
            "statusMaxWidthPercent", "0.3.0");

    /**
     * Names of the kinds checked for new ones, deliberately left out of the promise: they may
     * change. Each says why.
     */
    private static final Set<String> NOT_PROMISED = Set.of(
            "vim.g.neovim4eclipse_channel", // the RPC channel, for the plug-in's own scripts
            "vim.g.neovim4eclipse_help_page", // set in the help editor's own Neovim, never the user's
            "eclipseSplits", // unsupported: no control on any page (PreferenceConstants says so)
            "welcomeShownForVersion", // the plug-in's own record of which note it last showed
            "nvimExecutable"); // the preference before 0.2.1, read only to show a note once

    @Test
    void everyPromisedNameIsStillInTheCode() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : PROMISED.entrySet()) {
            String file = e.getValue().get(0);
            String spelling = e.getValue().get(1);
            if (!read(file).contains(spelling)) {
                missing.add(e.getKey() + " (" + spelling + " in " + file + ")");
            }
        }
        assertEquals(List.of(), missing, "promised names (README, Settings) gone from the code; a rename needs a"
                + " release that still accepts the old name first: see this test's comment");
    }

    @Test
    void everyPromisedNameIsInTheReadmeSettings() throws IOException {
        String settings = readmeSettings();
        List<String> missing = PROMISED.keySet().stream().filter(name -> !settings.contains(name)).sorted().toList();
        assertEquals(List.of(), missing, "promised names the README's Settings section does not list");
    }

    @Test
    void theCodeHasNoNameOfTheseKindsThatIsNeitherPromisedNorLeftOut() throws IOException {
        Set<String> found = new TreeSet<>();
        // init.lua switches: what the plug-in's Lua reads from vim.g
        collect(found, read(LUA), Pattern.compile("vim\\.g\\.(neovim4eclipse_[a-z_]+)"), "vim.g.");
        // preference keys: every String constant of PreferenceConstants
        collect(found, read(PREFERENCES), Pattern.compile("static final String [A-Z_]+ = \"([^\"]+)\""), "");
        // plugin.xml commands, for key bindings: the ones a user rebinds (Ctrl keys: a to z, checked above)
        collect(found, read(PLUGIN_XML), Pattern.compile("<command\\b[^>]*?\\bid=\"" + Pattern.quote(COMMAND_ID)
                + "([a-zA-Z]+)\""), ".");
        // Ex commands the plug-in defines
        collect(found, read(LUA), Pattern.compile("nvim_create_user_command\\('([A-Za-z]+)'"), ":");
        // colors users change in Colors and Fonts
        collect(found, read(PLUGIN_XML), Pattern.compile("<colorDefinition\\b[^>]*?\\bid=\"([^\"]+)\""), "");
        Set<String> promised = new TreeSet<>(PROMISED.keySet());
        promised.addAll(NOT_PROMISED);
        List<String> unknown = found.stream().filter(name -> !promised.contains(name)).toList();
        assertEquals(List.of(), unknown, "names users set that this test neither promises nor leaves out:"
                + " add each to PROMISED (and the README's Settings) or NOT_PROMISED");
    }

    /** The problem report asks Neovim about every promised init.lua setting, and only those ({@link ProblemReport#INIT_SETTINGS}). */
    @Test
    void theProblemReportAsksAboutEveryInitLuaSetting() {
        Set<String> promised = new TreeSet<>();
        PROMISED.keySet().stream().filter(name -> name.startsWith("vim.g.neovim4eclipse_"))
                .forEach(name -> promised.add(name.substring("vim.g.".length())));
        assertEquals(promised, new TreeSet<>(ProblemReport.INIT_SETTINGS));
    }

    /**
     * Every letter's Ctrl key command is there: a user's key binding for one is stored under its id,
     * and the README promises {@code .ctrl.a} to {@code .ctrl.z} (PROMISED checks only the two ends).
     */
    @Test
    void everyCtrlKeyCommandIsStillThere() throws IOException {
        String xml = read(PLUGIN_XML);
        List<String> missing = VimCtrlKeys.LETTERS.chars().mapToObj(c -> VimCtrlKeys.commandId((char) c))
                .filter(id -> !xml.contains("id=\"" + id + "\"")).toList();
        assertEquals(List.of(), missing, "Ctrl key commands gone from plugin.xml");
    }

    @Test
    void aRemovedNameIsGoneFromTheCodeAndListedInTheReadme() throws IOException {
        // Every source file of the plug-in and plugin.xml: a name can be read anywhere, in any spelling
        // (vim.g.x, vim.g['x'], a key in another class), so the bare name is looked for, as a word.
        StringBuilder all = new StringBuilder(read(PLUGIN_XML));
        try (var files = Files.walk(repoRoot().resolve(SRC))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                all.append('\n').append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        String code = all.toString();
        String settings = readmeSettings();
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> e : REMOVED.entrySet()) {
            String bare = e.getKey().startsWith("vim.g.") ? e.getKey().substring("vim.g.".length()) : e.getKey();
            if (Pattern.compile("\\b" + Pattern.quote(bare) + "\\b").matcher(code).find()) {
                wrong.add(e.getKey() + ": still in the plug-in's code (a removed name is not read, nor named in a comment)");
            }
            if (!settings.contains("`" + e.getKey() + "` (" + e.getValue() + ")")) {
                wrong.add(e.getKey() + ": not listed as `" + e.getKey() + "` (" + e.getValue() + ") in the README");
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void theReadmeSaysTheNamesStay() throws IOException {
        assertTrue(readmeSettings().contains("These names stay"), "the README's Settings section states the promise");
    }

    private static void collect(Set<String> into, String text, Pattern pattern, String prefix) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            into.add(prefix + m.group(1));
        }
    }

    /** The README's {@code ## Settings} section, up to the next {@code ## } heading. */
    private static String readmeSettings() throws IOException {
        String readme = read("README.md");
        int start = readme.indexOf("\n## Settings\n");
        assertTrue(start >= 0, "README.md has a section ## Settings");
        int end = readme.indexOf("\n## ", start + 1);
        return readme.substring(start, end < 0 ? readme.length() : end);
    }

    private static String read(String relative) throws IOException {
        return Files.readString(repoRoot().resolve(relative), StandardCharsets.UTF_8);
    }

    /** The repository root, found by walking up from the working directory to the root {@code pom.xml}'s folder. */
    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path d = dir; d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve("README.md")) && Files.isDirectory(d.resolve(PLUGIN))) {
                return d;
            }
        }
        throw new IllegalStateException("repository root not found above " + dir);
    }

    /**
     * The README's row of {@code vim.g.neovim4eclipse_jump_label_group_prefixes} names the
     * built-in prefixes, exactly those of {@link JumpLabels#BUILT_IN_PREFIXES} and in its order:
     * that list is the one place that decides them, and the row must not promise another set.
     */
    @Test
    void theReadmeNamesExactlyTheBuiltInJumpLabelPrefixes() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("README.md"))) {
            root = root.getParent();
        }
        String row = Files.readAllLines(root.resolve("README.md")).stream()
                .filter(line -> line.startsWith("| `vim.g.neovim4eclipse_jump_label_group_prefixes")).findFirst().orElseThrow();
        String builtIn = row.substring(row.indexOf("built-in") + "built-in".length(), row.indexOf('('));
        List<String> named = new ArrayList<>();
        Matcher m = Pattern.compile("`([A-Za-z0-9]+)`").matcher(builtIn);
        while (m.find()) {
            named.add(m.group(1));
        }
        assertEquals(JumpLabels.BUILT_IN_PREFIXES, named, row);
    }
}
