// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetectorExtension;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Tells whether there is a hyperlink (a URL, a file path, ...) at an offset of a plain text editor,
 * without opening it.
 *
 * <p><b>Why this is needed.</b> Eclipse's Open Hyperlink command opens the link under the caret,
 * but where there is none it shows a dialog ("Select a hyperlink target"), and the viewer has no
 * public way to ask first. Hyperlinks come from <em>hyperlink detectors</em>, contributed through
 * the {@code org.eclipse.ui.workbench.texteditor.hyperlinkDetectors} extension point for a
 * <em>target</em>. The plain text editor, and editors built on it, use the target
 * {@value #TEXT_EDITOR_TARGET} ({@code TextSourceViewerConfiguration}), so creating that target's
 * detectors from {@link EditorsUI#getHyperlinkDetectorRegistry()} finds the same links.
 *
 * <p>Example: in a {@code .txt} file, the caret in {@code https://example.com} gives {@code true},
 * the caret in a plain word {@code false}. UI thread only.
 */
final class HyperlinkProbe {

    private static final Logger LOG = Logger.getLogger(HyperlinkProbe.class.getName());

    /** Hyperlink detector target of the plain text editor and of editors that extend it. */
    static final String TEXT_EDITOR_TARGET = "org.eclipse.ui.DefaultTextEditor";

    private HyperlinkProbe() {
    }

    /** Whether a detector of the text editor's target finds a link at document offset {@code offset}. */
    static boolean isAt(ITextEditor editor, ITextViewer viewer, int offset) {
        IHyperlinkDetector[] detectors;
        try {
            detectors = EditorsUI.getHyperlinkDetectorRegistry().createHyperlinkDetectors(TEXT_EDITOR_TARGET, editor);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Hyperlink detectors could not be created", e);
            return false;
        }
        try {
            for (IHyperlinkDetector detector : detectors) {
                try {
                    var links = detector.detectHyperlinks(viewer, new Region(offset, 0), false);
                    if (links != null && links.length > 0) {
                        return true;
                    }
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Hyperlink detector failed: " + detector, e);
                }
            }
            return false;
        } finally {
            for (IHyperlinkDetector detector : detectors) {
                if (detector instanceof IHyperlinkDetectorExtension extension) {
                    extension.dispose();
                }
            }
        }
    }
}
