package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionOperations;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.common.exception.GlobalExceptionHandler;
import vn.thanhtuanle.oj.common.web.error.AppException;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;
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
import vn.thanhtuanle.submission.problem.ProblemNotFoundException;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @Mock SubmissionAdmission admission;

    @Spy TransactionOperations transactions = TransactionOperations.withoutTransaction();

    @InjectMocks SubmissionService submissionService;

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private void signInWith(String... authorities) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.TestingAuthenticationToken("u", "p", authorities));
    }

    private void submitTo(String slug) {
        when(problemCatalog.judgeSpec(eq(slug), anyBoolean(), isNull())).thenThrow(new ProblemNotFoundException());
        assertThatThrownBy(() -> submissionService.submit(SubmissionRequestDto.builder()
                .sourceCode("int main(){}").languageIdentifier("cpp").problemSlug(slug).build()))
                .isInstanceOf(ProblemNotFoundException.class);
    }

    @Test
    void problemEditorsMayJudgeInactiveProblems() {
        signInWith("problem:update");
        submitTo("hidden");
        verify(problemCatalog).judgeSpec("hidden", true, null);
    }

    @Test
    void ordinaryUsersCannot() {
        signInWith("submission:read-any", "problem:read");
        submitTo("hidden");
        verify(problemCatalog).judgeSpec("hidden", false, null);
    }

    @Test
    void submit_savesPending_queuesTheJudgeRequestInTheOutbox_andReturnsPending() {
        SubmissionRequestDto req = SubmissionRequestDto.builder()
                .sourceCode("int main(){}").languageIdentifier("cpp")
                .problemSlug("a-plus-b").shareSubmission(false).build();

        JudgeSpec spec = new JudgeSpec(UUID.randomUUID(), "a-plus-b", 1000, 256L, "abc123def456");
        Language language = new Language();
        UUID userId = UUID.randomUUID();

        when(problemCatalog.judgeSpec("a-plus-b", false, null)).thenReturn(spec);
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
        // A new submission is generation 0.
        when(judgeService.buildRequestedEvent(any(), any(), eq(spec), any(), eq(0))).thenReturn(event);
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
        verify(admission).admit(1);
        verify(admission, never()).release(anyInt());
        verify(submissionRateLimiter, never()).release(any());
    }

    @Test
    void aFullQueueRefusesBeforeTheProblemLookupAndTheCooldown() {
        doThrow(new RateLimitedException(ErrorCode.QUEUE_FULL, SubmissionAdmission.RETRY_AFTER_SECONDS))
                .when(admission).admit(1);

        // A contest submit too: the queue answers before the contest is looked at.
        assertThatThrownBy(() -> submissionService.submit(SubmissionRequestDto.builder()
                .sourceCode("int main(){}").languageIdentifier("cpp").problemSlug("a-plus-b")
                .contestSlug("icpc-1").build()))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUEUE_FULL));

        verify(judgeService).validate("int main(){}");
        verifyNoInteractions(problemCatalog, languageRepository, currentUser, submissionRateLimiter,
                submissionRepository, outboxWriter);
        verify(admission, never()).release(anyInt());  // nothing was reserved
    }

    @Test
    void anInvalidSourceReservesNoQueueSlot() {
        doThrow(new IllegalArgumentException("Source code cannot be empty")).when(judgeService).validate("");

        assertThatThrownBy(() -> submissionService.submit(SubmissionRequestDto.builder()
                .sourceCode("").languageIdentifier("cpp").problemSlug("a-plus-b").build()))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(admission);
    }

    @Test
    void aRefusalAfterTheReservationGivesTheSlotBack() {
        // A 404 burns no cooldown, so without the release a stream of them could fill the queue for 5 s.
        when(problemCatalog.judgeSpec("no-such-problem", false, null)).thenThrow(new ProblemNotFoundException());

        assertThatThrownBy(() -> submissionService.submit(SubmissionRequestDto.builder()
                .sourceCode("int main(){}").languageIdentifier("cpp").problemSlug("no-such-problem").build()))
                .isInstanceOf(ProblemNotFoundException.class);

        verify(admission).admit(1);
        verify(admission).release(1);
        verifyNoInteractions(submissionRateLimiter);
    }

    @Test
    void submit_inADisabledLanguage_isNotFound_andNeverReachesTheJudge() {
        SubmissionRequestDto req = SubmissionRequestDto.builder()
                .sourceCode("console.log(1)").languageIdentifier("javascript")
                .problemSlug("a-plus-b").shareSubmission(false).build();
        Language disabled = new Language();
        disabled.setDisabled(true);

        when(problemCatalog.judgeSpec("a-plus-b", false, null))
                .thenReturn(new JudgeSpec(UUID.randomUUID(), "a-plus-b", 1000, 256L, "abc123def456"));
        when(languageRepository.findByIdentifier("javascript")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> submissionService.submit(req)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(submissionRateLimiter, submissionRepository, outboxWriter);
    }

    @Test
    void aCooldownRefusalOpensNoTransaction() {
        TransactionOperations mockTransactions = mock(TransactionOperations.class);
        ReflectionTestUtils.setField(submissionService, "transactions", mockTransactions);
        UUID userId = UUID.randomUUID();
        when(problemCatalog.judgeSpec("a-plus-b", false, null))
                .thenReturn(new JudgeSpec(UUID.randomUUID(), "a-plus-b", 1000, 256L, "abc123def456"));
        when(languageRepository.findByIdentifier("cpp")).thenReturn(Optional.of(new Language()));
        when(currentUser.id()).thenReturn(userId);
        doThrow(new RateLimitedException(ErrorCode.SUBMISSION_RATE_LIMITED, 7))
                .when(submissionRateLimiter).acquire(userId);

        assertThatThrownBy(() -> submissionService.submit(SubmissionRequestDto.builder()
                .sourceCode("int main(){}").languageIdentifier("cpp").problemSlug("a-plus-b").build()))
                .isInstanceOf(RateLimitedException.class);

        verifyNoInteractions(mockTransactions);
    }

    @Test
    void contestSubmissionStoresContestId() {
        UUID contestId = UUID.randomUUID();
        JudgeSpec spec = new JudgeSpec(UUID.randomUUID(), "a-plus-b", 1000, 256L, "abc123def456", contestId);
        when(problemCatalog.judgeSpec("a-plus-b", false, "icpc-1")).thenReturn(spec);
        when(languageRepository.findByIdentifier("cpp")).thenReturn(Optional.of(new Language()));
        when(currentUser.id()).thenReturn(UUID.randomUUID());
        when(submissionRepository.save(any(Submission.class))).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        when(judgeService.buildRequestedEvent(any(), any(), eq(spec), any(), eq(0)))
                .thenReturn(SubmissionRequestedEvent.builder().submissionId("x").build());

        submissionService.submit(SubmissionRequestDto.builder()
                .sourceCode("int main(){}").languageIdentifier("cpp").problemSlug("a-plus-b")
                .contestSlug("icpc-1").build());

        ArgumentCaptor<Submission> saved = ArgumentCaptor.forClass(Submission.class);
        verify(submissionRepository).save(saved.capture());
        assertThat(saved.getValue().getContestId()).isEqualTo(contestId);
    }

    @Test
    void contestNotRunningIsRefusedBeforeTheCooldown() {
        when(problemCatalog.judgeSpec("a-plus-b", false, "ended"))
                .thenThrow(new AppException(ErrorCode.CONTEST_NOT_RUNNING));

        assertThatThrownBy(() -> submissionService.submit(SubmissionRequestDto.builder()
                .sourceCode("int main(){}").languageIdentifier("cpp").problemSlug("a-plus-b")
                .contestSlug("ended").build()))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONTEST_NOT_RUNNING));

        verifyNoInteractions(submissionRateLimiter, outboxWriter);
        verify(submissionRepository, never()).save(any());
        verify(admission).admit(1);
        verify(admission, times(1)).release(1);
    }

    @Test
    void badContestSlugIs400() throws Exception {
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new SubmissionController(submissionService, mock(RejudgeService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/submissions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceCode": "int main(){}", "languageIdentifier": "cpp", "problemSlug": "a-plus-b",
                         "contestSlug": "Not A Slug!"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.contestSlug").exists());

        verifyNoInteractions(judgeService, admission, problemCatalog, submissionRepository);
    }
}
