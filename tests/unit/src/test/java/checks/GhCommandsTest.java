// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import common.Shell;
import common.ToolFailure;

/**
 * The developer tools' {@code gh --jq} filters work on Windows too. There, Java joins a
 * command's arguments into one command line and does not escape a double quote inside one, and
 * {@code gh} takes such quotes away: {@code --jq '.[] | "\(.tag_name)\t\(.published_at)"'}
 * reaches jq as {@code .[] | \(.tag_name)…} and fails to parse. A filter therefore builds its
 * lines with {@code @tsv} ({@code [.tag_name, .published_at] | @tsv}) and holds no {@code "}.
 * Linux and macOS pass the quotes on, so this test catches a filter written inline that would
 * break Windows; on Windows itself, {@code common/Shell} refuses any argument with a double quote.
 */
class GhCommandsTest {

    /** {@code "--jq", "<filter>"} (or {@code -q}, its short form) in Java source, the filter as written (escapes kept). */
    private static final Pattern JQ = Pattern.compile("\"(?:--jq|-q)\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

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

    @Test
    void onWindowsAnArgumentWithADoubleQuoteIsRefusedNotChanged() {
        List<String> quoted = List.of("gh", "api", "x", "--jq", "\"\\(.a)\"");
        assertThrows(ToolFailure.class, () -> Shell.command(quoted, true));
        assertEquals(quoted, Shell.command(quoted, false)); // Linux and macOS pass it on
        assertEquals(List.of("gh", "api", "x", "--jq", "[.a, .b] | @tsv"), Shell.command(List.of("gh", "api", "x", "--jq", "[.a, .b] | @tsv"), true));
    }
}
