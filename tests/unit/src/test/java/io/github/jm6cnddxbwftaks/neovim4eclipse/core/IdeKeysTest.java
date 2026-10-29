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
}
