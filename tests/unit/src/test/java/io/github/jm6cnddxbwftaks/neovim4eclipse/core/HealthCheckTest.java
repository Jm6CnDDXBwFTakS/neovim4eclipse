// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NeovimVersion;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.NvimExecutable;

/** What {@code :checkhealth neovim4eclipse} is given, and the stub file that lets Neovim find it. */
class HealthCheckTest {

    @Test
    void theInfoCarriesWhatOnlyEclipseKnows() {
        Map<String, Object> table = new HealthCheck.Info(true, "0.2.27", "4.38.0").toLua();
        assertEquals(NeovimVersion.MINIMUM.toString(), table.get("minimum"));
        assertEquals("dedicated", table.get("program"));
        assertEquals("0.2.27", table.get("plugin"));
        assertEquals("4.38.0", table.get("eclipse"));
        assertEquals(NvimExecutable.READERS_OFF, table.get("readers"));
        assertEquals("own", new HealthCheck.Info(false, "0.2.27", "").toLua().get("program"));
    }

    @Test
    void theStubIsTheOnlyFileAndIsWrittenAgainEachStart(@TempDir Path plugin) throws Exception {
        Path runtime = HealthCheck.runtimeFolder(plugin);
        assertEquals(plugin.resolve("runtime"), runtime);
        HealthCheck.writeStub(runtime);
        Path stub = runtime.resolve("lua").resolve("neovim4eclipse").resolve("health.lua");
        Files.writeString(stub, "changed", StandardCharsets.UTF_8);
        HealthCheck.writeStub(runtime);
        assertEquals(HealthCheck.STUB, Files.readString(stub, StandardCharsets.UTF_8), "replaced, not kept");
        try (var files = Files.walk(runtime)) {
            assertEquals(List.of(stub), files.filter(Files::isRegularFile).toList(),
                    "nothing else on the runtimepath: no plugin/ or ftplugin/ that would run");
        }
    }

    @Test
    void aFolderWithACommaWouldSplitOnTheRuntimepath() {
        assertEquals(true, HealthCheck.fitsRuntimepath(Path.of("/home/you/.local/share/neovim4eclipse-plugin/runtime")));
        assertEquals(false, HealthCheck.fitsRuntimepath(Path.of("/data,home/neovim4eclipse-plugin/runtime")));
    }
}
