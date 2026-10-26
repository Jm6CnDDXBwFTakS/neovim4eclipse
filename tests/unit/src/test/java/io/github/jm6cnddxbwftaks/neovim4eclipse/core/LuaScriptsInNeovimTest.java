// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import io.github.jm6cnddxbwftaks.neovim4eclipse.testing.TestWait;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.HostCommand;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.TestNeovim;

/**
 * Runs the Lua tests in {@code src/test/lua/*_spec.lua}: the plug-in's Lua scripts
 * ({@link LuaScripts}) inside a headless Neovim, without the Java session. See
 * {@code src/test/lua/harness.lua} for why and how.
 *
 * <p>Each spec file becomes a group in the JUnit report, each of its tests one JUnit test, run in
 * a Neovim of its own (about 50 ms each), so no test sees another's buffers or options. Tagged
 * {@code integration}: it needs a real Neovim, found as for the other integration tests
 * ({@code -Dnvim.path}, see {@link TestNeovim}).
 *
 * <p>The scripts are the Java constants as they are now, read by reflection and written to a Lua
 * module ({@code target/lua-tests/scripts.lua}) that the harness loads; so a test always
 * checks the text the session would send, and adding a script to {@link LuaScripts} needs no
 * change here. The module stays after the run, so a Lua test can be run by hand (see harness.lua).
 * Example of the module:
 *
 * <pre>
 * return { host_rules = [==[bdelete=buffers ...]==], scripts = { SETUP = [==[local chan = ...]==], ... } }
 * </pre>
 */
@Tag("integration")
class LuaScriptsInNeovimTest {

    /** Where the harness and the spec files are: {@code src/test/lua}, from the module's folder. */
    private static final Path LUA_DIR = Path.of(System.getProperty("lua.tests.dir", "src/test/lua")).toAbsolutePath();

    /** Where the module is written and Neovim runs: kept after the run, cleared by {@code mvn clean}. */
    private static final Path WORK = Path.of("target", "lua-tests").toAbsolutePath();

    private static String nvim;
    private static Path module;

    @BeforeAll
    static void writeTheScripts() throws Exception {
        assumeTrue(NvimExecutable.looksAvailable(TestNeovim.PATH), "nvim not available");
        nvim = NvimExecutable.resolve(TestNeovim.PATH);
        Files.createDirectories(WORK);
        module = WORK.resolve("scripts.lua");
        Files.writeString(module, scriptsModule(), StandardCharsets.UTF_8);
    }

    @TestFactory
    Stream<DynamicContainer> luaSpecs() throws IOException {
        List<Path> specs;
        try (Stream<Path> files = Files.list(LUA_DIR)) {
            specs = files.filter(p -> p.getFileName().toString().endsWith("_spec.lua")).sorted().toList();
        }
        assertFalse(specs.isEmpty(), "no *_spec.lua in " + LUA_DIR.toAbsolutePath());
        return specs.stream().map(spec -> {
            List<String> names = testNames(spec);
            // The harness gets the test's number (1 is the first), not its name: a name may hold a
            // double quote ('"+p asks Eclipse ...'), and on Windows ProcessBuilder does not escape
            // one, so the command line would split the name there and the harness would not find it.
            return DynamicContainer.dynamicContainer(spec.getFileName().toString(), IntStream.rangeClosed(1, names.size())
                    .mapToObj(n -> DynamicTest.dynamicTest(names.get(n - 1), () -> runOne(spec, n))));
        });
    }

    /** The spec's test names, as the harness lists them. */
    private static List<String> testNames(Path spec) {
        Run run = nvim(spec, "--list");
        assertEquals(0, run.exit(), "listing the tests of " + spec + " failed:\n" + run.output());
        List<String> names = new ArrayList<>();
        for (String line : run.output().split("\\R")) {
            if (line.startsWith("TEST\t")) {
                names.add(line.substring(5));
            }
        }
        assertFalse(names.isEmpty(), spec + " declares no test (t.test)");
        return names;
    }

    private static void runOne(Path spec, int number) {
        Run run = nvim(spec, "--number", Integer.toString(number));
        if (run.exit() != 0 || !run.output().contains("PASS")) {
            fail("Lua test failed (exit " + run.exit() + "; module " + module + "):\n" + run.output());
        }
    }

    private record Run(int exit, String output) {
    }

    /**
     * Starts {@code nvim --clean --headless -l harness.lua module spec what...} ({@code --list}, or
     * {@code --number 3}) and returns its exit status and output (stdout and stderr together: the
     * harness's verdict and anything Neovim printed, such as an echoed message).
     */
    private static Run nvim(Path spec, String... what) {
        List<String> cmd = new ArrayList<>(List.of(nvim, "--clean", "--headless", "--cmd",
                "let g:" + NvimExecutable.GUARD_VARIABLE + "=1", "-l", LUA_DIR.resolve("harness.lua").toString(),
                module.toString(), spec.toString()));
        cmd.addAll(List.of(what));
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd).redirectErrorStream(true).directory(WORK.toFile());
            NvimExecutable.prepareEnvironment(builder.environment(), io.github.jm6cnddxbwftaks.neovim4eclipse.util.PlatformUtil.isWindows());
            Process process = builder.start();
            process.getOutputStream().close(); // no input: a script that waited for a key would hang
            // Read before waiting: a full pipe would block Neovim.
            byte[] out = process.getInputStream().readAllBytes();
            if (!process.waitFor(TestWait.millis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                fail("Neovim did not finish: " + String.join(" ", cmd));
            }
            return new Run(process.exitValue(), new String(out, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("could not start " + cmd, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    /** The Lua module the harness loads: every {@code static final String} of {@link LuaScripts}, and the host-command rules. */
    static String scriptsModule() throws IllegalAccessException {
        StringBuilder lua = new StringBuilder("return {\n  host_rules = ").append(longString(HostCommand.rulesForNeovim()))
                .append(",\n  scripts = {\n");
        for (Field field : LuaScripts.class.getDeclaredFields()) {
            int m = field.getModifiers();
            if (Modifier.isStatic(m) && Modifier.isFinal(m) && field.getType() == String.class) {
                field.setAccessible(true);
                lua.append("    ").append(field.getName()).append(" = ").append(longString((String) field.get(null))).append(",\n");
            }
        }
        return lua.append("  },\n}\n").toString();
    }

    /**
     * {@code text} as a Lua long string, {@code [==[...]==]}, with as many {@code =} as it takes for
     * the closing bracket not to occur in the text. A long string takes the text as it is: no
     * escapes are read, so the script reaches Neovim exactly as {@code nvim_exec_lua} would get it.
     * (A newline right after the opening bracket would be dropped, so none is put there.)
     */
    static String longString(String text) {
        String level = "";
        while (text.contains("]" + level + "]")) {
            level += "=";
        }
        return "[" + level + "[" + text + "]" + level + "]";
    }

    // A check of the module itself, so a broken one fails here and not as every Lua test at once.
    @org.junit.jupiter.api.Test
    void theModuleHasEveryScript() throws Exception {
        String lua = scriptsModule();
        assertTrue(lua.contains("    SETUP = [") && lua.contains("    HOST_COMMANDS = [") && lua.contains("    DOCUMENT_BUFFER = ["), lua);
        assertEquals("[=[a]]b]=]", longString("a]]b"));
    }
}
