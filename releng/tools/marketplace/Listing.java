// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package marketplace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import common.ToolFailure;

/**
 * The Marketplace listing as text: the form's fields in form order ({@code releng/marketplace/listing.txt})
 * and the description ({@code description.md}), with every {@code {fact}} filled in. Pure, so
 * {@code tests/unit} tests it.
 *
 * <p>The fields file has one {@code Field: value} line per field; {@code #} lines and blank lines
 * are left out. Example: {@code Minimum Java Version: Java {java}} becomes the field
 * <i>Minimum Java Version</i> with the value <i>Java 21</i>.
 *
 * <p>The same text, filled in, is what {@code submitted} keeps in
 * {@code releng/marketplace/submitted/}: a later run compares field by field and lists what to
 * change on the website.
 *
 * @param fields      field name to value, in form order
 * @param description the description, Markdown ({@link Markdown})
 */
public record Listing(Map<String, String> fields, String description) {

    /** The fields file and the description, under the repository's root. */
    public static final String FIELDS = "releng/marketplace/listing.txt";
    public static final String DESCRIPTION = "releng/marketplace/description.md";
    /** Where {@code submitted} keeps the listing as it was sent, in the same two files. */
    public static final String SUBMITTED = "releng/marketplace/submitted";

    /** A {@code {fact}}: letters, digits and dots, so JSON-like or code braces are left alone. */
    private static final Pattern FACT = Pattern.compile("\\{([a-z][a-z0-9.]*)\\}");

    public Listing {
        fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        Objects.requireNonNull(description);
    }

    /** The {@code Field: value} lines of {@code text}, in order; a repeated or nameless field stops the tool. */
    public static Map<String, String> parseFields(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        int number = 0;
        for (String line : text.lines().toList()) {
            number++;
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            int colon = l.indexOf(':');
            if (colon <= 0) {
                throw new ToolFailure(FIELDS + " line " + number + " should be \"Field: value\": " + l);
            }
            String name = l.substring(0, colon).strip();
            if (out.put(name, l.substring(colon + 1).strip()) != null) {
                throw new ToolFailure(FIELDS + " names the field \"" + name + "\" twice (line " + number + ")");
            }
        }
        return out;
    }

    /**
     * {@code text} with each {@code {fact}} replaced by its value; a fact {@code facts} does not
     * know stops the tool, so a typo cannot reach the website as literal braces.
     */
    public static String fill(String text, Map<String, String> facts, String where) {
        Matcher m = FACT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = facts.get(m.group(1));
            if (value == null) {
                throw new ToolFailure(where + " names {" + m.group(1) + "}, which is not a known fact " + facts.keySet());
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The listing from its two source texts, filled in with {@code facts}. */
    public static Listing of(String fieldsText, String descriptionText, Map<String, String> facts) {
        Map<String, String> fields = new LinkedHashMap<>();
        parseFields(fieldsText).forEach((name, value) -> fields.put(name, fill(value, facts, FIELDS + " (" + name + ")")));
        return new Listing(fields, fill(descriptionText, facts, DESCRIPTION));
    }

    /** The fields as {@code submitted} writes them: one {@code Field: value} line each, facts filled in. */
    public String fieldsText() {
        StringBuilder out = new StringBuilder();
        fields.forEach((name, value) -> out.append(name).append(": ").append(value).append('\n'));
        return out.toString();
    }

    /**
     * What changed since {@code submitted}: one line per field that differs, is new or is gone,
     * and one for the description. Empty when the website already shows this listing.
     */
    public List<String> changesSince(Listing submitted) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> f : fields.entrySet()) {
            String old = submitted.fields.get(f.getKey());
            if (old == null) {
                out.add(f.getKey() + ": new field, \"" + f.getValue() + "\"");
            } else if (!old.equals(f.getValue())) {
                out.add(f.getKey() + ": \"" + old + "\" -> \"" + f.getValue() + "\"");
            }
        }
        for (String name : submitted.fields.keySet()) {
            if (!fields.containsKey(name)) {
                out.add(name + ": no longer in the listing");
            }
        }
        if (!description.strip().equals(submitted.description.strip())) {
            out.add("Solution Description: changed (" + DESCRIPTION + ")");
        }
        return out;
    }
}
