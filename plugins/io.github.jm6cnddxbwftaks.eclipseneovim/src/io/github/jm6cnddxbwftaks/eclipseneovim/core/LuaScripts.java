// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package io.github.jm6cnddxbwftaks.eclipseneovim.core;

/**
 * Lua snippets executed inside Neovim via {@code nvim_exec_lua}.
 *
 * <p>Kept in one place, with comments, because Lua embedded in Java strings is hard to review.
 * Note {@code string.char(34)} is the {@code "} (unnamed) register name, written that way to avoid
 * Java string escaping.
 */
final class LuaScripts {

    private LuaScripts() {
    }

    /**
     * One-time session setup: options plus autocmds that push state to Eclipse.
     *
     * <ul>
     *   <li>{@code timeoutlen}: how long Neovim waits for the rest of a multi-key mapping or
     *       operator. Keys arrive one RPC at a time, so this must be generous (gg, diw, f{char}).</li>
     *   <li>{@code showmode=false}: Eclipse shows the mode in its own status line.</li>
     *   <li>Autocmds emit {@code eclipse_mode}, {@code eclipse_cursor} (with the buffer number),
     *       {@code eclipse_buf_enter} and {@code eclipse_yank} notifications handled by
     *       {@link NotificationBridge}.</li>
     *   <li>Normal-mode mappings for {@code gd}, {@code K}, {@code gt} and {@code gT} send
     *       {@code eclipse_action} instead, so that the IDE does those (see {@link IdeAction}).</li>
     * </ul>
     */
    static final String SETUP = """
            vim.opt.timeout = true
            vim.opt.timeoutlen = 400
            vim.opt.ttimeoutlen = 50
            vim.opt.hlsearch = true
            vim.opt.incsearch = true
            vim.opt.ignorecase = true
            vim.opt.smartcase = true
            vim.opt.wrapscan = true
            vim.opt.magic = true
            vim.opt.showcmd = true
            vim.opt.showmode = false
            -- Insert-mode text is typed by Eclipse, so the user's insert-mode mappings and abbreviations
            -- never applied. They must not hijack the text that is re-typed into Neovim at Esc (so that
            -- "." can repeat it), e.g. a `jk` -> <Esc> mapping would end the insert in the middle.
            vim.cmd('imapclear')
            vim.cmd('iabclear')
            -- Lets nvim_eval_statusline('%S') report the pending command (showcmd) for the status bar.
            -- The option exists from Neovim 0.9; on older versions the "cmd" item stays empty.
            pcall(function() vim.opt.showcmdloc = 'statusline' end)
            vim.api.nvim_create_autocmd('ModeChanged', {
              callback = function()
                vim.rpcnotify(0, 'eclipse_mode', vim.api.nvim_get_mode().mode)
              end
            })
            vim.api.nvim_create_autocmd({'CursorMoved', 'CursorMovedI'}, {
              callback = function()
                local p = vim.api.nvim_win_get_cursor(0)
                vim.rpcnotify(0, 'eclipse_cursor', p[1], p[2], vim.api.nvim_get_current_buf())
              end
            })
            -- Each Eclipse document has its own buffer. When Neovim switches buffers by itself
            -- (Ctrl-O across files, 'A, :b), Eclipse follows by activating that file's editor.
            vim.api.nvim_create_autocmd('BufEnter', {
              callback = function(ev)
                vim.rpcnotify(0, 'eclipse_buf_enter', ev.buf)
              end
            })
            vim.api.nvim_create_autocmd('TextYankPost', {
              callback = function()
                -- If the user's config already makes Neovim sync the system clipboard
                -- (clipboard=unnamed/unnamedplus), do not also write it from Java: two writers
                -- race, and a linewise yank can come back as a charwise one (so P pastes inline).
                if vim.o.clipboard:find('unnamed', 1, true) then
                  return
                end
                local e = vim.v.event
                if e and e.regcontents then
                  vim.rpcnotify(0, 'eclipse_yank', e.regcontents, e.regtype or 'v')
                end
              end
            })
            -- Keys only the IDE can carry out (see IdeAction). This runs after the user's init.lua,
            -- so it wins over their own mappings of these keys; `vim.g.eclipse_ide_keys = 0` (or
            -- false, v:false in Vim script) in init.lua keeps Neovim's meaning instead. vim.v.count
            -- is the count typed before the key.
            local ide_keys = vim.g.eclipse_ide_keys
            if ide_keys ~= 0 and ide_keys ~= false then
              local function ide(action, key, desc)
                vim.keymap.set('n', key, function()
                  vim.rpcnotify(0, 'eclipse_action', action, vim.v.count)
                end, { desc = 'Eclipse: ' .. desc })
              end
              ide('definition', 'gd', 'Open Declaration')
              ide('hover', 'K', 'Show Tooltip Description')
              ide('next_tab', 'gt', 'next editor tab')
              ide('previous_tab', 'gT', 'previous editor tab')
            end
            """;

