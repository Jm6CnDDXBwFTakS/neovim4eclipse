// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.AbstractHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlink;

/**
 * A stand-in for a Language Server's go-to-definition, registered in {@code fragment.xml} for the
 * Generic Editor's hyperlink target: the word {@value #WORD} is a link, nothing else is. Opening it
 * only counts ({@link #OPENED}). The word {@value #FAILING} is a link that throws when opened, for
 * the message a failed {@code grt} shows ({@code IdeKeysEndToEndTest}).
 */
public class TestHyperlinkDetector extends AbstractHyperlinkDetector {

    static final String WORD = "LINKME";
    static final String FAILING = "FAILME";
    /** This detector's id in {@code fragment.xml}. */
    static final String ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.tests.hyperlinkDetector";

    /** How many times a {@value #WORD} link was opened. */
    static final AtomicInteger OPENED = new AtomicInteger();

    @Override
    public IHyperlink[] detectHyperlinks(ITextViewer textViewer, IRegion region, boolean canShowMultipleHyperlinks) {
        IDocument document = textViewer.getDocument();
        if (document == null) {
            return null;
        }
        String text = document.get();
        for (String word : new String[] {WORD, FAILING}) {
            for (int start = text.indexOf(word); start >= 0; start = text.indexOf(word, start + 1)) {
                if (region.getOffset() >= start && region.getOffset() <= start + word.length()) {
                    return new IHyperlink[] { link(new Region(start, word.length()), word) };
                }
            }
        }
        return null;
    }

    private static IHyperlink link(IRegion region, String word) {
        return new IHyperlink() {
            @Override
            public IRegion getHyperlinkRegion() {
                return region;
            }

            @Override
            public String getTypeLabel() {
                return null;
            }

            @Override
            public String getHyperlinkText() {
                return "Test definition of " + word;
            }

            @Override
            public void open() {
                if (word.equals(FAILING)) {
                    throw new IllegalStateException("cannot open " + FAILING);
                }
                OPENED.incrementAndGet();
            }
        };
    }
}
