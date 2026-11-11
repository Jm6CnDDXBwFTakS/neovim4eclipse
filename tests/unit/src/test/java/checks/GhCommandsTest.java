// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The developer tools' {@code gh --jq} filters work on Windows too. There, Java joins a
 * command's arguments into one command line and does not escape a double quote inside one, and
 * {@code gh} takes such quotes away: {@code --jq '.[] | "\(.tag_name)\t\(.published_at)"'}
 * reaches jq as {@code .[] | \(.tag_name)…} and fails to parse. A filter therefore builds its
 * lines with {@code @tsv} ({@code [.tag_name, .published_at] | @tsv}) and holds no {@code "}.
 * Linux and macOS pass the quotes on, so only this test catches a filter that breaks Windows.
 */
class GhCommandsTest {

    /** {@code "--jq", "<filter>"} in Java source, the filter as written (escapes kept). */
    private static final Pattern JQ = Pattern.compile("\"--jq\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void noJqFilterHoldsADoubleQuote() throws IOException {
        List<String> wrong = new ArrayList<>();
        int filters = 0;
        try (Stream<Path> files = Files.walk(Path.of("../../releng/tools"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = JQ.matcher(Files.readString(f));
                while (m.find()) {
                    filters++;
                    if (m.group(1).contains("\\\"")) {
                        wrong.add(f + ": " + m.group(1));
                    }
                }
            }
        }
        assertTrue(filters >= 5, "the pattern should find the tools' filters, found " + filters);
        assertTrue(wrong.isEmpty(), "jq filters with a double quote (use @tsv; Windows drops the quotes): " + wrong);
    }
}