    /**
     * Everything the UI needs after a key, in ONE round trip:
     * {@code {cursor_line, cursor_col, vstart_line, vstart_col, vend_line, vend_col, mode, curswant,
     * tabstop, recording, statusline, showcmd, statusmsg, errmsg, bufnr}}. {@code bufnr} tells whether
     * the cursor is still in the buffer Eclipse shows (Neovim may just have jumped to another file).
     *
     * <p>The cursor is {@code nvim_win_get_cursor} (1-based line, 0-based byte column); the visual
     * bounds are {@code getpos} (1-based lines and byte columns) and only meaningful in visual mode.
     * {@code curswant} is 2147483647 after {@code $} (a block then extends to each line's end).
     * {@code recording} is the register being recorded into ({@code reg_recording()}), or "".
     *
     * <p>The last four depend on three arguments, each {@code "1"} or {@code "0"}, so that nothing is
     * evaluated that the status bar does not show: (1) the evaluated {@code 'statusline'} (a Vim-like
     * default if the user has none), (2) {@code showcmd} via the {@code %S} statusline item, (3) the
     * latest message {@code v:statusmsg} and error {@code v:errmsg}, which are <em>cleared</em> after
     * reading so that each message is seen once.
     */
    static final String STATE_SNAPSHOT = """
            local want_statusline = select(1, ...) == '1'
            local want_cmd = select(2, ...) == '1'
            local want_msg = select(3, ...) == '1'
            local c = vim.api.nvim_win_get_cursor(0)
            local s = vim.fn.getpos('v')
            local e = vim.fn.getpos('.')
            local view = vim.fn.winsaveview()
            local statusline, showcmd, statusmsg, errmsg = '', '', '', ''
            if want_statusline then
              local fmt = vim.o.statusline
              if fmt == nil or fmt == '' then fmt = '%<%f %h%m%r%=%-14.(%l,%c%V%) %P' end
              local ok, res = pcall(vim.api.nvim_eval_statusline, fmt, { maxwidth = 300 })
              if ok and type(res) == 'table' then statusline = res.str or '' end
            end
            if want_cmd then
              local ok, res = pcall(vim.api.nvim_eval_statusline, '%S', { maxwidth = 40 })
              if ok and type(res) == 'table' then showcmd = res.str or '' end
            end
            if want_msg then
              statusmsg = vim.v.statusmsg or ''
              errmsg = vim.v.errmsg or ''
              pcall(function() vim.v.statusmsg = '' end)
              pcall(function() vim.v.errmsg = '' end)
            end
            return { c[1], c[2], s[2], s[3], e[2], e[3], vim.fn.mode(), view.curswant, vim.bo.tabstop,
                     vim.fn.reg_recording(), statusline, showcmd, statusmsg, errmsg,
                     vim.api.nvim_get_current_buf() }
            """;

    /**
     * Deletes the empty, unnamed buffer Neovim starts with (argument: the number of the buffer that
     * replaced it), so that {@code :ls} lists only the files open in Eclipse. Buffers with text or a
     * name, e.g. created by the user's config, are left alone.
     */
    static final String DELETE_STARTUP_BUFFER = """
            local keep = tonumber(select(1, ...))
            for _, b in ipairs(vim.api.nvim_list_bufs()) do
              if b ~= keep and vim.api.nvim_buf_get_name(b) == '' and not vim.bo[b].modified
                  and vim.api.nvim_buf_line_count(b) == 1 and vim.api.nvim_buf_get_lines(b, 0, 1, false)[1] == '' then
                pcall(vim.api.nvim_buf_delete, b, { force = true })
              end
            end
            """;

