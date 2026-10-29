// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.AbstractHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetectorExtension;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.HyperlinkDetectorDescriptor;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Finds the links Eclipse's editors offer on Ctrl+click (hyperlinks) at an offset: whether the
 * Generic Editor knows one ({@link #isAt}, which {@code gd} asks before it runs the Generic Editor's
 * F3), and one detector's link, opened ({@link #open}, for {@code grt}: JDT's "Open Declared Type" has
 * no command).
 *
 * <p><b>Why this is needed.</b> The Generic Editor binds F3 to Open Hyperlink, which is how Language
 * Server files go to a definition. Where no link is under the caret (a word in a plain {@code .txt}
 * file) Open Hyperlink does nothing useful, and the viewer has no public way to ask first.
 * Hyperlinks come from <em>hyperlink detectors</em>, contributed through the
 * {@code org.eclipse.ui.workbench.texteditor.hyperlinkDetectors} extension point for a
 * <em>target</em>, each with an id; creating them from {@link EditorsUI#getHyperlinkDetectorRegistry()}
 * finds the same links the editor would.
 *
 * <p><b>Only the Generic Editor's own target</b> for {@link #isAt}. The Generic Editor also uses the
 * plain text editor's detectors (target {@code org.eclipse.ui.DefaultTextEditor}: URLs, file paths).
 * They are left out on purpose: in the plain Text Editor {@code gd} never follows such links (that is
 * Ctrl+click), so a {@code .txt} file behaves the same in both editors.
 *
 * <p>Example: with a Language Server for the file, the caret on a function's name gives
 * {@code isAt} {@code true}; in a plain text file, {@code false}. In the Java editor, on a variable
 * {@code s} of type {@code String}, {@code open} with JDT's declared-type detector opens
 * {@code String}. UI thread only.
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
        return withFirstLink(detectors, viewer, offset, link -> { });
    }

    /**
     * Opens the first link the detector {@code detectorId} finds at {@code offset}, as Ctrl+click
     * would. {@code false} when that detector is not installed (no Java tooling), does not apply to
     * this editor, or finds no link there.
     */
    static boolean open(ITextEditor editor, ITextViewer viewer, int offset, String detectorId) {
        IHyperlinkDetector detector = detector(editor, detectorId);
        return detector != null
                && withFirstLink(new IHyperlinkDetector[] {detector}, viewer, offset, IHyperlink::open);
    }

    /** Whether the detector {@code detectorId} finds a link at {@code offset}, without opening it. */
    static boolean isAt(ITextEditor editor, ITextViewer viewer, int offset, String detectorId) {
        IHyperlinkDetector detector = detector(editor, detectorId);
        return detector != null && withFirstLink(new IHyperlinkDetector[] {detector}, viewer, offset, link -> { });
    }

    /** A new detector {@code detectorId} for {@code editor}, or {@code null} when it is not installed. */
    private static IHyperlinkDetector detector(ITextEditor editor, String detectorId) {
        IHyperlinkDetector detector = null;
        try {
            for (HyperlinkDetectorDescriptor descriptor : EditorsUI.getHyperlinkDetectorRegistry().getHyperlinkDetectorDescriptors()) {
                if (descriptor.getId().equals(detectorId)) {
                    detector = descriptor.createHyperlinkDetectorImplementation();
                    break;
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Hyperlink detector " + detectorId + " could not be created", e);
            return null;
        }
        if (detector instanceof AbstractHyperlinkDetector withContext) {
            withContext.setContext(editor); // the editor the detector works for, as the registry gives it
        }
        return detector;
    }

    /**
     * Gives {@code use} the first link any of {@code detectors} finds at {@code offset}, before the
     * detectors are disposed (a link may need its detector's editor); whether there was one.
     */
    private static boolean withFirstLink(IHyperlinkDetector[] detectors, ITextViewer viewer, int offset,
            Consumer<IHyperlink> use) {
        try {
            for (IHyperlinkDetector detector : detectors) {
                IHyperlink[] links;
                try {
                    links = detector.detectHyperlinks(viewer, new Region(offset, 0), false);
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Hyperlink detector failed: " + detector, e);
                    continue;
                }
                if (links != null && links.length > 0) {
                    use.accept(links[0]);
                    return true;
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
