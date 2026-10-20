package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.transaction.support.TransactionOperations;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;
import vn.thanhtuanle.submission.dto.TestCaseResultDto;
import vn.thanhtuanle.submission.mapper.SubmissionMapper;
import vn.thanhtuanle.oj.common.security.CurrentUser;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceDetailsTest {

    @Mock SubmissionRepository submissionRepository;
    @Mock SubmissionMapper submissionMapper;
    @Mock SubmissionDetailAssembler detailAssembler;
    @Mock CurrentUser currentUser;
    @Spy TransactionOperations transactions = TransactionOperations.withoutTransaction();

    @InjectMocks SubmissionService service;

    @Test
    void getById_populatesDetailsFromAssembler() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Submission s = Submission.builder().problemId(UUID.randomUUID()).userId(ownerId).build();
        s.setId(id);
        when(submissionRepository.findById(id)).thenReturn(Optional.of(s));
        when(currentUser.id()).thenReturn(ownerId);

        List<TestCaseResultDto> rows = List.of(
                TestCaseResultDto.builder().name("1").result(0).build());
        when(detailAssembler.assemble(s)).thenReturn(rows);
        when(submissionMapper.toDto(eq(s), any()))
                .thenReturn(SubmissionResponseDto.builder().details(rows).build());

        SubmissionResponseDto dto = service.getSubmissionById(id.toString());

        assertThat(dto.getDetails()).hasSize(1);
        assertThat(dto.getDetails().get(0).getName()).isEqualTo("1");
    }
}
