// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.sync;

import java.util.List;

import org.eclipse.jface.text.IDocument;

import io.github.jm6cnddxbwftaks.neovim4eclipse.util.LineTextUtil;

/**
 * What the UI tests in other packages need from {@code sync/}'s package-private classes. The test
 * fragment shares the plug-in's packages, so a class of its own in this package can reach them,
 * as {@code core/SessionProbe} does for {@code core/}.
 */
public final class SyncProbe {

    private SyncProbe() {
    }

    /**
     * The lines Neovim's buffer should hold for {@code document}, as a push would write them (an
     * empty document is one empty line), without deciding its final newline. UI thread.
     */
    public static List<String> linesNeovimShouldHave(IDocument document) {
        return LineTextUtil.nonEmptyLines(FinalNewlines.linesForNeovim(document));
    }
}
