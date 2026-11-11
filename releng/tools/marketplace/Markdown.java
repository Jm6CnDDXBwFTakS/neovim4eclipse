// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package marketplace;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import common.ToolFailure;

/**
 * The little Markdown the listing's description uses, turned into HTML for the Marketplace form's
 * formatted-text field (paste it in the editor's source view) and the local listing page. Not a
 * Markdown library on purpose: the tools add no dependency, and a fixed small subset keeps the
 * listing simple. Anything outside it stays plain text, escaped.
 *
 * <ul>
 *   <li>paragraphs, separated by a blank line; lines of one paragraph are joined;</li>
 *   <li>bullet lists: lines starting with {@code - }, continued by indented lines;</li>
 *   <li>{@code **bold**}, {@code *italic*}, {@code `code`};</li>
 *   <li>{@code [text](https://...)} links; any other address stops the tool, so the page can
 *       never carry a {@code javascript:} link.</li>
 * </ul>
 *
 * Example: {@code - Neovim **0.12.6**} becomes {@code <ul><li>Neovim <strong>0.12.6</strong></li></ul>}.
 */
public final class Markdown {

    private static final Pattern INLINE = Pattern.compile(
            "`([^`]+)`|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*|\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");

    private Markdown() {
    }

    /** {@code markdown} as HTML: a {@code <p>} or {@code <ul>} per block, one block per line. */
    public static String toHtml(String markdown) {
        List<String> out = new ArrayList<>();
        for (String block : markdown.strip().split("\\n\\s*\\n")) {
            List<String> lines = block.lines().toList();
            if (lines.get(0).startsWith("- ")) {
                List<String> items = new ArrayList<>();
                for (String line : lines) {
                    if (line.startsWith("- ")) {
                        items.add(line.substring(2).strip());
                    } else if (!items.isEmpty() && (line.startsWith(" ") || line.startsWith("\t"))) {
                        items.set(items.size() - 1, items.get(items.size() - 1) + " " + line.strip());
                    } else {
                        throw new ToolFailure("a list in the description has a line that is neither \"- item\" nor indented: " + line);
                    }
                }
                StringBuilder ul = new StringBuilder("<ul>");
                items.forEach(i -> ul.append("<li>").append(inline(i)).append("</li>"));
                out.add(ul.append("</ul>").toString());
            } else {
                out.add("<p>" + inline(String.join(" ", lines.stream().map(String::strip).toList())) + "</p>");
            }
        }
        return String.join("\n", out);
    }

    /** One line of text with its inline marks as HTML, everything else escaped. */
    static String inline(String text) {
        Matcher m = INLINE.matcher(text);
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (m.find()) {
            out.append(escape(text.substring(at, m.start())));
            if (m.group(1) != null) {
                out.append("<code>").append(escape(m.group(1))).append("</code>");
            } else if (m.group(2) != null) {
                out.append("<strong>").append(escape(m.group(2))).append("</strong>");
            } else if (m.group(3) != null) {
                out.append("<em>").append(escape(m.group(3))).append("</em>");
            } else {
                String url = m.group(5);
                if (!url.startsWith("https://")) {
                    throw new ToolFailure("a link in the description must be https://: " + url);
                }
                out.append("<a href=\"").append(escape(url)).append("\">").append(escape(m.group(4))).append("</a>");
            }
            at = m.end();
        }
        return out.append(escape(text.substring(at))).toString();
    }

    /**
     * {@code text} safe inside HTML text and double-quoted attributes, the only kind the tool
     * writes ({@code '} needs no escape there, and stays readable in the form's source view).
     */
    public static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
