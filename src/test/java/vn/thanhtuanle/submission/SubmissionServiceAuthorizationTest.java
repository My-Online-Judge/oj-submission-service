package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.transaction.support.TransactionOperations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import vn.thanhtuanle.oj.common.web.error.ResourceNotFoundException;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;
import vn.thanhtuanle.submission.mapper.SubmissionMapper;
import vn.thanhtuanle.oj.common.security.CurrentUser;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceAuthorizationTest {

    @Mock SubmissionRepository submissionRepository;
    @Mock SubmissionMapper submissionMapper;
    @Mock SubmissionDetailAssembler detailAssembler;
    @Mock CurrentUser currentUser;
    @Mock SubmissionSseRegistry sseRegistry;
    @Mock SubmissionAdmission admission;
    @Spy TransactionOperations transactions = TransactionOperations.withoutTransaction();

    @InjectMocks SubmissionService service;

    private Submission ownedBy(UUID submissionId, UUID ownerId) {
        Submission s = Submission.builder().problemId(UUID.randomUUID()).userId(ownerId).build();
        s.setId(submissionId);
        return s;
    }

    @Test
    void owner_canRead() {
        UUID ownerId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        Submission s = ownedBy(subId, ownerId);

        when(submissionRepository.findById(subId)).thenReturn(Optional.of(s));
        when(currentUser.id()).thenReturn(ownerId);
        when(submissionMapper.toDto(eq(s), any()))
                .thenReturn(SubmissionResponseDto.builder().build());

        assertThat(service.getSubmissionById(subId.toString())).isNotNull();
    }

    @Test
    void nonOwner_gets404NotForbidden() {
        UUID subId = UUID.randomUUID();
        Submission s = ownedBy(subId, UUID.randomUUID());

        when(submissionRepository.findById(subId)).thenReturn(Optional.of(s));
        when(currentUser.id()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.getSubmissionById(subId.toString()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void streamVerdict_nonOwnerWithoutReadAny_deniedAndRegistryNeverTouched() {
        UUID subId = UUID.randomUUID();
        Submission s = ownedBy(subId, UUID.randomUUID());

        when(submissionRepository.findById(subId)).thenReturn(Optional.of(s));
        when(currentUser.id()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.streamVerdict(subId.toString()))
                .isInstanceOf(ResourceNotFoundException.class);

        // No emitter may ever be registered for a request that fails the read check —
        // otherwise it would evict a legitimate owner's live emitter (eviction DoS).
        verifyNoInteractions(sseRegistry);
    }

    @Test
    void streamVerdict_unknownId_throwsNotFound() {
        UUID subId = UUID.randomUUID();
        when(submissionRepository.findById(subId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.streamVerdict(subId.toString()))
                .isInstanceOf(ResourceNotFoundException.class);

        // Unknown id must behave identically to a denied id (both 404) so existence can't
        // be inferred from the response shape.
        verifyNoInteractions(sseRegistry);
    }

    @Test
    void getSubmissionsByUser_otherUserWithoutReadAny_deniedAndRepositoryNeverQueried() {
        UUID requestedUserId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.getSubmissionsByUser(requestedUserId.toString(), 0, 10))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(submissionRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void getSubmissionsByUser_ownId_succeeds() {
        UUID userId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        when(submissionRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(Page.empty());

        assertThat(service.getSubmissionsByUser(userId.toString(), 0, 10)).isNotNull();
    }

    @Test
    void getSubmissionsByUserAndProblem_otherUserWithoutReadAny_deniedAndRepositoryNeverQueried() {
        UUID requestedUserId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.getSubmissionsByUserAndProblem(requestedUserId.toString(), "two-sum", 0, 10))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(submissionRepository, never()).findByUserIdAndProblemSlugOrderByCreatedAtDesc(any(), any(), any());
    }

    @Test
    void getSubmissionsByUserAndProblem_ownId_succeeds() {
        UUID userId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        when(submissionRepository.findByUserIdAndProblemSlugOrderByCreatedAtDesc(eq(userId), eq("two-sum"), any()))
                .thenReturn(Page.empty());

        assertThat(service.getSubmissionsByUserAndProblem(userId.toString(), "two-sum", 0, 10)).isNotNull();
    }
}
