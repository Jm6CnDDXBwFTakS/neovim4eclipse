// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * {@link LuaModules}: the plug-in's Lua files, as the plug-in reads them and sends them. That each
 * file does its work is tested in Lua ({@code src/test/lua/*_spec.lua}, {@link LuaScriptsInNeovimTest}).
 */
class LuaModulesTest {

    /** The folder in the plug-in's sources, from the repository root. */
    private static final String SOURCES =
            "plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/src/io/github/jm6cnddxbwftaks/neovim4eclipse/core/lua";

    /**
     * How a script names a file it runs: the chunk name after the file's long string, as
     * {@link LuaModules#script} writes it. Group 1 is the file.
     */
    private static final Pattern CHUNK = Pattern.compile(
            "\\]=*\\], '=" + Pattern.quote(LuaModules.CHUNK_PREFIX) + "([^']+)'\\)\\)\\(unpack\\(args, 1, n\\)\\)\n");

    /**
     * Every file in {@code core/lua/} is one that {@link LuaScripts} sends, and the other way round:
     * a file no script names would be dead code that still looks maintained, and a name with no
     * file would fail every session's start. The files named are read from the scripts' own text,
     * each field's, so what other tests read cannot hide a file no script sends. (A file left in
     * {@code target/} after its source was deleted is never read: only names the scripts give are.)
     */
    @Test
    void theScriptsSendEveryFileInTheFolderAndNoOther() throws IOException, IllegalAccessException {
        assertEquals(filesInSources(), filesNamedBy(scriptsOfLuaScripts()));
    }

    /** The check above sees a file only when a script runs it, not when the file is merely read. */
    @Test
    void aFileIsCountedOnlyWhereAScriptRunsIt() {
        assertEquals(Set.of("paths.lua"), filesNamedBy(List.of(LuaModules.script("paths.lua"))));
        assertEquals(Set.of(), filesNamedBy(List.of(LuaModules.text("paths.lua"),
                "-- an error reads neovim4eclipse/core/lua/folds.lua:120\n")));
    }

    /**
     * Every {@code String} of {@link LuaScripts} has its text when the class has loaded. A script
     * built from another one declared further down would otherwise hold {@code "null"} where that
     * one's text belongs: a field read from a file is no compile-time constant, so the compiler does
     * not fill it in; the fields are set in the order they are declared. (Lua has no {@code null}:
     * a line that starts with the word is where a missing text went.)
     */
    @Test
    void everyScriptHasItsTextOnceTheClassHasLoaded() throws IllegalAccessException {
        for (Field field : LuaScripts.class.getDeclaredFields()) {
            int m = field.getModifiers();
            if (Modifier.isStatic(m) && field.getType() == String.class) {
                field.setAccessible(true);
                String text = (String) field.get(null);
                assertNotNull(text, field.getName());
                assertFalse(text.startsWith("null") || text.contains("\nnull"), field.getName());
            }
        }
    }

    /** The conventions of a source file: the two SPDX lines first, Unix line ends, a last newline. */
    @Test
    void everyFileHasTheLicenseHeaderAndUnixLineEnds() throws IOException {
        for (String file : filesInSources()) {
            String text = LuaModules.text(file);
            assertTrue(text.startsWith("-- SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS\n"
                    + "-- SPDX-License-Identifier: EPL-2.0\n"), file);
            assertFalse(text.contains("\r"), file);
            assertTrue(text.endsWith("\n"), file);
        }
    }

    /** One file: its text as a chunk named after it, given the script's arguments, its answer returned. */
    @Test
    void aScriptRunsTheFileAsAChunkNamedAfterIt() {
        String script = LuaModules.script("paths.lua");
        assertTrue(script.startsWith("local n, args = select('#', ...), { ... }\n"
                + "return assert(loadstring([[\n" + LuaModules.text("paths.lua") + "]], "
                + "'=neovim4eclipse/core/lua/paths.lua'))(unpack(args, 1, n))\n"), script);
    }

    /** Several files: each a chunk of its own, in order, with the same arguments; the last one's answer. */
    @Test
    void aScriptOfSeveralFilesRunsEachInOrderAndAnswersWithTheLast() {
        String script = LuaModules.script("paths.lua", "paths.lua");
        String[] lines = script.split("\n");
        assertEquals("local n, args = select('#', ...), { ... }", lines[0]);
        assertTrue(lines[1].startsWith("assert(loadstring([["), lines[1]);
        int second = script.indexOf("\nreturn assert(loadstring([[\n");
        assertTrue(second > 0, script);
        assertEquals(2, script.split("'=neovim4eclipse/core/lua/paths.lua'\\)\\)\\(unpack\\(args, 1, n\\)\\)\n", -1).length - 1);
    }

    /** A long string's brackets: as many {@code =} as keep the text's own brackets from closing it. */
    @Test
    void aLongStringIsNotClosedByTheTextsOwnBrackets() {
        assertEquals("[[\nx = 1\n]]", LuaModules.longString("x = 1\n"));
        assertEquals("[=[\nlocal s = [[a]]\n]=]", LuaModules.longString("local s = [[a]]\n"));
        assertEquals("[==[\nt[[[=[x]=]]]\n]==]", LuaModules.longString("t[[[=[x]=]]]\n"));
        // A text ending in ] would close [[...]] one bracket early: x]]] ends at x]].
        assertEquals("[=[\nx]]=]", LuaModules.longString("x]"));
        // PUC Lua 5.1 refuses [[ inside [[...]], a comment's too.
        assertEquals("[=[\n-- see [[x\n]=]", LuaModules.longString("-- see [[x\n"));
    }

    /** Only a plain name of a file in the folder is read: no way up or out of it. */
    @Test
    void aNameOutsideTheFolderIsRefused() {
        for (String name : new String[] { "../LuaScripts.java", "/etc/x.lua", "lua/../../x.lua", "Paths.lua",
                "paths", "windows//init.lua", "" }) {
            assertThrows(IllegalArgumentException.class, () -> LuaModules.text(name), name);
        }
        assertThrows(IllegalArgumentException.class, LuaModules::script);
    }

    /** A file the plug-in lacks is a broken build: the error names it. */
    @Test
    void aMissingFileIsNamed() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> LuaModules.text("no_such_file.lua"));
        assertTrue(e.getMessage().contains("core/lua/no_such_file.lua"), e.getMessage());
    }

    /** Every {@code String} field of {@link LuaScripts}: the scripts the plug-in sends. */
    private static List<String> scriptsOfLuaScripts() throws IllegalAccessException {
        List<String> scripts = new ArrayList<>();
        for (Field field : LuaScripts.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                field.setAccessible(true);
                scripts.add((String) field.get(null));
            }
        }
        return scripts;
    }

    /** The files {@code scripts} run, as {@link #CHUNK} finds them. */
    private static Set<String> filesNamedBy(List<String> scripts) {
        Set<String> files = new TreeSet<>();
        for (String script : scripts) {
            Matcher m = CHUNK.matcher(script);
            while (m.find()) {
                files.add(m.group(1));
            }
        }
        return files;
    }

    /** The {@code .lua} files below {@code core/lua/} in the plug-in's sources, as {@link LuaModules#text} names them. */
    private static Set<String> filesInSources() throws IOException {
        Path folder = repoRoot().resolve(SOURCES);
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(folder)) {
            files.filter(f -> f.toString().endsWith(".lua"))
                    .forEach(f -> names.add(folder.relativize(f).toString().replace('\\', '/')));
        }
        return names;
    }

    /** The repository root, found by walking up from the working directory. */
    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path d = dir; d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve("README.md")) && Files.isDirectory(d.resolve(SOURCES))) {
                return d;
            }
        }
        throw new IllegalStateException("repository root not found above " + dir);
    }
}
