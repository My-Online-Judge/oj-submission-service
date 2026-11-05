package vn.thanhtuanle.messaging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.oj.common.event.SubmissionVerdictRecorded;
import vn.thanhtuanle.submission.SubmissionTimeouts.TimedOut;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictEventsTest {

    private TimeZone before;

    @BeforeEach
    void pinTheServiceZone() {
        // What SubmissionServiceApplication.main pins: created_at is naive local time in that zone.
        before = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
    }

    @AfterEach
    void restoreTheZone() {
        TimeZone.setDefault(before);
    }

    @Test
    void carriesUserContestSubmittedAtAndGeneration() {
        UUID id = UUID.randomUUID();
        UUID problemId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID contestId = UUID.randomUUID();
        Submission s = Submission.builder().problemId(problemId).userId(userId).contestId(contestId)
                .createdAt(LocalDateTime.parse("2026-10-19T10:00")).rejudgeCount(2).build();
        s.setId(id);

        assertThat(VerdictEvents.recorded(s, 0, true)).isEqualTo(new SubmissionVerdictRecorded(
                id, problemId, 0, true, userId, contestId, Instant.parse("2026-10-19T03:00:00Z"), 2));
    }

    @Test
    void aFirstVerdictIsGenerationZero() {
        Submission s = Submission.builder().problemId(UUID.randomUUID()).userId(UUID.randomUUID())
                .createdAt(LocalDateTime.parse("2026-10-19T10:00")).build();
        s.setId(UUID.randomUUID());

        assertThat(VerdictEvents.recorded(s, -1, false).rejudgeCount()).isZero();
    }

    @Test
    void aReconciledVerdictCarriesTheRowsGeneration() {
        TimedOut t = new TimedOut(UUID.randomUUID(), UUID.randomUUID(), 3, UUID.randomUUID(), null,
                LocalDateTime.parse("2026-10-19T10:00"));

        SubmissionVerdictRecorded e = VerdictEvents.recorded(t, 5);

        assertThat(e.rejudgeCount()).isEqualTo(3);
        assertThat(e.rejudge()).isTrue();
    }
}
