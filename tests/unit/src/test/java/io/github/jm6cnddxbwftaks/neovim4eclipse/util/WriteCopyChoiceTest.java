// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link WriteCopyChoice}: what {@code :w name}, {@code :wq name} and {@code :x name} do, check by
 * check in Vim's order, and that the disk is asked only as far as needed. That Eclipse carries a
 * choice out (the copy written, E13 shown, the editor kept open) is {@code OtherFileWriteEndToEndTest}.
 */
class WriteCopyChoiceTest {

    private final List<String> asked = new ArrayList<>();

    /** A target that answers as given and records each question. */
    private WriteCopyChoice.Target target(boolean ownFile, boolean exists, boolean danglingLink) {
        return new WriteCopyChoice.Target() {
            @Override
            public boolean isOwnFile() {
                asked.add("own");
                return ownFile;
            }

            @Override
            public boolean exists() {
                asked.add("exists");
                return exists;
            }

            @Override
            public boolean isDanglingLink() {
                asked.add("dangling");
                return danglingLink;
            }
        };
    }

    /** {@code :x name} without unsaved changes writes nothing, and looks at nothing. */
    @Test
    void xWithoutChangesWritesNothing() {
        assertEquals(WriteCopyChoice.NOTHING, WriteCopyChoice.of(false, true, false, target(false, true, false)));
        assertEquals(WriteCopyChoice.NOTHING, WriteCopyChoice.of(false, true, true, null), "not even an unknown folder");
        assertEquals(List.of(), asked);
    }

    /** {@code :wq name} writes even without changes, as Vim's {@code :wq} does; {@code :x} with changes too. */
    @Test
    void wqWritesWithoutChangesAndXWithThem() {
        assertEquals(WriteCopyChoice.COPY, WriteCopyChoice.of(false, false, false, target(false, false, false)));
        assertEquals(WriteCopyChoice.COPY, WriteCopyChoice.of(true, true, false, target(false, false, false)));
    }

    @Test
    void aNameWithNoFolderCannotBeWritten() {
        assertEquals(WriteCopyChoice.NO_FOLDER, WriteCopyChoice.of(true, false, false, null));
        assertEquals(WriteCopyChoice.NO_FOLDER, WriteCopyChoice.of(true, false, true, null), "! does not help");
    }

    /** The editor's own file under another spelling is a save, even where it exists and without !. */
    @Test
    void theEditorsOwnFileIsASaveAndTheDiskIsNotAsked() {
        assertEquals(WriteCopyChoice.SAVE_OWN, WriteCopyChoice.of(true, false, false, target(true, true, false)));
        assertEquals(List.of("own"), asked);
    }

    /** Vim's E13: an existing file needs !. */
    @Test
    void anExistingFileNeedsBang() {
        assertEquals(WriteCopyChoice.FILE_EXISTS, WriteCopyChoice.of(true, false, false, target(false, true, false)));
        assertEquals(List.of("own", "exists"), asked, "E13 before the link is looked at");
        asked.clear();
        assertEquals(WriteCopyChoice.COPY, WriteCopyChoice.of(true, false, true, target(false, true, false)));
        assertEquals(List.of("own"), asked, "with ! the disk is not asked");
    }

    /** A link to a missing file is no file to {@code Files.exists}; without ! it is refused all the same. */
    @Test
    void aLinkToAMissingFileNeedsBang() {
        assertEquals(WriteCopyChoice.DANGLING_LINK, WriteCopyChoice.of(true, false, false, target(false, false, true)));
        assertEquals(WriteCopyChoice.COPY, WriteCopyChoice.of(true, false, true, target(false, false, true)));
    }

    @Test
    void aNewNameIsACopy() {
        assertEquals(WriteCopyChoice.COPY, WriteCopyChoice.of(true, false, false, target(false, false, false)));
        assertEquals(List.of("own", "exists", "dangling"), asked);
    }

    @Test
    void theE13TextIsVims() {
        assertEquals("E13: File exists (add ! to override)", WriteCopyChoice.E13);
    }
}
