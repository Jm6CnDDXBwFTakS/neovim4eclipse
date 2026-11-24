// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The plug-in's Lua files ({@code core/lua/*.lua}, beside this class), read from the plug-in and
 * turned into the scripts {@code nvim_exec_lua} runs. {@link LuaScripts} keeps the short scripts
 * as Java text; the large ones, and every Neovim module the plug-in installs
 * ({@code neovim4eclipse.windows}, {@code neovim4eclipse.paths}, ...), are real files here, so that
 * an editor shows them as Lua, and no file comes near Java's limit for one string constant (65,535
 * bytes of UTF-8 in a class file).
 *
 * <p><b>How a file reaches Neovim</b>: as text, the same way as before it was a file. The file is
 * read from the plug-in's own jar through this class's class loader
 * ({@link Class#getResourceAsStream}: the bundle's class loader in Eclipse, the class path in
 * {@code tests/unit}; no Eclipse API, since {@code core/} is compiled without Eclipse), and
 * {@link #script} wraps it into a script that Neovim compiles as a chunk named after the file.
 * None of these files is written to disk for Neovim or put on {@code 'runtimepath'}, so no other
 * file can stand in for a module: not one in a cloned repository, nor one in the user's config or a
 * plug-in, which a {@code require} of a file on the runtimepath would find first. (The one Lua file
 * the plug-in does write there, {@code core/HealthCheck}'s {@code :checkhealth} stub, holds none
 * of this code; beside it are only the plug-in's help page and its tags, {@code core/PluginHelp}.)
 *
 * <p><b>Why a wrapper</b> rather than the file's text as it is: {@code nvim_exec_lua} names every
 * chunk the same, so an error would point at a line of "a script". Compiled with
 * {@code loadstring(text, '=neovim4eclipse/core/lua/paths.lua')}, Neovim's message names the file
 * and its line ({@code neovim4eclipse/core/lua/paths.lua:42: attempt to index a nil value}), which
 * is the line in this folder. The name starts with {@code =}, Lua's mark for a chunk named as it
 * is, not with {@code @}, its mark for a file: Neovim takes a {@code @} name for a file's path and
 * resolves it against its working folder, the project's, so {@code :verbose map} would say "Last
 * set from /ws/app/neovim4eclipse/core/lua/setup.lua", a file that is not there (or one a cloned
 * repository put there). With {@code =} it says "Last set from Lua", as for any
 * {@code nvim_exec_lua} (probed on 0.12.6). And a module made of several files (the windows module, in
 * {@code windows/}) is sent as one script whose files are each a chunk of their own, run in order:
 * each file's locals stay its own, under Lua's limit of 200 locals per function.
 *
 * <p>Example: {@code script("paths.lua")} gives
 * <pre>{@code
 * local n, args = select('#', ...), { ... }
 * return assert(loadstring([[
 * -- SPDX-FileCopyrightText: ...
 * ...]], '=neovim4eclipse/core/lua/paths.lua'))(unpack(args, 1, n))
 * }</pre>
 * Each file gets the script's arguments, and the script answers what the last file returns.
 *
 * <p>A file is read once, when {@link LuaScripts} loads. A file missing from the plug-in is a
 * broken build: the error names it.
 */
final class LuaModules {

    /** The folder of the files, beside this class. */
    static final String FOLDER = "lua/";

    /** What a chunk is named, after Lua's {@code =}: {@value} and the file, as Neovim's error messages show it. */
    static final String CHUNK_PREFIX = "neovim4eclipse/core/lua/";

    /**
     * A file name as {@link #script} takes it: lowercase letters, digits and {@code _}, in folders,
     * ending in {@code .lua}. Examples: {@code windows/floats.lua}, {@code ui2.lua}. Nothing else is read: no {@code ..}, no
     * absolute path.
     */
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*(/[a-z][a-z0-9_]*)*\\.lua");

    /** The files read so far, for {@code LuaModulesTest}: every file in the folder must be one. */
    private static final Set<String> READ = new TreeSet<>();

    private LuaModules() {
    }

    /**
     * The script that runs {@code files} in Neovim, in order, each as a chunk of its own, with the
     * script's arguments; it answers what the last one returns.
     *
     * @param files the files below {@link #FOLDER}, such as {@code "paths.lua"}
     * @throws IllegalArgumentException for no file, or a name {@link #NAME} refuses
     * @throws IllegalStateException    for a file missing from the plug-in
     */
    static String script(String... files) {
        if (files.length == 0) {
            throw new IllegalArgumentException("No Lua file to run");
        }
        StringBuilder lua = new StringBuilder("local n, args = select('#', ...), { ... }\n");
        for (int i = 0; i < files.length; i++) {
            // The last file's call is returned, so that all its answers are the script's.
            lua.append(i == files.length - 1 ? "return " : "")
                    .append("assert(loadstring(").append(longString(text(files[i])))
                    .append(", '=").append(CHUNK_PREFIX).append(files[i]).append("'))(unpack(args, 1, n))\n");
        }
        return lua.toString();
    }

    /**
     * The text of one file below {@link #FOLDER}, as the file holds it.
     *
     * @throws IllegalArgumentException for a name {@link #NAME} refuses
     * @throws IllegalStateException    for a file missing from the plug-in
     */
    static String text(String file) {
        if (!NAME.matcher(file).matches()) {
            throw new IllegalArgumentException("Not the name of one of the plug-in's Lua files: " + file);
        }
        try (InputStream in = LuaModules.class.getResourceAsStream(FOLDER + file)) {
            if (in == null) {
                throw new IllegalStateException("The plug-in's Lua file core/" + FOLDER + file + " is missing");
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            synchronized (READ) {
                READ.add(file);
            }
            return text;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the plug-in's Lua file core/" + FOLDER + file, e);
        }
    }

    /** The files {@link #text} has read, sorted; {@code LuaModulesTest} compares them with the folder. */
    static Set<String> read() {
        synchronized (READ) {
            return Set.copyOf(READ);
        }
    }

    /**
     * {@code text} as a Lua long string, {@code [==[...]==]}, with as many {@code =} as it takes for
     * the closing bracket not to occur in the text, nor to begin at its end (a text ending in
     * {@code ]} would close {@code [[...]]} one bracket early), and for the opening one not to occur
     * either: PUC Lua 5.1, which a Neovim may be built with instead of LuaJIT, refuses a {@code [[}
     * inside {@code [[...]]} ("nesting of [[...]] is deprecated"), even in a comment. A long string
     * takes the text as it is (no escapes are read), so the chunk is the file. The newline after the
     * opening bracket is one Lua drops, there so that a file's first line is the chunk's line 1
     * whatever it starts with.
     */
    static String longString(String text) {
        String level = "";
        while ((text + "]").contains("]" + level + "]") || text.contains("[" + level + "[")) {
            level += "=";
        }
        return "[" + level + "[\n" + text + "]" + level + "]";
    }
}
