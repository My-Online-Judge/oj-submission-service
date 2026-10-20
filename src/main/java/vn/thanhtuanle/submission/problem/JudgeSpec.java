package vn.thanhtuanle.submission.problem;

import java.util.UUID;

/**
 * What the submission side needs from a problem to queue a submission: its identity, its limits and the
 * test-case bundle version to judge against.
 *
 * @param contestId the running contest the submission is made in; null for practice
 */
public record JudgeSpec(UUID problemId, String problemSlug, int timeLimitMs, long memoryLimitMb,
        String testCaseVersion, UUID contestId) {

    /** Outside any contest. */
    public JudgeSpec(UUID problemId, String problemSlug, int timeLimitMs, long memoryLimitMb, String testCaseVersion) {
        this(problemId, problemSlug, timeLimitMs, memoryLimitMb, testCaseVersion, null);
    }
}
