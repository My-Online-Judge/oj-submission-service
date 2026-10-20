package vn.thanhtuanle.submission.problem;

import java.util.UUID;

/**
 * What the submission side needs from a problem to queue a submission: its identity, its limits and the
 * test-case bundle version to judge against.
 *
 * @param contestId  the running contest the submission is made in; null for practice
 * @param spjSource  the problem's checker (C++ source); null when the output is compared as is
 * @param spjVersion the sha256 hex of {@code spjSource}; null exactly when {@code spjSource} is
 */
public record JudgeSpec(UUID problemId, String problemSlug, int timeLimitMs, long memoryLimitMb,
        String testCaseVersion, UUID contestId, String spjSource, String spjVersion) {

    /** Outside any contest, without a checker. */
    public JudgeSpec(UUID problemId, String problemSlug, int timeLimitMs, long memoryLimitMb, String testCaseVersion) {
        this(problemId, problemSlug, timeLimitMs, memoryLimitMb, testCaseVersion, null);
    }

    /** Without a checker. */
    public JudgeSpec(UUID problemId, String problemSlug, int timeLimitMs, long memoryLimitMb, String testCaseVersion,
            UUID contestId) {
        this(problemId, problemSlug, timeLimitMs, memoryLimitMb, testCaseVersion, contestId, null, null);
    }
}
