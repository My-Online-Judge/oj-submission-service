package vn.thanhtuanle.submission;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.submission.mapper.SubmissionMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pushes a verdict to the SSE subscriber this instance holds for it. Every instance hears every verdict
 * ({@code VerdictPubSub}); only the one holding the emitter does any work, on its own small pool: read the submission
 * again, build its full view — the sample test cases come from problem-service — and complete the emitter. Neither the
 * Kafka consumer nor the Redis listener ever waits for problem-service.
 */
@Component
@Slf4j
public class VerdictPush {

    static final int THREADS = 2;
    static final int QUEUE_CAPACITY = 100;

    private final SubmissionRepository submissionRepository;
    private final SubmissionMapper submissionMapper;
    private final SubmissionDetailAssembler detailAssembler;
    private final SubmissionSseRegistry sseRegistry;
    private final Executor executor;

    @Autowired
    public VerdictPush(SubmissionRepository submissionRepository, SubmissionMapper submissionMapper,
                       SubmissionDetailAssembler detailAssembler, SubmissionSseRegistry sseRegistry) {
        this(submissionRepository, submissionMapper, detailAssembler, sseRegistry, newExecutor());
    }

    VerdictPush(SubmissionRepository submissionRepository, SubmissionMapper submissionMapper,
                SubmissionDetailAssembler detailAssembler, SubmissionSseRegistry sseRegistry, Executor executor) {
        this.submissionRepository = submissionRepository;
        this.submissionMapper = submissionMapper;
        this.detailAssembler = detailAssembler;
        this.sseRegistry = sseRegistry;
        this.executor = executor;
    }

    /** Two threads and a queue of a hundred; a full queue refuses the task instead of running it on the caller. */
    static ThreadPoolExecutor newExecutor() {
        AtomicInteger count = new AtomicInteger();
        return new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                task -> {
                    Thread thread = new Thread(task, "verdict-push-" + count.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** For every verdict broadcast: a no-op unless this instance holds the submission's SSE emitter. */
    public void pushIfWatched(String submissionId) {
        if (!sseRegistry.holds(submissionId)) {
            return;
        }
        try {
            executor.execute(() -> push(submissionId));
        } catch (RejectedExecutionException e) {
            // The client still reads its verdict with GET /submissions/{id}.
            log.warn("Verdict push queue full, live push of submission {} dropped", submissionId);
        }
    }

    private void push(String submissionId) {
        MDC.put("submissionId", submissionId);
        try {
            Optional<Submission> found = submissionRepository.findWithLanguageById(UUID.fromString(submissionId));
            if (found.isEmpty()) {
                log.warn("Verdict of submission {} not pushed: the submission is gone", submissionId);
                return;
            }
            Submission submission = found.get();
            sseRegistry.complete(submissionId, submissionMapper.toDto(submission, detailAssembler.assemble(submission)));
        } catch (RuntimeException e) {
            log.error("Failed to push the verdict of submission {}", submissionId, e);
        } finally {
            MDC.remove("submissionId");
        }
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdown();
        }
    }
}
