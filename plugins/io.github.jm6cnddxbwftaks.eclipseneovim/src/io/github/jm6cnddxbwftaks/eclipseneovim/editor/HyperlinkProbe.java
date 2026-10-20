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
 * Tells whether the Generic Editor knows a link at an offset (a Language Server's definition, for
 * example), without opening it. {@code gd} asks this before it runs the Generic Editor's F3.
 *
 * <p><b>Why this is needed.</b> The Generic Editor binds F3 to Open Hyperlink, which is how Language
 * Server files go to a definition. Where no link is under the caret (a word in a plain {@code .txt}
 * file) Open Hyperlink does nothing useful, and the viewer has no public way to ask first.
 * Hyperlinks come from <em>hyperlink detectors</em>, contributed through the
 * {@code org.eclipse.ui.workbench.texteditor.hyperlinkDetectors} extension point for a
 * <em>target</em>; creating that target's detectors from
 * {@link EditorsUI#getHyperlinkDetectorRegistry()} finds the same links the editor would.
 *
 * <p><b>Only the Generic Editor's own target.</b> The Generic Editor also uses the plain text
 * editor's detectors (target {@code org.eclipse.ui.DefaultTextEditor}: URLs, file paths). They are
 * left out on purpose: in the plain Text Editor {@code gd} never follows such links (that is
 * Ctrl+click), so a {@code .txt} file behaves the same in both editors.
 *
 * <p>Example: with a Language Server for the file, the caret on a function's name gives
 * {@code true}; in a plain text file, {@code false}. UI thread only.
 */
final class HyperlinkProbe {

    private static final Logger LOG = Logger.getLogger(HyperlinkProbe.class.getName());

    /** Hyperlink detector target of the Generic Editor, where Language Server plug-ins contribute. */
    static final String GENERIC_EDITOR_TARGET = "org.eclipse.ui.genericeditor.GenericEditor";

    private HyperlinkProbe() {
    }

    /** Whether a detector of the Generic Editor's target finds a link at document offset {@code offset}. */
    static boolean isAt(ITextEditor editor, ITextViewer viewer, int offset) {
        IHyperlinkDetector[] detectors;
        try {
            detectors = EditorsUI.getHyperlinkDetectorRegistry().createHyperlinkDetectors(GENERIC_EDITOR_TARGET, editor);
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
