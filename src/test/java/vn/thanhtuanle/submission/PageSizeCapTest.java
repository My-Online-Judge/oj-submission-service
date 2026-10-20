package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.support.TransactionOperations;

import vn.thanhtuanle.oj.common.identity.PublicUserClient;
import vn.thanhtuanle.oj.common.security.CurrentUser;
import vn.thanhtuanle.submission.mapper.SubmissionMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A client cannot ask for an unbounded page: {@code size} is clamped to 100, never a 400. */
@ExtendWith(MockitoExtension.class)
class PageSizeCapTest {

    @Mock SubmissionRepository submissionRepository;
    @Mock SubmissionMapper submissionMapper;
    @Mock CurrentUser currentUser;
    @Mock PublicUserClient publicUserClient;
    @Spy TransactionOperations transactions = TransactionOperations.withoutTransaction();

    @InjectMocks SubmissionService service;

    private final ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

    @Test
    @SuppressWarnings("unchecked")
    void searchClampsTo100() {
        when(submissionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.search(new SubmissionSearch("a-plus-b", null, null, null, null, 0, 500));

        verify(submissionRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void byUserClampsTo100() {
        UUID me = UUID.randomUUID();
        when(currentUser.id()).thenReturn(me);
        when(submissionRepository.findByUserIdOrderByCreatedAtDesc(eq(me), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.getSubmissionsByUser(me.toString(), 0, 500);

        verify(submissionRepository).findByUserIdOrderByCreatedAtDesc(eq(me), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void byUserAndProblemClampsTo100() {
        UUID me = UUID.randomUUID();
        when(currentUser.id()).thenReturn(me);
        when(submissionRepository.findByUserIdAndProblemSlugOrderByCreatedAtDesc(eq(me), eq("a-plus-b"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.getSubmissionsByUserAndProblem(me.toString(), "a-plus-b", 0, 500);

        verify(submissionRepository).findByUserIdAndProblemSlugOrderByCreatedAtDesc(eq(me), eq("a-plus-b"), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }
}
