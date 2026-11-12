// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package marketplace;

import java.util.List;
import java.util.Map;

/**
 * The local page {@code target/marketplace/listing.html}: every field of the Marketplace form in
 * form order with a Copy button, the description as it will look and as HTML for the form's source
 * view, the logo and the pictures, and a checklist for the web form. One opens it beside the form
 * and copies field by field. Pure, so {@code tests/unit} tests it; every value is escaped
 * ({@link Markdown#escape}), as it is text from the repository.
 */
public final class ListingPage {

    private ListingPage() {
    }

    /**
     * The page. {@code images} are file names in the page's own folder (the logo, the demo and the
     * screenshots, those that exist); {@code changes} are {@link Listing#changesSince} lines, or a
     * single line saying none was submitted yet.
     */
    public static String html(Listing listing, List<String> images, List<String> changes) {
        StringBuilder b = new StringBuilder();
        b.append("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Marketplace listing</title>
                <style>
                body{font:15px/1.45 system-ui,sans-serif;max-width:60rem;margin:2rem auto;padding:0 1rem;color:#222}
                table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #ddd;padding:.4rem;vertical-align:top;text-align:left}
                th{width:16rem;font-weight:600}td.value{white-space:pre-wrap;word-break:break-word}
                button{font:inherit;padding:.1rem .6rem}.rendered{border:1px solid #ccc;padding:.2rem 1rem;border-radius:4px}
                textarea{width:100%;height:10rem;font:13px monospace}img{max-width:100%;border:1px solid #ccc;margin:.3rem 0}
                .changes li{color:#8a4b00}
                </style></head><body>
                <h1>Marketplace listing</h1>
                <p>Open <a href="https://marketplace.eclipse.org/">marketplace.eclipse.org</a>, sign in, and fill in the form
                (<i>Add Content &rarr; Solution</i>, or <i>Edit</i> on the listing) from top to bottom.</p>
                """);
        b.append("<h2>Since the last submission</h2><ul class=\"changes\">");
        changes.forEach(c -> b.append("<li>").append(Markdown.escape(c)).append("</li>"));
        b.append("</ul>\n<h2>Fields</h2>\n<table>\n");
        int i = 0;
        for (Map.Entry<String, String> f : listing.fields().entrySet()) {
            String id = "f" + i++;
            b.append("<tr><th>").append(Markdown.escape(f.getKey())).append("</th><td class=\"value\" id=\"").append(id).append("\">")
                    .append(Markdown.escape(f.getValue())).append("</td><td>");
            if (!f.getValue().isEmpty()) {
                b.append("<button data-copy=\"").append(id).append("\">Copy</button>");
            }
            b.append("</td></tr>\n");
        }
        b.append("</table>\n<h2>Solution Description</h2>\n<div class=\"rendered\">\n").append(Markdown.toHtml(listing.description()))
                .append("\n</div>\n<p>As HTML, for the editor's source view: <button data-copy=\"description\">Copy</button></p>\n")
                .append("<textarea id=\"description\" readonly>").append(Markdown.escape(Markdown.toHtml(listing.description())))
                .append("</textarea>\n<h2>Pictures</h2>\n");
        if (images.isEmpty()) {
            b.append("<p>None yet: run <code>java releng/tools/marketplace/Marketplace.java pictures</code> on Linux.</p>\n");
        }
        for (String image : images) {
            b.append("<p><b>").append(Markdown.escape(image)).append("</b><br><img src=\"").append(Markdown.escape(image))
                    .append("\" alt=\"").append(Markdown.escape(image)).append("\"></p>\n");
        }
        b.append("""
                <h2>Checklist</h2>
                <ol>
                <li>Look at every picture before uploading it: it is public, so no home folder or account name may show.</li>
                <li>Upload the logo and the four screenshots, in the order above. Not the demo: the listing shows only a
                GIF's first frame, and the description links to the README, where it plays. A <code>demo-new.gif</code>
                is a recording not committed yet; commit it (pictures offers to) for the README to show it.</li>
                <li>Tick each supported Eclipse release up to the newest tested one, no further.</li>
                <li>After saving, run <code>java releng/tools/marketplace/Marketplace.java submitted</code>, so the next run lists only what changed.</li>
                </ol>
                <script>
                for (const button of document.querySelectorAll('button[data-copy]')) {
                  button.addEventListener('click', async () => {
                    const el = document.getElementById(button.dataset.copy);
                    const text = el.value !== undefined ? el.value : el.textContent;
                    try { await navigator.clipboard.writeText(text); button.textContent = 'Copied'; }
                    catch (e) { const r = document.createRange(); r.selectNodeContents(el); getSelection().removeAllRanges(); getSelection().addRange(r); }
                  });
                }
                </script>
                </body></html>
                """);
        return b.toString();
    }
}
