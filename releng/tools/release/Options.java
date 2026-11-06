// SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
// SPDX-License-Identifier: EPL-2.0

package release;

import java.util.ArrayList;
import java.util.List;

import common.Ask;
import common.ToolFailure;
import common.Version;

/**
 * The Release tool's command line: one step (or none, for the status) and the flags.
 * <pre>
 *   Release.java                       the status and the next step
 *   Release.java check|prepare|promote|tag|next
 *   Release.java go                    from the next step to the end
 *   --yes             do side effects without asking (for an approved step)
 *   --dry-run         print side effects instead of doing them
 *   --no-tests        check: skip the local test run
 *   --skip-local-tests  promote: go on without a local test run of this commit
 *   --no-rehearsal    promote: skip the Release rehearsal
 *   --ci-on-branch    promote: also run the Build on the release branch, before main
 *   --next 0.3.0      next: the version to start (default: the next patch)
 * </pre>
 *
 * @param args the arguments as given, for {@link #rerunWithYes}
 */
public record Options(String step, Ask.Mode mode, boolean noTests, boolean skipLocalTests, boolean rehearse,
        boolean ciOnBranch, Version next, List<String> args) {

    /** The steps, in the order a release runs them. */
    public static final List<String> STEPS = List.of("check", "prepare", "promote", "tag", "next");

    public static final String USAGE = """
            Usage: java releng/tools/release/Release.java [step] [flags]
              (no step)  the status and the next step
              check      branch, changelog, Neovim pins, manual checks due; the local tests (LikeCi)
              prepare    date the changelog section, name the version in the README; commit
              promote    push the branch, rehearse the release, fast-forward main, watch its Build
              tag        tag main, watch the Release workflow, verify the release and the update site
              next       cut the next release branch, set its version; delete merged branches
              go         from the next step to the end, asking before each side effect
            Flags: --yes, --dry-run, --no-tests (check), --skip-local-tests, --no-rehearsal,
                   --ci-on-branch (promote), --next <x.y.z> (next)""";

    public static Options parse(String... args) {
        String step = "status";
        Ask.Mode mode = Ask.Mode.ASK;
        boolean noTests = false;
        boolean skipLocalTests = false;
        boolean rehearse = true;
        boolean ciOnBranch = false;
        Version next = null;
        boolean stepSeen = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--yes" -> mode = mode == Ask.Mode.DRY_RUN ? mode : Ask.Mode.YES;
                case "--dry-run" -> mode = Ask.Mode.DRY_RUN;
                case "--no-tests" -> noTests = true;
                case "--skip-local-tests" -> skipLocalTests = true;
                case "--no-rehearsal" -> rehearse = false;
                case "--ci-on-branch" -> ciOnBranch = true;
                case "--next" -> {
                    if (i + 1 == args.length) {
                        throw new ToolFailure("--next needs a version, e.g. --next 0.3.0\n" + USAGE);
                    }
                    next = Version.parse(args[++i]);
                }
                default -> {
                    if (stepSeen || a.startsWith("-") || !(STEPS.contains(a) || a.equals("go") || a.equals("status"))) {
                        throw new ToolFailure("unknown argument " + a + "\n" + USAGE);
                    }
                    step = a;
                    stepSeen = true;
                }
            }
        }
        return new Options(step, mode, noTests, skipLocalTests, rehearse, ciOnBranch, next, List.of(args));
    }

    /**
     * The command that repeats this run with {@code --yes} (and without {@code --dry-run}), for a
     * run that had to stop because nobody could be asked; {@code step} replaces {@code go}, so an
     * approval covers one step: {@code go} stopped at {@code promote} prints
     * {@code java releng/tools/release/Release.java promote --yes}.
     */
    public String rerunWithYes(String currentStep) {
        List<String> words = new ArrayList<>(List.of("java", "releng/tools/release/Release.java"));
        for (String a : args) {
            if (a.equals("go") || a.equals("status")) {
                continue;
            }
            if (!a.equals("--yes") && !a.equals("--dry-run") && !STEPS.contains(a)) {
                words.add(a);
            }
        }
        words.add(2, currentStep);
        words.add("--yes");
        return String.join(" ", words);
    }
}
