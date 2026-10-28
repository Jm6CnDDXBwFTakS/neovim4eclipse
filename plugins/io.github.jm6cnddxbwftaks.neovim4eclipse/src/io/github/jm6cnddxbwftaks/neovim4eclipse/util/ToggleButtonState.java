// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.util;

/**
 * How the Neovim toolbar button (and its menu checkbox, <em>Neovim › Neovim Mode</em>) looks for
 * the active editor: greyed out, pressed, or raised, which icon it shows, and what its tooltip says. A pure decision,
 * so it is unit-tested without Eclipse; {@code editor/ToggleNeovimModeHandler} applies it.
 *
 * <pre>
 *   not a text editor       greyed out  color logo  "Neovim mode unavailable: not a text editor."
 *   no editor open          greyed out  color logo  "Neovim mode unavailable: no editor open."
 *   text editor, no Neovim  greyed out  color logo  "Neovim mode unavailable (Neovim: Attach to Active Editor attaches Neovim)."
 *   Neovim mode             pressed     color logo  "Neovim mode: Neovim gets the keys. Click or (Ctrl+Shift+M) to switch to Eclipse mode."
 *   Eclipse mode            raised      grey logo   "Eclipse mode: Eclipse gets the keys. Click or (Ctrl+Shift+M) to switch to Neovim mode."
 * </pre>
 *
 * <p>The button is an Eclipse <em>toggle</em> item ({@code style="toggle"} in {@code plugin.xml}):
 * the platform draws it pressed while it is <em>checked</em>, and greyed out while its command is
 * <em>disabled</em>. Being greyed out, it cannot be clicked, and its key binding does nothing.
 *
 * <p>Why the icon changes too: macOS draws a pressed toolbar button with only a faint tint, hard to
 * tell from a raised one. A grey logo in Eclipse mode shows the mode on every platform. The greyed-out
 * button keeps the color logo, which Eclipse pales itself as it does for every disabled button.
 *
 * @param enabled {@code false} to grey the button out
 * @param checked {@code true} to draw it pressed
 * @param icon    the icon's path in the plug-in, {@link #ICON_ON} or {@link #ICON_OFF} (Eclipse loads
 *                the {@code @2x} twin by itself on a 200% screen)
 * @param tooltip what hovering over it shows
 */
public record ToggleButtonState(boolean enabled, boolean checked, String icon, String tooltip) {

    /**
     * What the active editor is, as far as Neovim is concerned. Only a text editor (or the text
     * page of a multi-page editor) can have Neovim; the attach key helps only there, for example
     * after <em>Detach from Active Editor</em> or when Neovim failed to start.
     */
    public enum Editor {
        /** The active editor has Neovim attached. */
        ATTACHED,
        /** A text editor without Neovim: the attach key would attach it. */
        TEXT_WITHOUT_NEOVIM,
        /** An image, a form page (a manifest's Overview) ...: Neovim cannot work there. */
        NOT_TEXT,
        /** No editor is open. */
        NONE
    }

    /** The logo in color: Neovim mode (and the greyed-out button). Also plugin.xml's icon. */
    public static final String ICON_ON = "icons/neovim.png";
    /** The logo in grey ({@code releng/render-icon.py --grey}): Eclipse mode. */
    public static final String ICON_OFF = "icons/neovim-off.png";

    /**
     * The attach command (plugin.xml), which the greyed-out button's tooltip names. It has no key
     * binding of its own (no more since 0.1.9); one the user binds in <em>General › Keys</em> is
     * named instead of the command's name.
     */
    public static final String ATTACH_COMMAND_ID = "io.github.jm6cnddxbwftaks.neovim4eclipse.commands.attach";

    /**
     * @param editor     the active editor's kind
     * @param neovimMode Neovim gets the keys (ignored unless {@link Editor#ATTACHED})
     * @param toggleKey  the toggle's key binding as shown to the user ({@code Ctrl+Shift+M}), or
     *                   {@code null} if it has none
     * @param attachKey  a key binding the user gave the attach command (say {@code Ctrl+Alt+A}), or
     *                   {@code null} (the default: the tooltip then names the command, which Quick
     *                   Access finds)
     */
    public static ToggleButtonState of(Editor editor, boolean neovimMode, String toggleKey, String attachKey) {
        switch (editor) {
            case NOT_TEXT:
                return unavailable("Neovim mode unavailable: not a text editor.");
            case NONE:
                return unavailable("Neovim mode unavailable: no editor open.");
            case TEXT_WITHOUT_NEOVIM:
                String hint = isBlank(attachKey)
                        ? "Neovim: Attach to Active Editor attaches Neovim"
                        : attachKey + " attaches Neovim";
                return unavailable("Neovim mode unavailable (" + hint + ").");
            case ATTACHED:
            default:
                break;
        }
        // "Click or (Ctrl+Shift+M)"; just "Click" if the user removed the key binding.
        String click = isBlank(toggleKey) ? "Click" : "Click or (" + toggleKey + ")";
        return neovimMode
                ? new ToggleButtonState(true, true, ICON_ON,
                        "Neovim mode: Neovim gets the keys. " + click + " to switch to Eclipse mode.")
                : new ToggleButtonState(true, false, ICON_OFF,
                        "Eclipse mode: Eclipse gets the keys. " + click + " to switch to Neovim mode.");
    }

    private static ToggleButtonState unavailable(String tooltip) {
        return new ToggleButtonState(false, false, ICON_ON, tooltip);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
