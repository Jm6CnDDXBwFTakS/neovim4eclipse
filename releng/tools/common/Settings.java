// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * A tool's settings, in three layers, each overriding the one before:
 * <ol>
 *   <li>{@code settings.properties} beside the tool, tracked: what is the same for everyone (the
 *       repository, the workflow files, the update site's address).</li>
 *   <li>{@code local.properties} beside it, ignored by git ({@code .gitignore} ignores every
 *       {@code local.properties}): what differs per machine or person. A tracked
 *       {@code local.properties.example} lists the keys.</li>
 *   <li>Values given on the command line, such as {@code --next 0.3.0}.</li>
 * </ol>
 * For example, with {@code tests=--all} in {@code settings.properties} and {@code tests=} in a
 * {@code local.properties}, {@link #get get("tests")} is the empty string.
 */
public final class Settings {

    private final Properties values = new Properties();

    private Settings() {
    }

    /** The layers in {@code folder}, then {@code overrides}; a missing {@code local.properties} is fine. */
    public static Settings load(Path folder, Map<String, String> overrides) {
        Settings s = new Settings();
        s.read(folder.resolve("settings.properties"), true);
        s.read(folder.resolve("local.properties"), false);
        s.values.putAll(overrides);
        return s;
    }

    /** Settings from {@code text} alone, in the {@code .properties} format (for tests). */
    public static Settings of(String text) {
        Settings s = new Settings();
        try {
            s.values.load(new java.io.StringReader(text));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    /** The value of {@code key}, stripped; fails if no layer sets it. */
    public String get(String key) {
        String v = values.getProperty(key);
        if (v == null) {
            throw new ToolFailure("no setting " + key + " in settings.properties or local.properties");
        }
        return v.strip();
    }

    /** The value of {@code key}, or {@code fallback} when no layer sets it. */
    public String get(String key, String fallback) {
        String v = values.getProperty(key);
        return v == null ? fallback : v.strip();
    }

    private void read(Path file, boolean required) {
        if (!Files.isRegularFile(file)) {
            if (required) {
                throw new ToolFailure(file + " is missing");
            }
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(r);
        } catch (IOException e) {
            throw new ToolFailure("cannot read " + file + ": " + e.getMessage());
        }
    }
}
