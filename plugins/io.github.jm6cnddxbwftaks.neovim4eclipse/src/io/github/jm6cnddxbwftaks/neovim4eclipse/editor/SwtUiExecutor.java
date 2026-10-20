// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.neovim4eclipse.editor;

import org.eclipse.swt.widgets.Display;

import io.github.jm6cnddxbwftaks.neovim4eclipse.core.UiExecutor;

/**
 * {@link UiExecutor} on SWT's {@link Display}: the workbench's one UI thread.
 *
 * <p>The display is looked up on each call ({@link Display#getDefault()}), so a call during
 * shutdown sees it disposed and the work is dropped rather than failing.
 */
public final class SwtUiExecutor implements UiExecutor {

    public static final SwtUiExecutor INSTANCE = new SwtUiExecutor();

    private SwtUiExecutor() {
    }

    @Override
    public boolean isAvailable() {
        Display display = Display.getDefault();
        return display != null && !display.isDisposed();
    }

    @Override
    public boolean isUiThread() {
        return Display.getDefault().getThread() == Thread.currentThread();
    }

    @Override
    public void asyncExec(Runnable work) {
        Display.getDefault().asyncExec(work);
    }

    @Override
    public void syncExec(Runnable work) {
        Display.getDefault().syncExec(work);
    }
}
