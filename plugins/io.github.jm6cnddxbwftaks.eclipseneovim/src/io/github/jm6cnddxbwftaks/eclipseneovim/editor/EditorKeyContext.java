// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.editor;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.contexts.IContextService;

import io.github.jm6cnddxbwftaks.eclipseneovim.Activator;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimCtrlKeys;
import io.github.jm6cnddxbwftaks.eclipseneovim.util.VimModes;

/**
 * Decides, for one attached editor, when the Vim key-binding contexts are active.
 *
 * <p><b>Background.</b> Eclipse resolves a key such as {@code Ctrl+V} through key bindings before
 * the editor widget sees it, so Vim could never get it. The plug-in binds those keys to its own
 * command in contexts of its own ({@link #KEY_CONTEXT_ID} and one child context per Ctrl letter,
 * see {@link VimKeyContexts}); while a context is active its binding wins over Eclipse's.
 *
 * <p><b>The rule.</b> The contexts are active exactly while Neovim owns the keys, Neovim is not in
 * insert mode, and this editor's text widget has keyboard focus. In insert mode, in Eclipse mode,
 * on focus loss and after {@link #dispose()} they are inactive, so Eclipse's own shortcuts (paste,
 * content assist, other views) are untouched there. Activation is workbench-wide (the standard,
 * well-trodden path); the focus check keeps it from leaking into other parts.
 *
 * <p>UI thread only, except {@link #updateFromAnyThread}.
 */
final class EditorKeyContext {

    private static final Logger LOG = Logger.getLogger(EditorKeyContext.class.getName());

    /**
     * Key-binding context active while Neovim owns the keys and is not in insert mode. Below it, one
     * context per Vim {@code Ctrl} key (plugin.xml) that Eclipse would otherwise consume first: for
     * example {@code Ctrl-V} is Paste in Eclipse but starts blockwise visual in Vim.
     */
    static final String KEY_CONTEXT_ID = VimCtrlKeys.NORMAL_CONTEXT_ID;

    private final StyledText widget;
    /** Neovim's current mode. */
    private final Supplier<String> mode;
    /** Whether Neovim owns this editor's keys (attached and not in Eclipse mode). */
    private final BooleanSupplier neovimOwnsKeys;
    private final VimKeyContexts contexts = new VimKeyContexts();
    private boolean disposed;

    private final FocusListener focusListener = new FocusListener() {
        @Override
        public void focusGained(FocusEvent e) {
            update();
        }

        @Override
        public void focusLost(FocusEvent e) {
            update();
        }
    };

    EditorKeyContext(StyledText widget, Supplier<String> mode, BooleanSupplier neovimOwnsKeys) {
        this.widget = widget;
        this.mode = mode;
        this.neovimOwnsKeys = neovimOwnsKeys;
        widget.addFocusListener(focusListener);
    }

    /** Applies the rule (see the class comment) with Neovim's current mode. UI thread. */
    void update() {
        update(mode.get());
    }

    /** Applies the rule for {@code nvimMode}. UI thread. */
    void update(String nvimMode) {
        boolean wanted = !disposed && neovimOwnsKeys.getAsBoolean() && !VimModes.isTyping(nvimMode)
                && !widget.isDisposed() && widget.isFocusControl();
        try {
            IContextService service = contextService();
            if (service == null) {
                LOG.warning("No workbench IContextService; Vim Ctrl keys cannot be enabled");
                return;
            }
            if (wanted && !contexts.isActive()) {
                contexts.activate(service, EditorKeyContext::isCtrlKeyToNeovim);
                LOG.fine("Neovim key context activated (Vim Ctrl keys now go to Neovim)");
                // Report what the workbench would run for Ctrl-V now; see KeyContextDiagnostics.
                Display.getDefault().asyncExec(() -> KeyContextDiagnostics.logResolution("CTRL+V"));
            } else if (!wanted && contexts.isActive()) {
                contexts.deactivate(service);
                LOG.fine("Neovim key context deactivated");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Neovim key context update failed", e);
        }
    }

    /** {@link #update(String)} from any thread: runs at once on the UI thread, else posted to it. */
    void updateFromAnyThread(String nvimMode) {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        if (display.getThread() == Thread.currentThread()) {
            update(nvimMode);
        } else {
            display.asyncExec(() -> update(nvimMode));
        }
    }

    /**
     * Re-applies the Ctrl-key preferences: the active letter contexts are dropped and, if Neovim
     * still owns the keys, activated again with the current choice of letters. Any thread.
     */
    void refresh() {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            IContextService service = contextService();
            if (service != null) {
                contexts.deactivate(service);
            }
            update();
        });
    }

    /** Deactivates the contexts for good and stops listening to focus changes. UI thread. */
    void dispose() {
        disposed = true;
        if (!widget.isDisposed()) {
            widget.removeFocusListener(focusListener);
        }
        update(); // disposed == true: deactivates
    }

    /** Whether {@code Ctrl+letter} goes to Neovim; all letters do while the preferences are unavailable. */
    static boolean isCtrlKeyToNeovim(char letter) {
        Activator activator = Activator.getDefault();
        return activator == null || activator.isCtrlKeyToNeovim(letter);
    }

    private static IContextService contextService() {
        return PlatformUI.getWorkbench().getService(IContextService.class);
    }
}
