// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.util;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandLine.Kind;

/**
 * Up/Down history for the inline command line, kept separately for {@code :} commands and for
 * searches, as Vim does.
 *
 * <p>Navigation works like a shell: {@link #previous} walks back, {@link #next} walks forward and
 * finally returns the text that was being typed before navigation started (the "draft"). Adding an
 * entry resets navigation. Not thread-safe: UI thread only.
 */
public final class CommandHistory {

    private static final class Entries {
        final List<String> items = new ArrayList<>();
        int cursor; // index into items; items.size() means "the draft"
        String draft = "";
    }

    private final int maxEntries;
    private final Map<Kind, Entries> byKind = new EnumMap<>(Kind.class);

    public CommandHistory(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    private Entries of(Kind kind) {
        return byKind.computeIfAbsent(kind, k -> new Entries());
    }

    /** Remembers a submitted command (without its prefix). Blank entries are ignored; duplicates move to the end. */
    public void add(Kind kind, String entry) {
        if (entry == null || entry.isBlank()) {
            return;
        }
        Entries e = of(kind);
        e.items.remove(entry);
        e.items.add(entry);
        while (e.items.size() > maxEntries) {
            e.items.remove(0);
        }
        e.cursor = e.items.size();
        e.draft = "";
    }

    /**
     * One step back in time.
     *
     * @param currentText what is in the box now; kept as the draft when navigation starts
     * @return the older entry, or {@code null} if there is no history
     */
    public String previous(Kind kind, String currentText) {
        Entries e = of(kind);
        if (e.items.isEmpty()) {
            return null;
        }
        if (e.cursor >= e.items.size()) {
            e.draft = currentText == null ? "" : currentText;
            e.cursor = e.items.size();
        }
        if (e.cursor > 0) {
            e.cursor--;
        }
        return e.items.get(e.cursor);
    }

    /**
     * One step forward in time.
     *
     * @return the newer entry, the draft after the newest, or {@code null} if navigation was not started
     */
    public String next(Kind kind) {
        Entries e = of(kind);
        if (e.cursor >= e.items.size()) {
            return null;
        }
        e.cursor++;
        return e.cursor == e.items.size() ? e.draft : e.items.get(e.cursor);
    }

    /** Ends navigation without adding anything (the box was canceled). */
    public void resetNavigation(Kind kind) {
        Entries e = of(kind);
        e.cursor = e.items.size();
        e.draft = "";
    }
}
