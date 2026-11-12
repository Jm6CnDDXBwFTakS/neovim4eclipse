// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text of <i>Neovim → Report a Problem…</i>: what a bug report needs besides the steps, gathered
 * so that nobody has to look it up (versions, which Neovim, the settings changed, the last
 * warnings). It is pasted into a GitHub issue, so it is Markdown, and it holds nothing of the
 * user's files: no text, no file name (the active editor is described by its kind,
 * {@link RepairReport#fileKind}), and the home folder written as {@code ~} ({@link #withoutHome}),
 * which keeps the account's name out of paths. Example:
 *
 * <pre>
 * ### Neovim for Eclipse problem report
 *
 * - Neovim for Eclipse: 0.2.16.v20261112
 * - Eclipse: 4.41.0 (Eclipse IDE for Java Developers)
 * - Java: 21.0.5 (Eclipse Adoptium)
 * - System: Linux 6.12 aarch64, SWT gtk
 * - Neovim: Found: dedicated Neovim 0.12.6, checked, ~/.local/share/neovim4eclipse-plugin/neovim/la/bin/nvim
 * - Running: Neovim 0.12.6
 * - NVIM_APPNAME: not set
 * - Errors in the config at start: none
 * - init.lua settings: vim.g.neovim4eclipse_folds = 0
 * - Preferences changed: statusStatusline = true
 * - Active editor: a .java file in the Java Editor, Neovim mode on
 *
 * Recent warnings (the last 20 of this run):
 * ```text
 * 14:03:12 WARNING TextRepairs: …
 * ```
 * </pre>
 *
 * Pure Java, unit-tested without Eclipse; {@code editor/ReportProblemHandler} gathers the facts.
 *
 * @param plugin           this plug-in's version
 * @param eclipse          the platform's version and the product's name
 * @param java             the Java version and vendor
 * @param system           the operating system, its version and architecture, and SWT's port
 * @param neovim           what the plug-in found to run, as the preference page says it ({@code NvimProbe})
 * @param running          the running Neovim's version, or {@code ""} when none runs
 * @param appNameSet       whether {@code NVIM_APPNAME} is set (its value is the user's own name for a config)
 * @param startupErrors    whether the user's config reported errors when Neovim started
 * @param initSettings     the plug-in's {@code init.lua} settings that are set, name to value as Neovim
 *                         shows it ({@code vim.inspect}); {@code null} when Neovim could not be asked
 * @param preferences      the preferences that differ from their defaults, as {@code key = value}
 * @param editor           the active editor's kind and mode, or {@code ""} when none is attached
 * @param warnings         the last warnings ({@link RecentWarnings}), oldest first
 */
public record ProblemReport(String plugin, String eclipse, String java, String system, String neovim, String running,
        boolean appNameSet, boolean startupErrors, Map<String, String> initSettings, List<String> preferences,
        String editor, List<String> warnings) {

    /**
     * The {@code init.lua} settings the report asks Neovim about: those of the README's Settings
     * table that a user sets ({@code SettingsNamesTest} keeps the two lists equal).
     */
    public static final List<String> INIT_SETTINGS = List.of("neovim4eclipse_ide_keys", "neovim4eclipse_folds",
            "neovim4eclipse_viewport", "neovim4eclipse_problems", "neovim4eclipse_search_highlight",
            "neovim4eclipse_task_tags");

    /** The text to paste into an issue, with {@code home} written as {@code ~} throughout. */
    public String text(String home) {
        StringBuilder t = new StringBuilder("### Neovim for Eclipse problem report\n\n");
        item(t, "Neovim for Eclipse", plugin);
        item(t, "Eclipse", eclipse);
        item(t, "Java", java);
        item(t, "System", system);
        item(t, "Neovim", neovim);
        item(t, "Running", running.isBlank() ? "no Neovim is running" : "Neovim " + running);
        item(t, "NVIM_APPNAME", appNameSet ? "set" : "not set");
        item(t, "Errors in the config at start", running.isBlank() ? "(no Neovim running)" : startupErrors ? "yes" : "none");
        item(t, "init.lua settings", initSettings == null ? "(Neovim could not be asked)"
                : initSettings.isEmpty() ? "none set"
                : String.join(", ", initSettings.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(e -> e.getKey() + " = " + e.getValue()).toList()));
        item(t, "Preferences changed", preferences.isEmpty() ? "none" : String.join(", ", preferences.stream().sorted().toList()));
        item(t, "Active editor", editor.isBlank() ? "none attached" : editor);
        t.append("\nRecent warnings (the last ").append(RecentWarnings.CAPACITY).append(" of this run):\n");
        if (warnings.isEmpty()) {
            t.append("none\n");
        } else {
            t.append("```text\n");
            warnings.forEach(w -> t.append(w).append('\n'));
            t.append("```\n");
        }
        return withoutHome(t.toString(), home);
    }

    private static void item(StringBuilder t, String name, String value) {
        t.append("- ").append(name).append(": ").append(value == null || value.isBlank() ? "unknown" : value).append('\n');
    }

    /**
     * {@code text} with every spelling of the home folder {@code home} as {@code ~}: as it is, with
     * {@code /} for {@code \} (a Windows path can come either way), in any case (Windows and macOS
     * ignore it), and only where a path goes on or ends there. Examples, with {@code home}
     * {@code /home/pat}: {@code /home/pat/a.txt} is {@code ~/a.txt}, {@code /home/patricia} stays;
     * with {@code C:\Users\pat}: {@code c:/users/pat/x} is {@code ~/x}.
     */
    public static String withoutHome(String text, String home) {
        if (home == null || home.isBlank() || home.equals("/")) {
            return text;
        }
        String stripped = home.endsWith("/") || home.endsWith("\\") ? home.substring(0, home.length() - 1) : home;
        String result = text;
        for (String spelling : List.of(stripped, stripped.replace('\\', '/'), stripped.replace('/', '\\'))) {
            Pattern p = Pattern.compile(Pattern.quote(spelling) + "(?=[\\\\/]|$|[^\\w.-])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            result = p.matcher(result).replaceAll(Matcher.quoteReplacement("~"));
        }
        return result;
    }
}
