// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The one table of the IDE keys ({@link IdeKeys}): Neovim maps from it ({@code setup_spec.lua}) and
 * Eclipse runs from it ({@code editor/IdeActions}, UI tests). Here: the table holds together.
 */
class IdeKeysTest {

    /** The action names {@link IdeAction} defines (its {@code public static final String} constants). */
    private static Set<String> definedActions() throws IllegalAccessException {
        Set<String> names = new HashSet<>();
        for (Field field : IdeAction.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                names.add((String) field.get(null));
            }
        }
        return names;
    }

    @Test
    void everyKeySendsAnActionIdeActionDefines() throws Exception {
        // A command action is run from the table; the others (gt, gT) by editor/IdeActions itself.
        Set<String> defined = definedActions();
        for (IdeKeys.Key key : IdeKeys.KEYS) {
            assertTrue(defined.contains(key.action()), key.lhs() + " sends " + key.action() + ", which IdeAction does not define");
        }
        assertTrue(defined.containsAll(IdeKeys.COMMANDS.keySet()), "every command action is an IdeAction");
    }

    @Test
    void everyCommandHasAKeyAndAWay() {
        Set<String> sent = new HashSet<>();
        IdeKeys.KEYS.forEach(k -> sent.add(k.action()));
        IdeKeys.COMMANDS.forEach((action, command) -> {
            assertTrue(sent.contains(action), action + " has no key");
            assertFalse(command.ways().isEmpty(), action + " has no way to find its command");
            assertFalse(command.label().isBlank(), action + " has no label");
        });
    }

    @Test
    void eachKeyIsMappedOncePerMode() {
        Set<String> seen = new HashSet<>();
        for (IdeKeys.Key key : IdeKeys.KEYS) {
            for (char mode : key.modes().toCharArray()) {
                assertTrue(seen.add(mode + key.lhs()), key.lhs() + " twice in mode " + mode);
                assertTrue("nx".indexOf(mode) >= 0, key.lhs() + ": mode " + mode);
            }
        }
    }

    @Test
    void neovimsLanguageKeysRunEclipsesCounterparts() {
        // Neovim 0.12's defaults for a language server (:help grn); grx (code lens) has no counterpart.
        assertEquals(List.of("grn", "gra", "grr", "gri", "grt", "gO"), IdeKeys.KEYS.stream().map(IdeKeys.Key::lhs)
                .filter(lhs -> lhs.startsWith("gr") || lhs.equals("gO")).toList());
        assertEquals("nx", IdeKeys.KEYS.stream().filter(k -> k.lhs().equals("gra")).findFirst().orElseThrow().modes(),
                "gra in visual mode too, as Neovim's own");
        assertTrue(IdeKeys.KEYS.stream().noneMatch(k -> k.lhs().equals("grx")));
    }

    @Test
    void onlyOpenDeclarationFallsBackToNeovim() {
        // gd without an Eclipse command is Vim's "go to local declaration"; the others show a message.
        IdeKeys.COMMANDS.forEach((action, command) -> assertEquals(
                action.equals(IdeAction.DEFINITION) ? "gd" : "", command.neovimKeys(), action));
    }

    @Test
    void aKeyDescribedByItsCommandsLabel() {
        IdeKeys.Key grn = IdeKeys.KEYS.stream().filter(k -> k.lhs().equals("grn")).findFirst().orElseThrow();
        assertEquals("Rename", grn.description());
        assertEquals(Optional.of("gd"), IdeKeys.keyOf(IdeAction.DEFINITION), "the first of gd and Ctrl-]");
        assertEquals(Optional.empty(), IdeKeys.keyOf("nothing"));
    }

    @Test
    void luaArgsAreFourStringsPerKey() {
        List<String> args = IdeKeys.toLuaArgs();
        assertEquals(4 * IdeKeys.KEYS.size(), args.size());
        assertEquals(List.of("gd", "n", "definition", "Open Declaration"), args.subList(0, 4));
        int gra = args.indexOf("gra");
        assertEquals(List.of("gra", "nx", "quick_fix", "Quick Fix"), args.subList(gra, gra + 4));
    }

    // ---- pick: the one loop, with a fake editor (no Eclipse, no waiting) ----

    /**
     * An editor whose answers the test sets: which command each Eclipse key runs, which commands can
     * run, which of those then fail to run (disabled between the check and the run), and which
     * detectors find a link. Records what ran.
     */
    private static final class FakeEditor implements IdeKeys.Editor {
        final java.util.Map<String, String> bound = new java.util.HashMap<>();
        final Set<String> runnable = new HashSet<>();
        final Set<String> failOnRun = new HashSet<>();
        final Set<String> links = new HashSet<>();
        boolean anyLink;
        final List<String> ran = new java.util.ArrayList<>();

        @Override
        public String commandBoundTo(String eclipseKey) {
            return bound.get(eclipseKey);
        }

        @Override
        public boolean runsHere(String id) {
            return runnable.contains(id);
        }

        @Override
        public boolean execute(String id) {
            if (failOnRun.contains(id)) {
                return false;
            }
            ran.add(id);
            return true;
        }

        @Override
        public boolean anyLinkAt() {
            return anyLink;
        }

        @Override
        public boolean linkAt(String detectorId) {
            return links.contains(detectorId);
        }

        @Override
        public boolean openLink(String detectorId) {
            if (links.contains(detectorId)) {
                ran.add("link " + detectorId);
                return true;
            }
            return false;
        }
    }

    private static IdeKeys.Choice pick(String action, IdeKeys.Editor editor) {
        return IdeKeys.pick(action, IdeKeys.COMMANDS.get(action), editor);
    }

    private static IdeKeys.Choice choice(IdeKeys.Choice.Kind kind, String value) {
        return new IdeKeys.Choice(kind, value);
    }

    @Test
    void theCommandBoundToTheKeyRuns() {
        FakeEditor java = new FakeEditor();
        java.bound.put("F3", "jdt.open");
        java.runnable.add("jdt.open");
        assertEquals(choice(IdeKeys.Choice.Kind.COMMAND, "jdt.open"), pick(IdeAction.DEFINITION, java));
        assertEquals(List.of("jdt.open"), java.ran);
    }

    @Test
    void gdIsNeovimsWhereNoCommandRuns() {
        FakeEditor text = new FakeEditor(); // F3 bound to nothing
        assertEquals(choice(IdeKeys.Choice.Kind.NEOVIM, "gd"), pick(IdeAction.DEFINITION, text));
        FakeEditor other = new FakeEditor(); // bound, but another plug-in's command, not handled here
        other.bound.put("F3", "other.open");
        assertEquals(choice(IdeKeys.Choice.Kind.NEOVIM, "gd"), pick(IdeAction.DEFINITION, other));
        assertEquals(List.of(), other.ran);
    }

    @Test
    void aCommandThatFailsToRunAfterTheCheckPassesToTheFallback() {
        // runsHere said yes, then execute said no (its handler was disabled meanwhile): gd is Neovim's,
        // grn says it is not available, as when the check had said no.
        FakeEditor java = new FakeEditor();
        java.bound.put("F3", "jdt.open");
        java.bound.put("M2+M3+R", "jdt.rename");
        java.runnable.addAll(Set.of("jdt.open", "jdt.rename"));
        java.failOnRun.addAll(Set.of("jdt.open", "jdt.rename"));
        assertEquals(choice(IdeKeys.Choice.Kind.NEOVIM, "gd"), pick(IdeAction.DEFINITION, java));
        assertEquals(choice(IdeKeys.Choice.Kind.MESSAGE, "grn: Rename is not available in this editor"),
                pick(IdeAction.RENAME, java));
        assertEquals(List.of(), java.ran);
    }

    @Test
    void aCommandThatFailsToRunPassesToTheNextWay() {
        IdeKeys.Command twoWays = new IdeKeys.Command("Open Declaration",
                List.of(IdeKeys.Way.command("first"), IdeKeys.Way.command("second")), "gd");
        FakeEditor editor = new FakeEditor();
        editor.runnable.addAll(Set.of("first", "second"));
        editor.failOnRun.add("first");
        assertEquals(choice(IdeKeys.Choice.Kind.COMMAND, "second"), IdeKeys.pick(IdeAction.DEFINITION, twoWays, editor));
        assertEquals(List.of("second"), editor.ran);
    }

    @Test
    void openHyperlinkCountsOnlyWithALinkAtTheCaret() {
        // The Generic Editor binds F3 to Open Hyperlink: a Language Server file's definition, or nothing.
        FakeEditor generic = new FakeEditor();
        generic.bound.put("F3", IdeKeys.OPEN_HYPERLINK);
        generic.runnable.add(IdeKeys.OPEN_HYPERLINK);
        assertEquals(choice(IdeKeys.Choice.Kind.NEOVIM, "gd"), pick(IdeAction.DEFINITION, generic), "a plain .txt word");
        generic.anyLink = true;
        assertEquals(choice(IdeKeys.Choice.Kind.COMMAND, IdeKeys.OPEN_HYPERLINK), pick(IdeAction.DEFINITION, generic));
    }

    @Test
    void grtOpensTheDetectorsLinkElseSaysSo() {
        String detector = IdeKeys.COMMANDS.get(IdeAction.TYPE_DEFINITION).ways().get(0).value();
        FakeEditor java = new FakeEditor();
        assertEquals(choice(IdeKeys.Choice.Kind.MESSAGE, "grt: Open Declared Type is not available in this editor"),
                pick(IdeAction.TYPE_DEFINITION, java), "no link: a keyword");
        java.links.add(detector);
        assertEquals(choice(IdeKeys.Choice.Kind.LINK, detector), pick(IdeAction.TYPE_DEFINITION, java));
        assertEquals(List.of("link " + detector), java.ran);
    }

    @Test
    void aDryRunRunsNothingAndAnswersAsTheRunWould() {
        FakeEditor java = new FakeEditor();
        java.bound.put("M2+M3+R", "jdt.rename");
        java.runnable.add("jdt.rename");
        String detector = IdeKeys.COMMANDS.get(IdeAction.TYPE_DEFINITION).ways().get(0).value();
        java.links.add(detector);
        IdeKeys.Editor dry = IdeKeys.dryRun(java);
        assertEquals(choice(IdeKeys.Choice.Kind.COMMAND, "jdt.rename"), pick(IdeAction.RENAME, dry));
        assertEquals(choice(IdeKeys.Choice.Kind.LINK, detector), pick(IdeAction.TYPE_DEFINITION, dry));
        assertEquals(List.of(), java.ran, "nothing ran, nothing opened");
    }
}
