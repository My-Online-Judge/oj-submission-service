package vn.thanhtuanle.submission;

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
import static org.mockito.ArgumentMatchers.same;
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
    @Mock SubmissionAdmission admission;

    @Spy TransactionOperations transactions = TransactionOperations.withoutTransaction();

    @InjectMocks SubmissionService submissionService;

    private static Submission submission(UUID id, UUID ownerId, SubmissionResult status) {
        Submission s = new Submission();
        s.setId(id);
        s.setUserId(ownerId);
        s.setStatus(status.getValue());
        return s;
    }

    @Test
    void streamVerdict_loadsAndAuthorizesBeforeSubscribing_thenReplaysTerminalVerdict() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Submission s = submission(id, ownerId, SubmissionResult.ACCEPTED);
        SubmissionResponseDto dto = SubmissionResponseDto.builder()
                .status(SubmissionResult.ACCEPTED.getValue()).build();
        SseEmitter emitter = new SseEmitter();
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));
        when(submissionRepository.findStatusById(id)).thenReturn(SubmissionResult.ACCEPTED.getValue());
        when(submissionRepository.findWithLanguageById(id)).thenReturn(Optional.of(s));
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString(), ownerId)).thenReturn(emitter);
        when(submissionMapper.toDto(eq(s), any())).thenReturn(dto);

        SseEmitter result = submissionService.streamVerdict(id.toString());

        assertThat(result).isSameAs(emitter);
        // Ordering is the security property: load + authorize MUST happen before subscribe,
        // so a denied/unknown request never registers (and evicts) an emitter.
        InOrder inOrder = inOrder(submissionRepository, sseRegistry, detailAssembler);
        inOrder.verify(submissionRepository).findById(id);
        inOrder.verify(sseRegistry).subscribe(id.toString(), ownerId);
        // The replay decision must come from the post-subscribe scalar re-read, and the
        // replayed row from a read of its own after it; the sample cases are asked for last.
        inOrder.verify(submissionRepository).findStatusById(id);
        inOrder.verify(submissionRepository).findWithLanguageById(id);
        inOrder.verify(detailAssembler).assemble(s);
        inOrder.verify(sseRegistry).complete(id.toString(), dto);
    }

    @Test
    void streamVerdict_whenPending_doesNotReplay() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Submission s = submission(id, ownerId, SubmissionResult.PENDING);
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));
        when(submissionRepository.findStatusById(id)).thenReturn(SubmissionResult.PENDING.getValue());
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString(), ownerId)).thenReturn(new SseEmitter());

        submissionService.streamVerdict(id.toString());

        verify(submissionRepository, never()).findWithLanguageById(any());
        verify(sseRegistry, never()).complete(any(), any());
        verify(submissionMapper, never()).toDto(any(Submission.class));
    }

    @Test
    void streamVerdict_whenJudging_doesNotReplay() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Submission s = submission(id, ownerId, SubmissionResult.JUDGING);
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));
        when(submissionRepository.findStatusById(id)).thenReturn(SubmissionResult.JUDGING.getValue());
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString(), ownerId)).thenReturn(new SseEmitter());

        submissionService.streamVerdict(id.toString());

        verify(submissionRepository, never()).findWithLanguageById(any());
        verify(sseRegistry, never()).complete(any(), any());
        verify(submissionMapper, never()).toDto(any(Submission.class));
    }

    @Test
    void streamVerdict_verdictArrivesBetweenLoadAndSubscribe_replaysTheCommittedRow() {
        // The missed-verdict race: the authorizing read saw the row PENDING, and the verdict was
        // committed before subscribe(). The replay decision comes from the scalar status query
        // (always the database), and the payload from a read of its own, made after it (no
        // transaction spans streamVerdict, so it shares no persistence context with the first):
        // the committed row, never the PENDING copy the authorizing read loaded.
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Submission loaded = submission(id, ownerId, SubmissionResult.PENDING);
        Submission committed = submission(id, ownerId, SubmissionResult.ACCEPTED);
        SubmissionResponseDto dto = SubmissionResponseDto.builder()
                .status(SubmissionResult.ACCEPTED.getValue()).build();

        when(submissionRepository.findById(id)).thenReturn(Optional.of(loaded));
        when(submissionRepository.findStatusById(id)).thenReturn(SubmissionResult.ACCEPTED.getValue());
        when(submissionRepository.findWithLanguageById(id)).thenReturn(Optional.of(committed));
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString(), ownerId)).thenReturn(new SseEmitter());
        when(submissionMapper.toDto(same(committed), any())).thenReturn(dto);

        submissionService.streamVerdict(id.toString());

        verify(sseRegistry).complete(id.toString(), dto);
        verify(detailAssembler).assemble(committed);
        verify(submissionMapper, never()).toDto(same(loaded), any());
    }

    @Test
    void streamVerdict_whenRowGoneAtScalarReRead_doesNotReplay() {
        // Row deleted between load and re-read: null scalar means no replay decision can be
        // made — leave the emitter to the registry timeout, no special-casing.
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Submission s = submission(id, ownerId, SubmissionResult.PENDING);
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));
        when(submissionRepository.findStatusById(id)).thenReturn(null);
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString(), ownerId)).thenReturn(new SseEmitter());

        submissionService.streamVerdict(id.toString());

        verify(submissionRepository, never()).findWithLanguageById(any());
        verify(sseRegistry, never()).complete(any(), any());
    }

    @Test
    void streamVerdict_whenRowGoneAtTheReplayRead_doesNotReplay() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(submissionRepository.findById(id)).thenReturn(Optional.of(submission(id, ownerId, SubmissionResult.PENDING)));
        when(submissionRepository.findStatusById(id)).thenReturn(SubmissionResult.ACCEPTED.getValue());
        when(submissionRepository.findWithLanguageById(id)).thenReturn(Optional.empty());
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString(), ownerId)).thenReturn(new SseEmitter());

        submissionService.streamVerdict(id.toString());

        verifyNoInteractions(detailAssembler);
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

    @Test
    void streamVerdict_whenTheReadAfterSubscribingThrows_theEmitterIsRemoved() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter();
        when(submissionRepository.findById(id)).thenReturn(Optional.of(submission(id, ownerId, SubmissionResult.PENDING)));
        when(submissionRepository.findStatusById(id)).thenThrow(new IllegalStateException("db blip"));
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString(), ownerId)).thenReturn(emitter);

        assertThatThrownBy(() -> submissionService.streamVerdict(id.toString()))
                .isInstanceOf(IllegalStateException.class).hasMessage("db blip");

        verify(sseRegistry).onDone(id.toString(), emitter);
    }

    @Test
    void streamVerdict_whenTheReplayReadThrows_theEmitterIsRemoved() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter();
        when(submissionRepository.findById(id)).thenReturn(Optional.of(submission(id, ownerId, SubmissionResult.PENDING)));
        when(submissionRepository.findStatusById(id)).thenReturn(SubmissionResult.ACCEPTED.getValue());
        when(submissionRepository.findWithLanguageById(id)).thenThrow(new IllegalStateException("db blip"));
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString(), ownerId)).thenReturn(emitter);

        assertThatThrownBy(() -> submissionService.streamVerdict(id.toString()))
                .isInstanceOf(IllegalStateException.class);

        verify(sseRegistry).onDone(id.toString(), emitter);
    }

    @Test
    void streamVerdict_whenTheReadsSucceed_theEmitterStaysRegistered() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(submissionRepository.findById(id)).thenReturn(Optional.of(submission(id, ownerId, SubmissionResult.PENDING)));
        when(submissionRepository.findStatusById(id)).thenReturn(SubmissionResult.PENDING.getValue());
        when(currentUser.id()).thenReturn(ownerId);
        when(sseRegistry.subscribe(id.toString(), ownerId)).thenReturn(new SseEmitter());

        submissionService.streamVerdict(id.toString());

        verify(sseRegistry, never()).onDone(any(), any());
    }
}
