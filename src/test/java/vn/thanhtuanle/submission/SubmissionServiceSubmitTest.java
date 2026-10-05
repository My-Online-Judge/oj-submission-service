package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.thanhtuanle.messaging.outbox.OutboxWriter;

import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.judge.JudgeService;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.messaging.event.SubmissionRequestedEvent;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.submission.dto.SubmissionRequestDto;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;
import vn.thanhtuanle.submission.mapper.SubmissionMapper;
import vn.thanhtuanle.oj.common.security.CurrentUser;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceSubmitTest {

    @Mock SubmissionRepository submissionRepository;
    @Mock JudgeService judgeService;
    @Mock ProblemCatalog problemCatalog;
    @Mock LanguageRepository languageRepository;
    @Mock SubmissionMapper submissionMapper;
    @Mock CurrentUser currentUser;
    @Mock OutboxWriter outboxWriter;
    @Mock SubmissionSseRegistry sseRegistry;
    @Mock SubmissionRateLimiter submissionRateLimiter;

    @InjectMocks SubmissionService submissionService;

    @Test
    void submit_savesPending_queuesTheJudgeRequestInTheOutbox_andReturnsPending() {
        SubmissionRequestDto req = SubmissionRequestDto.builder()
                .sourceCode("int main(){}").languageIdentifier("cpp")
                .problemSlug("a-plus-b").shareSubmission(false).build();

        JudgeSpec spec = new JudgeSpec(UUID.randomUUID(), "a-plus-b", 1000, 256L, "abc123def456");
        Language language = new Language();
        UUID userId = UUID.randomUUID();

        when(problemCatalog.judgeSpec("a-plus-b")).thenReturn(spec);
        when(languageRepository.findByIdentifier("cpp")).thenReturn(Optional.of(language));
        when(currentUser.id()).thenReturn(userId);
        // simulate JPA's GenerationType.UUID assigning an id on save(), since a bare Mockito
        // mock does not run Hibernate's identifier-generation logic.
        when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        SubmissionRequestedEvent event = SubmissionRequestedEvent.builder().submissionId("x").build();
        when(judgeService.buildRequestedEvent(any(), any(), eq(spec), any())).thenReturn(event);
        when(submissionMapper.toDto(any(Submission.class)))
                .thenReturn(SubmissionResponseDto.builder()
                        .status(SubmissionResult.PENDING.getValue()).build());

        SubmissionResponseDto dto = submissionService.submit(req);

        verify(judgeService).validate("int main(){}");
        ArgumentCaptor<Submission> saved = ArgumentCaptor.forClass(Submission.class);
        verify(submissionRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SubmissionResult.PENDING.getValue());
        assertThat(saved.getValue().getUserId()).as("the submitter is recorded by id").isEqualTo(userId);
        assertThat(saved.getValue().getProblemId()).isEqualTo(spec.problemId());
        assertThat(saved.getValue().getProblemSlug()).isEqualTo("a-plus-b");
        verify(outboxWriter).append("submission.requested", "x", event);
        assertThat(dto.getStatus()).isEqualTo(SubmissionResult.PENDING.getValue());
    }

    @Test
    void submit_inADisabledLanguage_isNotFound_andNeverReachesTheJudge() {
        SubmissionRequestDto req = SubmissionRequestDto.builder()
                .sourceCode("console.log(1)").languageIdentifier("javascript")
                .problemSlug("a-plus-b").shareSubmission(false).build();
        Language disabled = new Language();
        disabled.setDisabled(true);

        when(problemCatalog.judgeSpec("a-plus-b"))
                .thenReturn(new JudgeSpec(UUID.randomUUID(), "a-plus-b", 1000, 256L, "abc123def456"));
        when(languageRepository.findByIdentifier("javascript")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> submissionService.submit(req)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(submissionRateLimiter, submissionRepository, outboxWriter);
    }
}
