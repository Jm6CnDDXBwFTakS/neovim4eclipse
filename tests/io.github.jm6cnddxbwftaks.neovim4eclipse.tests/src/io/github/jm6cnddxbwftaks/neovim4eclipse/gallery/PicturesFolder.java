// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.gallery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FilePaths;
import io.github.jm6cnddxbwftaks.neovim4eclipse.util.FilePaths.Platform;

/**
 * The folder the pictures' runs ({@code -Pgallery}, {@code -Pscreenshots}) keep the plug-in's data
 * and their own files in: one outside the developer's home, so no picture shows the account name.
 *
 * <h2>Why</h2>
 * A dialog about the dedicated Neovim shows its folder, and the UI tests keep that under
 * {@code target/}, inside the checkout, which usually sits in the home folder: a picture would read
 * {@code /home/<account>/src/neovim4eclipse/tests/...}. The pictures are shared (the Marketplace
 * images are public, the gallery goes to whoever reviews a release), so the test pom points these two
 * profiles' {@code XDG_DATA_HOME} and {@code test.isolated.root} at {@code pictures.home} instead:
 * {@code /tmp/neovim4eclipse-pictures} on Linux and macOS, {@code C:\Users\Public\neovim4eclipse-pictures}
 * on Windows (where the temp folder is under the account's own folder). A picture then reads, for
 * example, {@code /tmp/neovim4eclipse-pictures/data/neovim4eclipse-plugin/neovim/la}.
 *
 * <h2>Shared folder, checked</h2>
 * Both places are shared by every account on the machine, and the tests delete trees in this folder
 * (prefs/DedicatedNeovim). Another account could create it first, or put a link there to a folder of
 * yours, so {@link #root()} refuses a folder that is a link or that another account owns.
 *
 * <h2>Inside the home folder, no owner check</h2>
 * The ordinary UI tests use this folder too: {@code target/} in the checkout, which usually sits in
 * the home folder. No other ordinary account can write there, so none can have made the folder or a
 * link on its way, and {@link #root()} leaves out the owner check (not the link check) for a folder
 * inside the home folder ({@link #ownerChecked}). The check would be wrong there on Windows: an
 * administrator's elevated process makes its files owned by {@code BUILTIN\Administrators}, any other
 * process by the account, so a {@code target/} made by an elevated run would be refused by the next
 * run that is not elevated, and the other way round. In a shared folder an elevated and a plain run
 * still disagree; the refusal says to delete the folder.
 */
public final class PicturesFolder {

    private PicturesFolder() {
    }

    /**
     * {@code test.isolated.root}, once checked: a real folder (not a link), and outside the home
     * folder one owned by the account the tests run as. For the ordinary UI tests it is
     * {@code target/}.
     *
     * @throws AssertionError when the folder is a link, or outside the home folder another account's
     */
    public static Path root() throws IOException {
        String property = System.getProperty("test.isolated.root");
        if (property == null || property.isBlank() || property.startsWith("${")) {
            throw new AssertionError("test.isolated.root is not set (the test pom sets it)");
        }
        Path root = Path.of(property).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) {
            throw new AssertionError(root + " is a link: refusing to work in it (delete it and run again)");
        }
        Files.createDirectories(root);
        if (!ownerChecked(root.toString(), System.getProperty("user.home"), Platform.current())) {
            return root;
        }
        UserPrincipal owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
        UserPrincipal me = currentUser();
        if (!owner.equals(me)) {
            throw new AssertionError(root + " belongs to " + owner.getName() + ", not to " + me.getName()
                    + ": refusing to work in it (delete it, or set -Dpictures.home to a folder of yours)");
        }
        return root;
    }

    /**
     * Whether {@link #root()} checks the owner of {@code root}: only outside {@code home} (see the
     * class comment), by the one path rule, links resolved. Example on Windows, home
     * {@code C:\Users\you}: {@code c:/users/YOU/src/n4e/tests/x/target} is not checked,
     * {@code C:\Users\Public\neovim4eclipse-pictures} is, and so is a folder in the home folder that
     * is reached through a link to the shared one. No home ({@code null} or blank), or a home that
     * is a file system's root ({@code /} for an account such as {@code nobody}, or {@code C:\}, in
     * which every folder, {@code /tmp} too, would be "inside" it): checked.
     */
    static boolean ownerChecked(String root, String home, Platform platform) {
        if (home == null || home.isBlank()) {
            return true;
        }
        String canonicalHome = FilePaths.canonical(home, platform);
        return canonicalHome.endsWith("/") || !FilePaths.inside(root, home, platform);
    }

    /** A new, empty folder in {@link #root()}, such as {@code .../neovim4eclipse-pictures/n4e-gallery123}. */
    public static Path newTemp(String prefix) throws IOException {
        return Files.createTempDirectory(root(), prefix);
    }

    /**
     * The account the tests run as, as the file system names owners: the owner of a file just
     * created in the temp folder ({@code user.name} is spelled differently, e.g. without the
     * Windows domain).
     */
    private static UserPrincipal currentUser() throws IOException {
        Path probe = Files.createTempFile("n4e-owner", ".tmp");
        try {
            return Files.getOwner(probe);
        } finally {
            Files.delete(probe);
        }
    }
}
