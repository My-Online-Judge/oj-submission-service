package vn.thanhtuanle.messaging;

import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import vn.thanhtuanle.submission.SubmissionTimeouts.TimedOut;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Builds every {@link SubmissionVerdictRecorded} this service announces, so each names the submitter, the contest
 * (null for practice) and the submit time. {@code created_at} is naive local time in the JVM's zone, pinned to
 * Asia/Ho_Chi_Minh in {@code SubmissionServiceApplication}; the event carries it as an instant.
 */
public final class VerdictEvents {

    private VerdictEvents() {
    }

    /** The verdict a worker sent, as stored on {@code s}. */
    public static SubmissionVerdictRecorded recorded(Submission s, int verdict, boolean rejudge) {
        return new SubmissionVerdictRecorded(s.getId(), s.getProblemId(), verdict, rejudge, s.getUserId(),
                s.getContestId(), submittedAt(s.getCreatedAt()));
    }

    /** The verdict the reconcile job gave a submission whose work was lost. */
    public static SubmissionVerdictRecorded recorded(TimedOut t, int verdict) {
        return new SubmissionVerdictRecorded(t.id(), t.problemId(), verdict, t.rejudge(), t.userId(), t.contestId(),
                submittedAt(t.createdAt()));
    }

    private static Instant submittedAt(LocalDateTime createdAt) {
        return createdAt.atZone(ZoneId.systemDefault()).toInstant();
    }
}
