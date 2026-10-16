// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

/**
 * Text synchronization between an {@link org.eclipse.jface.text.IDocument} and the Neovim buffer.
 *
 * <p>Attach pushes Eclipse to Neovim once; afterward Neovim to Eclipse arrives as
 * {@code nvim_buf_lines_event} line ranges. The platform-neutral algorithms live in
 * {@code io.github.jm6cnddxbwftaks.eclipseneovim.util} ({@code LineRangeEdit}, {@code TextDiff},
 * {@code LineTextUtil}) where they are unit tested; see
 * {@link io.github.jm6cnddxbwftaks.eclipseneovim.sync.BufferSynchronizer} for the Eclipse glue.
 */
package io.github.jm6cnddxbwftaks.eclipseneovim.sync;