    /**
     * Runs an ex command (argument 1) and returns what it printed. Neovim 0.9+ has
     * {@code nvim_exec2}; older versions only {@code nvim_exec}.
     */
    static final String CAPTURE_OUTPUT = """
            local cmd = select(1, ...)
            if vim.api.nvim_exec2 then
              return vim.api.nvim_exec2(cmd, { output = true }).output
            end
            return vim.api.nvim_exec(cmd, true)
            """;

    /**
     * Starts collecting everything Neovim prints into {@code g:eclipse_neovim_output}, with
     * {@code :redir}. It keeps collecting while the next typed command runs, also under
     * {@code :silent} (the classic idiom {@code redir => x | silent ls | redir END}), until
     * {@link #STOP_OUTPUT_CAPTURE}.
     */
    static final String START_OUTPUT_CAPTURE = """
            vim.g.eclipse_neovim_output = ''
            vim.cmd('redir => g:eclipse_neovim_output')
            """;

    /**
     * Ends the capture started by {@link #START_OUTPUT_CAPTURE} and returns what was printed, or ""
     * when nothing was, or when it was only the error message (which the status bar already shows
     * through {@code v:errmsg}). Cleaned up for the popup:
     * <ul>
     *   <li>the empty lines it starts with (where the command line was drawn) and trailing blanks are
     *       removed; the indentation of the first line is kept ({@code "  tabstop=8"});</li>
     *   <li>carriage returns are removed (shell output arrives with {@code \r\n});</li>
     *   <li>for {@code :!cmd}, the echo of the shell command ({@code :!ls}) that Neovim prints first
     *       is removed: the popup's title already shows it.</li>
     * </ul>
     */
    static final String STOP_OUTPUT_CAPTURE = """
            pcall(vim.cmd, 'redir END')
            local out = (vim.g.eclipse_neovim_output or ''):gsub('\\r', '')
            vim.g.eclipse_neovim_output = nil
            out = out:gsub('^\\n+', '')
            out = out:gsub('^:![^\\n]*\\n*', '')
            out = out:gsub('%s+$', '')
            if out:match('^%s*$') or out == vim.v.errmsg then
              return ''
            end
            return out
            """;

    /**
     * Sets the current buffer's indentation options (arguments: tabstop, "1" to indent with tabs,
     * indent width, "1" to follow braces, cinoptions). See {@code util/Indentation} for why.
     * {@code 'shiftwidth'} is one indent level, used by {@code >>}, {@code o} and {@code cindent};
     * {@code 'softtabstop'} 0 makes Tab and Backspace in Neovim's insert mode work per character.
     */
    static final String SET_INDENTATION = """
            local tabstop, use_tabs, width, braces, cinoptions = ...
            tabstop = tonumber(tabstop)
            if tabstop >= 1 and tabstop <= 64 then
              vim.bo.tabstop = tabstop
            end
            vim.bo.expandtab = use_tabs ~= '1'
            vim.bo.shiftwidth = tonumber(width)
            vim.bo.softtabstop = 0
            vim.bo.autoindent = true
            vim.bo.cindent = braces == '1'
            if braces == '1' then
              vim.bo.cinoptions = cinoptions
            end
            """;

    /**
     * Replaces the current buffer's text without an undo step (arguments: the lines, one each). Setting
     * {@code 'undolevels'} to -1 for one change is Vim's documented way to do that (see
     * {@code :help clear-undo}); it also clears any earlier history. The buffer-local value is put
     * back afterwards, which may be "use the global value" (-123456).
     */
    static final String LOAD_WITHOUT_UNDO = """
            local lines = { ... }
            local saved = vim.bo.undolevels
            vim.bo.undolevels = -1
            local ok, err = pcall(vim.api.nvim_buf_set_lines, 0, 0, -1, false, lines)
            vim.bo.undolevels = saved
            if not ok then
              error(err)
            end
            """;

    /** The user's {@code 'clipboard'} option (e.g. {@code unnamedplus}), evaluated after their init file ran. */
    static final String GET_CLIPBOARD_OPTION = "return vim.o.clipboard";

    static final String GET_CMDLINE = "return vim.fn.getcmdline()";

    static final String GET_CMDTYPE = "return vim.fn.getcmdtype()";
}
