package vn.thanhtuanle.submission;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.transaction.support.TransactionOperations;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.thanhtuanle.messaging.outbox.OutboxWriter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.judge.JudgeService;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;
import vn.thanhtuanle.submission.mapper.SubmissionMapper;
import vn.thanhtuanle.oj.common.security.CurrentUser;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceStreamTest {

    @Mock SubmissionRepository submissionRepository;
    @Mock JudgeService judgeService;
    @Mock ProblemCatalog problemCatalog;
    @Mock LanguageRepository languageRepository;
    @Mock SubmissionMapper submissionMapper;
    @Mock CurrentUser currentUser;
    @Mock OutboxWriter outboxWriter;
    @Mock SubmissionSseRegistry sseRegistry;
    @Mock SubmissionDetailAssembler detailAssembler;
    @Mock EntityManager entityManager;
    @Mock SubmissionAdmission admission;

    @Spy TransactionOperations transactions = TransactionOperations.withoutTransaction();

    @InjectMocks SubmissionService submissionService;

    @Test
    void streamVerdict_loadsAndAuthorizesBeforeSubscribing_thenReplaysTerminalVerdict() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Submission s = new Submission();
        s.setId(id);
        s.setUserId(ownerId);
        s.setStatus(SubmissionResult.ACCEPTED.getValue());
        SubmissionResponseDto dto = SubmissionResponseDto.builder()
                .status(SubmissionResult.ACCEPTED.getValue()).build();
        SseEmitter emitter = new SseEmitter();
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));
        when(submissionRepository.findStatusById(id)).thenReturn(SubmissionResult.ACCEPTED.getValue());
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString())).thenReturn(emitter);
        when(submissionMapper.toDto(eq(s), any())).thenReturn(dto);

        SseEmitter result = submissionService.streamVerdict(id.toString());

        assertThat(result).isSameAs(emitter);
        // Ordering is the security property: load + authorize MUST happen before subscribe,
        // so a denied/unknown request never registers (and evicts) an emitter.
        InOrder inOrder = inOrder(submissionRepository, sseRegistry);
        inOrder.verify(submissionRepository).findById(id);
        inOrder.verify(sseRegistry).subscribe(id.toString());
        // The replay decision must come from the post-subscribe scalar re-read, and the
        // replayed entity must be refreshed from the database before mapping.
        inOrder.verify(submissionRepository).findStatusById(id);
        verify(entityManager).refresh(s);
        verify(sseRegistry).complete(id.toString(), dto);
    }

    @Test
    void streamVerdict_whenPending_doesNotReplay() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Submission s = new Submission();
        s.setId(id);
        s.setUserId(ownerId);
        s.setStatus(SubmissionResult.PENDING.getValue());
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));
        when(submissionRepository.findStatusById(id)).thenReturn(SubmissionResult.PENDING.getValue());
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString())).thenReturn(new SseEmitter());

        submissionService.streamVerdict(id.toString());

        verify(entityManager, never()).refresh(any());
        verify(sseRegistry, never()).complete(any(), any());
        verify(submissionMapper, never()).toDto(any(Submission.class));
    }

    @Test
    void streamVerdict_whenJudging_doesNotReplay() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Submission s = new Submission();
        s.setId(id);
        s.setUserId(ownerId);
        s.setStatus(SubmissionResult.JUDGING.getValue());
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));
        when(submissionRepository.findStatusById(id)).thenReturn(SubmissionResult.JUDGING.getValue());
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString())).thenReturn(new SseEmitter());

        submissionService.streamVerdict(id.toString());

        verify(entityManager, never()).refresh(any());
        verify(sseRegistry, never()).complete(any(), any());
        verify(submissionMapper, never()).toDto(any(Submission.class));
    }

    @Test
    void streamVerdict_verdictArrivesBetweenLoadAndSubscribe_replaysFromFreshScalarRead() {
        // Pins the missed-verdict race the way production JPA actually behaves: within one
        // persistence context a second findById returns the SAME managed (stale) instance —
        // it is a no-op, not a fresh read. So the terminal replay decision must come from a
        // scalar status query (always hits the DB), and the entity must then be refresh()ed
        // before mapping the replay payload.
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        // The one managed instance: PENDING when loaded for authorization, and — exactly like
        // the L1 cache — still PENDING if findById were asked again.
        Submission managed = new Submission();
        managed.setId(id);
        managed.setUserId(ownerId);
        managed.setStatus(SubmissionResult.PENDING.getValue());

        SubmissionResponseDto dto = SubmissionResponseDto.builder()
                .status(SubmissionResult.ACCEPTED.getValue()).build();
        SseEmitter emitter = new SseEmitter();

        when(submissionRepository.findById(id)).thenReturn(Optional.of(managed));
        // The verdict "landed" between load and subscribe: the committed row is terminal.
        when(submissionRepository.findStatusById(id)).thenReturn(SubmissionResult.ACCEPTED.getValue());
        // refresh() re-reads the row into the managed instance, like real EntityManager.refresh.
        doAnswer(inv -> {
            managed.setStatus(SubmissionResult.ACCEPTED.getValue());
            return null;
        }).when(entityManager).refresh(managed);
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString())).thenReturn(emitter);
        when(submissionMapper.toDto(eq(managed), any())).thenReturn(dto);

        submissionService.streamVerdict(id.toString());

        verify(entityManager).refresh(managed);
        verify(sseRegistry).complete(id.toString(), dto);
        assertThat(managed.getStatus()).isEqualTo(SubmissionResult.ACCEPTED.getValue());
    }

    @Test
    void streamVerdict_whenRowGoneAtScalarReRead_doesNotReplay() {
        // Row deleted between load and re-read: null scalar means no replay decision can be
        // made — leave the emitter to the registry timeout, no special-casing.
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Submission s = new Submission();
        s.setId(id);
        s.setUserId(ownerId);
        s.setStatus(SubmissionResult.PENDING.getValue());
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));
        when(submissionRepository.findStatusById(id)).thenReturn(null);
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString())).thenReturn(new SseEmitter());

        submissionService.streamVerdict(id.toString());

        verify(entityManager, never()).refresh(any());
        verify(sseRegistry, never()).complete(any(), any());
    }

    @Test
    void streamVerdict_whenNotFound_throwsResourceNotFound() {
        // Behavior change (approved): unknown id used to complete an already-registered
        // emitter with no verdict (200-empty). It now 404s before ever subscribing, so an
        // unknown id is indistinguishable from a denied id (closes the id-existence oracle).
        UUID id = UUID.randomUUID();
        when(submissionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> submissionService.streamVerdict(id.toString()))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(sseRegistry);
    }
}
