package vn.thanhtuanle.submission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionOperations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.judge.JudgeService;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.messaging.event.SubmissionRequestedEvent;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.submission.problem.ProblemCatalogUnavailableException;
import vn.thanhtuanle.submission.problem.ProblemNotFoundException;
import vn.thanhtuanle.submission.dto.SubmissionRequestDto;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;
import vn.thanhtuanle.submission.mapper.SubmissionMapper;
import vn.thanhtuanle.oj.common.security.CurrentUser;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubmissionServiceCooldownTest {

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

    @InjectMocks SubmissionService service;

    private final UUID userId = UUID.randomUUID();
    private SubmissionRequestDto req;

    @BeforeEach
    void setUp() {
        req = new SubmissionRequestDto();
        req.setSourceCode("print(1)");
        req.setLanguageIdentifier("python3");
        req.setProblemSlug("simple-a-plus-b");

        when(problemCatalog.judgeSpec("simple-a-plus-b"))
                .thenReturn(new JudgeSpec(UUID.randomUUID(), "simple-a-plus-b", 1000, 256L, "abc123def456"));
        when(languageRepository.findByIdentifier("python3")).thenReturn(Optional.of(new Language()));
        when(currentUser.id()).thenReturn(userId);
        when(submissionRepository.save(any())).thenAnswer(inv -> {
            Submission s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        when(judgeService.buildRequestedEvent(anyString(), anyString(), any(), any()))
                .thenReturn(SubmissionRequestedEvent.builder().build());
        when(submissionMapper.toDto(any(Submission.class))).thenReturn(new SubmissionResponseDto());
    }

    @Test
    void submit_acquiresCooldown_forTheCurrentUser() {
        assertThatCode(() -> service.submit(req)).doesNotThrowAnyException();
        verify(submissionRateLimiter).acquire(userId);
    }

    @Test
    void unknownProblem_doesNotBurnTheCooldown() {
        when(problemCatalog.judgeSpec("simple-a-plus-b")).thenThrow(new ProblemNotFoundException());
        assertThatThrownBy(() -> service.submit(req)).isInstanceOf(ResourceNotFoundException.class);
        verify(submissionRateLimiter, never()).acquire(any());
    }

    @Test
    void aProblemThatCannotBeJudgedDoesNotBurnTheCooldown() {
        // Before 2a the bundle version was read after the cooldown: a missing bundle cost the user a turn.
        when(problemCatalog.judgeSpec("simple-a-plus-b"))
                .thenThrow(new IllegalStateException("No published test-case bundle"));
        assertThatThrownBy(() -> service.submit(req)).isInstanceOf(IllegalStateException.class);
        verify(submissionRateLimiter, never()).acquire(any());
    }

    @Test
    void problemsUnavailable_doesNotBurnTheCooldown() {
        when(problemCatalog.judgeSpec("simple-a-plus-b")).thenThrow(new ProblemCatalogUnavailableException());
        assertThatThrownBy(() -> service.submit(req)).isInstanceOf(ProblemCatalogUnavailableException.class);
        verify(submissionRateLimiter, never()).acquire(any());
    }

    @Test
    void aFailedTransactionGivesBackTheCooldownAndTheQueueSlot() {
        // No submission exists: the user must not sit out the cooldown for it, nor the queue count it.
        doThrow(new DataAccessResourceFailureException("db down")).when(submissionRepository).save(any());

        assertThatThrownBy(() -> service.submit(req)).isInstanceOf(DataAccessResourceFailureException.class);

        verify(submissionRateLimiter).acquire(userId);
        verify(submissionRateLimiter).release(userId);
        verify(admission).release(1);
    }

    @Test
    void aCooldownRefusalKeepsTheEarlierCooldownButGivesTheSlotBack() {
        // The key belongs to the user's previous submit: deleting it would lift the cooldown.
        doThrow(new RateLimitedException(ErrorCode.SUBMISSION_RATE_LIMITED, 7))
                .when(submissionRateLimiter).acquire(userId);

        assertThatThrownBy(() -> service.submit(req)).isInstanceOf(RateLimitedException.class);

        verify(submissionRateLimiter, never()).release(any());
        verify(admission).release(1);
    }

    @Test
    void withinCooldown_nothingIsPersisted() {
        doThrow(new RateLimitedException(ErrorCode.SUBMISSION_RATE_LIMITED, 7))
                .when(submissionRateLimiter).acquire(userId);
        assertThatThrownBy(() -> service.submit(req)).isInstanceOf(RateLimitedException.class);
        verify(submissionRepository, never()).save(any());
        verify(outboxWriter, never()).append(any(), any(), any());
    }
}
