// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.resource.FontRegistry;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.ui.PlatformUI;

/**
 * The editors' font in the Marketplace pictures ({@code DemoRecordingTest}, {@code ScreenshotCaptureTest}):
 * JetBrains Mono NL, a monospaced font made for code (the NL variant has no ligatures, so {@code !=}
 * shows as typed), at 13 points, which stays legible when the Marketplace scales a picture down.
 * One font for all of them, so the demo and the screenshots look like one Eclipse. The Marketplace
 * tool checks that it is installed; on Debian and Ubuntu it is {@code fonts-jetbrains-mono}.
 *
 * <p>Example: {@code Map<String, FontData[]> before = PictureFont.useInEditors();} then, in a
 * {@code finally}, {@code PictureFont.restore(before);}.
 */
final class PictureFont {

    static final String FONT = "JetBrains Mono NL";
    static final int POINTS = 13;

    /** The text font every editor uses, and the Java editor's own, which follows it unless set. */
    private static final List<String> EDITOR_FONTS = List.of("org.eclipse.jface.textfont", "org.eclipse.jdt.ui.editors.textfont");

    private PictureFont() {
    }

    /** Sets {@link #FONT} at {@link #POINTS} as the editors' font; answers the fonts it replaced, for {@link #restore}. UI thread. */
    static Map<String, FontData[]> useInEditors() {
        FontRegistry fonts = registry();
        Map<String, FontData[]> before = new HashMap<>();
        for (String key : EDITOR_FONTS) {
            before.put(key, fonts.getFontData(key));
            fonts.put(key, new FontData[] { new FontData(FONT, POINTS, SWT.NORMAL) });
        }
        return before;
    }

    /** Puts back the fonts {@link #useInEditors} replaced. UI thread. */
    static void restore(Map<String, FontData[]> before) {
        FontRegistry fonts = registry();
        before.forEach(fonts::put);
    }

    private static FontRegistry registry() {
        return PlatformUI.getWorkbench().getThemeManager().getCurrentTheme().getFontRegistry();
    }
}
