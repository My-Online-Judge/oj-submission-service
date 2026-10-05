package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;
import vn.thanhtuanle.submission.dto.TestCaseResultDto;
import vn.thanhtuanle.submission.mapper.SubmissionMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerdictPushTest {

    @Mock SubmissionRepository submissionRepository;
    @Mock SubmissionMapper submissionMapper;
    @Mock SubmissionDetailAssembler detailAssembler;
    @Mock SubmissionSseRegistry sseRegistry;

    private final UUID id = UUID.randomUUID();

    private VerdictPush push(Executor executor) {
        return new VerdictPush(submissionRepository, submissionMapper, detailAssembler, sseRegistry, executor);
    }

    @Test
    void aVerdictNobodyWatchesHereCostsNothing() {
        when(sseRegistry.holds(id.toString())).thenReturn(false);

        push(Runnable::run).pushIfWatched(id.toString());

        verifyNoInteractions(submissionRepository, detailAssembler, submissionMapper);
        verify(sseRegistry, never()).complete(any(), any());
    }

    @Test
    void aWatchedVerdictIsReadAgainAssembledAndPushed() {
        Submission submission = Submission.builder().problemId(UUID.randomUUID()).build();
        List<TestCaseResultDto> rows = List.of(TestCaseResultDto.builder().name("1").sample(true).build());
        SubmissionResponseDto view = SubmissionResponseDto.builder().status(0).details(rows).build();
        when(sseRegistry.holds(id.toString())).thenReturn(true);
        when(submissionRepository.findWithLanguageById(id)).thenReturn(Optional.of(submission));
        when(detailAssembler.assemble(submission)).thenReturn(rows);
        when(submissionMapper.toDto(submission, rows)).thenReturn(view);

        push(Runnable::run).pushIfWatched(id.toString());

        verify(sseRegistry).complete(id.toString(), view);
    }

    @Test
    void theWorkRunsOnThePoolNotOnTheCallingListenerThread() {
        List<Runnable> queued = new ArrayList<>();
        when(sseRegistry.holds(id.toString())).thenReturn(true);

        push(queued::add).pushIfWatched(id.toString());

        assertThat(queued).hasSize(1);
        verifyNoInteractions(submissionRepository, detailAssembler); // nothing done on the caller's thread
    }

    @Test
    void aFullPoolDropsTheLivePushInsteadOfBlockingTheListener() {
        when(sseRegistry.holds(id.toString())).thenReturn(true);
        Executor full = task -> {
            throw new RejectedExecutionException("queue full");
        };

        assertThatCode(() -> push(full).pushIfWatched(id.toString())).doesNotThrowAnyException();
        verifyNoInteractions(submissionRepository);
    }

    @Test
    void aSubmissionGoneBeforeThePushIsSkipped() {
        when(sseRegistry.holds(id.toString())).thenReturn(true);
        when(submissionRepository.findWithLanguageById(id)).thenReturn(Optional.empty());

        assertThatCode(() -> push(Runnable::run).pushIfWatched(id.toString())).doesNotThrowAnyException();
        verify(sseRegistry, never()).complete(any(), any());
    }

    @Test
    void aFailureWhileBuildingTheViewStaysOnThePoolThread() {
        Submission submission = Submission.builder().problemId(UUID.randomUUID()).build();
        when(sseRegistry.holds(id.toString())).thenReturn(true);
        when(submissionRepository.findWithLanguageById(id)).thenReturn(Optional.of(submission));
        when(detailAssembler.assemble(submission)).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> push(Runnable::run).pushIfWatched(id.toString())).doesNotThrowAnyException();
        verify(sseRegistry, never()).complete(any(), any());
    }

    @Test
    void thePoolIsTwoThreadsAndAQueueOfAHundredThatRefusesWhenFull() {
        ThreadPoolExecutor pool = VerdictPush.newExecutor();
        try {
            assertThat(pool.getCorePoolSize()).isEqualTo(2);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
            assertThat(pool.getQueue().remainingCapacity()).isEqualTo(100);
            assertThat(pool.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            pool.shutdown();
        }
    }
}
