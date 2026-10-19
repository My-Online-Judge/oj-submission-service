package vn.thanhtuanle.messaging;

import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.judge.dto.JudgeResultDto;
import vn.thanhtuanle.messaging.event.SubmissionJudgedEvent;
import vn.thanhtuanle.metrics.OjMetrics;
import vn.thanhtuanle.submission.SubmissionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The judge's per-test-case results reach the conditional write as they came (stored as JSON, see the Postgres test). */
@ExtendWith(MockitoExtension.class)
class JudgeResultConsumerDetailsTest {

    @Mock SubmissionRepository submissionRepository;
    @Mock VerdictPubSub verdictPubSub;
    @Mock OjMetrics ojMetrics;
    @Mock OutboxWriter outboxWriter;
    @InjectMocks JudgeResultConsumer consumer;

    @Captor ArgumentCaptor<List<JudgeResultDto>> details;

    private void oneRowUpdated(UUID id, int status) {
        Submission s = Submission.builder().problemId(UUID.randomUUID())
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
        s.setId(id);
        when(submissionRepository.applyVerdict(eq(id), anyInt(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(1);
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));
    }

    private List<JudgeResultDto> writtenDetails(UUID id) {
        verify(submissionRepository).applyVerdict(eq(id), anyInt(), any(), any(), anyInt(), any(), any(),
                details.capture(), any());
        return details.getValue();
    }

    @Test
    void persistsDetails_fromEvent() {
        UUID id = UUID.randomUUID();
        oneRowUpdated(id, SubmissionResult.WRONG_ANSWER.getValue());

        JudgeResultDto case1 = JudgeResultDto.builder()
                .testCase("1").result(0).cpuTime(12).realTime(15).memory(3072L).build();
        JudgeResultDto case2 = JudgeResultDto.builder()
                .testCase("2").result(-1).cpuTime(9).realTime(11).memory(3000L).build();

        SubmissionJudgedEvent e = SubmissionJudgedEvent.builder()
                .submissionId(id.toString())
                .status(SubmissionResult.WRONG_ANSWER.getValue())
                .result(-1).cpuTime(12).realTime(15).memory(3072L)
                .details(List.of(case1, case2))
                .build();

        consumer.onJudged(e);

        List<JudgeResultDto> written = writtenDetails(id);
        assertThat(written).hasSize(2);
        assertThat(written.get(1).getTestCase()).isEqualTo("2");
        assertThat(written.get(1).getResult()).isEqualTo(-1);
    }

    @Test
    void persistsEmptyDetails_onCompileError() {
        UUID id = UUID.randomUUID();
        oneRowUpdated(id, SubmissionResult.COMPILE_ERROR.getValue());

        SubmissionJudgedEvent e = SubmissionJudgedEvent.builder()
                .submissionId(id.toString())
                .status(SubmissionResult.COMPILE_ERROR.getValue())
                .errorMessage("CompileError: expected ';'")
                .details(List.of())
                .build();

        consumer.onJudged(e);

        assertThat(writtenDetails(id)).isEmpty();
    }
}
