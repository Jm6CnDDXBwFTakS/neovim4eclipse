// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

/**
 * Pure logic with no Eclipse, SWT or Neovim-library dependencies, so all of it is unit-tested in
 * {@code tests/unit}.
 *
 * <p>The Eclipse-facing classes delegate their decisions here and stay thin. Main groups:
 * <ul>
 *   <li><b>Keys:</b> {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.KeyMapping} (SWT key event to
 *       Vim notation such as {@code <C-v>}), {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.NvimKeys},
 *       {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.VimCtrlKeys}.</li>
 *   <li><b>Text:</b> {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.LineTextUtil} (lines versus one
 *       string), {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.LineRangeEdit} (a Neovim line event as
 *       one document replacement), {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.LineDiff} and
 *       {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.TextDiff} (minimal changes),
 *       {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.Utf8Columns} (Neovim's UTF-8 byte columns
 *       versus Eclipse's UTF-16 character offsets).</li>
 *   <li><b>Modes and commands:</b> {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.VimModes},
 *       {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.CommandLine},
 *       {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.HostExCommand},
 *       {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.ListingCommand},
 *       {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.InsertReplay}.</li>
 *   <li><b>Exceptions:</b> {@link io.github.jm6cnddxbwftaks.eclipseneovim.util.NeovimExceptionBase},
 *       the base of every exception class the plug-in defines.</li>
 * </ul>
 *
 * <p>When adding behavior, try to express the decision as a function here first, with a test, and
 * call it from the {@code editor} package.
 */
package io.github.jm6cnddxbwftaks.eclipseneovim.util;
