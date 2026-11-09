// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An Eclipse release, by both of its names: the <i>simultaneous release</i> name the IDE packages
 * carry ({@code 2024-12}) and the Eclipse Platform's number ({@code 4.34}). Users know the first,
 * the Platform downloads and p2 use the second; the docs write both, "2024-12 (4.34)".
 *
 * <p>Eclipse ships four times a year, in March, June, September and December, and every release
 * raises the Platform's minor number by one, so the two names map onto each other by counting
 * quarters from one known pair: 2024-12 is 4.34, 2025-03 is 4.35, 2026-09 is 4.41. Checked
 * against the downloads (see {@code eclipse/EclipseFiles#checkDropDate}): a Platform drop is
 * built in its release's month or the month before, so a drop {@code R-4.41-202608281142} (August
 * 2026) confirms 4.41 is 2026-09.
 *
 * <p>Where the plug-in keeps the two ends of the range it supports ({@code docs/UPGRADING.md},
 * "Eclipse release"): the <b>oldest</b> is {@code <eclipse-repo.url>} in the root {@code pom.xml},
 * which the plug-in is built against; the <b>newest tested</b> is the second entry of the
 * {@code smoke} job's matrix in {@code .github/workflows/update-site.yml} ({@link #smokeEntries}),
 * which every release is installed into.
 */
public record EclipseRelease(int year, int month) implements Comparable<EclipseRelease> {

    /** The known pair the others are counted from: 2024-12 is 4.34. */
    private static final int ANCHOR_YEAR = 2024;
    private static final int ANCHOR_MONTH = 12;
    private static final int ANCHOR_MINOR = 34;

    private static final Pattern NAME = Pattern.compile("(20\\d\\d)-(03|06|09|12)");
    private static final Pattern NUMBER = Pattern.compile("4\\.(\\d+)");

    public EclipseRelease {
        if (month % 3 != 0 || month < 3 || month > 12) {
            throw new ToolFailure("an Eclipse release comes out in March, June, September or December, not month " + month);
        }
        if (minorOf(year, month) < ANCHOR_MINOR) {
            throw new ToolFailure("Eclipse releases before " + ANCHOR_YEAR + "-" + ANCHOR_MONTH + " are not counted");
        }
    }

    /** {@code 2024-12}; anything else, {@code 2024-11} or {@code 4.34} among them, is refused. */
    public static EclipseRelease ofName(String name) {
        Matcher m = NAME.matcher(name.strip());
        if (!m.matches()) {
            throw new ToolFailure("not an Eclipse release name such as 2025-12: " + name);
        }
        return new EclipseRelease(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    /** {@code 4.34}; {@code 4.34.0} and {@code 2024-12} are refused. */
    public static EclipseRelease ofNumber(String number) {
        Matcher m = NUMBER.matcher(number.strip());
        if (!m.matches()) {
            throw new ToolFailure("not an Eclipse Platform number such as 4.38: " + number);
        }
        int quarters = Integer.parseInt(m.group(1)) - ANCHOR_MINOR;
        if (quarters < 0) {
            throw new ToolFailure("Eclipse " + number + " is older than " + ANCHOR_YEAR + "-" + ANCHOR_MONTH + ", which is not counted");
        }
        int months = ANCHOR_YEAR * 12 + (ANCHOR_MONTH - 1) + quarters * 3;
        return new EclipseRelease(months / 12, months % 12 + 1);
    }

    /** The release name: {@code 2024-12}. */
    public String name() {
        return year + "-" + (month < 10 ? "0" : "") + month;
    }

    /** The Platform number: {@code 4.34}. */
    public String number() {
        return "4." + minorOf(year, month);
    }

    /** As the docs write it: {@code 2024-12 (4.34)}. */
    @Override
    public String toString() {
        return name() + " (" + number() + ")";
    }

    @Override
    public int compareTo(EclipseRelease o) {
        return Integer.compare(year * 12 + month, o.year * 12 + o.month);
    }

    private static int minorOf(int year, int month) {
        return ANCHOR_MINOR + ((year * 12 + month) - (ANCHOR_YEAR * 12 + ANCHOR_MONTH)) / 3;
    }

    // ------------------------------------------------------------ the smoke job's matrix

    /**
     * One entry of the {@code smoke} job's matrix, where a release of the plug-in is installed from
     * the live update site:
     * <pre>
     *   - eclipse: '4.34'
     *     platform-url: https://download.eclipse.org/eclipse/downloads/drops4/R-4.34-202411201800/eclipse-platform-4.34-linux-gtk-x86_64.tar.gz
     *     platform-sha512: a3464f0b…
     * </pre>
     * The first entry is the oldest supported Eclipse, the second the newest tested.
     */
    public record SmokeEntry(String eclipse, String platformUrl, String platformSha512) {
    }

    /** The entry: three lines, each with its own indent; comments between entries are left alone. */
    private static final Pattern ENTRY = Pattern.compile(
            "(?m)^(\\s*- eclipse: ')([^']*)('\\R\\s*platform-url: )(\\S+)(\\R\\s*platform-sha512: )([0-9a-f]+)$");

    /** The matrix's entries, in the workflow's order (update-site.yml and rehearse-release.yml have the same). */
    public static List<SmokeEntry> smokeEntries(String workflow) {
        List<SmokeEntry> found = new ArrayList<>();
        Matcher m = ENTRY.matcher(workflow);
        while (m.find()) {
            found.add(new SmokeEntry(m.group(2), m.group(4), m.group(6)));
        }
        return found;
    }

    /**
     * {@code workflow} with its {@code index}th smoke entry (0: the oldest, 1: the newest) set to
     * {@code entry}, keeping each line's indent and every other line as it is.
     */
    public static String withSmokeEntry(String workflow, int index, SmokeEntry entry) {
        Matcher m = ENTRY.matcher(workflow);
        for (int i = 0; m.find(); i++) {
            if (i == index) {
                return workflow.substring(0, m.start()) + m.group(1) + entry.eclipse() + m.group(3) + entry.platformUrl()
                        + m.group(5) + entry.platformSha512() + workflow.substring(m.end());
            }
        }
        throw new ToolFailure("the workflow has no smoke entry " + (index + 1) + " (- eclipse: '…', platform-url, platform-sha512)");
    }
}
