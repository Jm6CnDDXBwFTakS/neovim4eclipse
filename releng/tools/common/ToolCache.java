// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

/**
 * Where the developer tools under {@code releng/tools} keep their files: {@code .tool-cache/} at
 * the repository's root, one folder per tool. The one place a tool gets these names from.
 *
 * <p><b>The rule is by who writes, not by what it is.</b> {@code target/} belongs to Maven: only a
 * build writes there, and {@code ./mvnw clean} removes exactly what a build can make again.
 * {@code .tool-cache/} belongs to the tools: every file a tool writes goes into its folder here,
 * whatever kind it is (a download, a record that a step passed, a page to look at). A tool still
 * reads Maven's outputs from {@code target/} (the built update site, test reports, screenshots).
 *
 * <p>Why: the tools used to write under {@code target/}, so {@code clean} took with it what is slow
 * or impossible to make again by a build. Example: {@code ./mvnw clean verify -Pgallery} deleted
 * the fetched test plug-ins, and the gallery left their pictures out ("flash is not fetched");
 * the same {@code clean} deleted the Release tool's record that the local tests had passed.
 *
 * <p>The folder is git-ignored and safe to delete: everything in it comes back by a download (hash
 * checked, as always) or by running the tool again. {@code git clean -x} deletes it too.
 *
 * <p>Three readers outside the tools that use a Java 22 launcher hold a literal copy of a name, as
 * they cannot import this class: {@code LikeCi.java} (one file that runs on JDK 21), the tests'
 * shared {@code PinnedPlugins} (UI and integration tests) and the Lua tests' {@code harness.lua}
 * (other languages or modules). {@code ToolCacheTest} checks each copy equals the name here, and that no tool writes
 * into {@code target/}.
 */
public final class ToolCache {

    /** The folder, relative to the repository's root. */
    public static final String FOLDER = ".tool-cache";

    /** {@code TestPlugins}: the fetched plug-ins, their archives, archives kept for vetting. */
    public static final String TEST_PLUGINS = FOLDER + "/test-plugins";

    /** {@code LikeCi}: the pinned Neovim (the Marketplace tool installs it there too). */
    public static final String LIKE_CI = FOLDER + "/like-ci";

    /** {@code Release} and {@code InstallCheck}: records that a step passed, downloaded release ZIPs. */
    public static final String RELEASE_TOOL = FOLDER + "/release-tool";

    /** {@code EclipseUpgrade}: platform archives, the unpacked Eclipse of a smoke install. */
    public static final String ECLIPSE_UPGRADE = FOLDER + "/eclipse-upgrade";

    /** {@code MavenUpgrade}: Maven and wrapper archives. */
    public static final String MAVEN_UPGRADE = FOLDER + "/maven-upgrade";

    /** {@code NeovimUpgrade}: the archives of the version being moved to, and their unpacked copies. */
    public static final String NEOVIM_UPGRADE = FOLDER + "/neovim-upgrade";

    /** {@code Dependencies}: the embedded library's new jar and, on a difference, its upstream license text. */
    public static final String DEPENDENCY_UPDATE = FOLDER + "/dependency-update";

    /** {@code Marketplace}: the listing page, logo renders, a new demo GIF. */
    public static final String MARKETPLACE = FOLDER + "/marketplace";

    private ToolCache() {
    }
}
