package vn.thanhtuanle.submission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory registry of SSE subscribers keyed by submissionId, several per submission (a console plus a history row, or two tabs).
 *
 * <p>Deliberately instance-local: it only holds emitters for connections terminated by THIS
 * instance. Cross-instance delivery is handled upstream by {@code VerdictPubSub}, which broadcasts
 * every verdict over Redis so each instance calls {@link #complete} — a no-op on instances that
 * don't hold the emitter.
 */
@Component
@Slf4j
public class SubmissionSseRegistry {

    private static final long TIMEOUT_MS = 5 * 60 * 1000L;
    static final int MAX_PER_SUBMISSION = 8;
    static final int MAX_TOTAL = 2_000;
    static final int MAX_PER_USER = 20;
    private static final long RETRY_AFTER_SECONDS = 5;

    /** Emitters per submission, oldest first. Mutated only inside {@code compute}/{@code remove}. */
    private final Map<String, Deque<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    /** Open streams per user, and whose each registered emitter is (only for emitters registered with a user). */
    private final Map<UUID, Integer> perUser = new ConcurrentHashMap<>();
    private final Map<SseEmitter, UUID> owners = new ConcurrentHashMap<>();
    private final int maxPerSubmission;
    private final int maxTotal;
    private final int maxPerUser;

    public SubmissionSseRegistry() {
        this(MAX_PER_SUBMISSION, MAX_TOTAL, MAX_PER_USER);
    }

    SubmissionSseRegistry(int maxPerSubmission, int maxTotal, int maxPerUser) {
        this.maxPerSubmission = maxPerSubmission;
        this.maxTotal = maxTotal;
        this.maxPerUser = maxPerUser;
    }

    /** @param userId whose stream it is; null (none known) is counted against the global cap only */
    public SseEmitter subscribe(String submissionId, UUID userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitter.onCompletion(() -> onDone(submissionId, emitter));
        emitter.onTimeout(() -> onDone(submissionId, emitter));
        emitter.onError(e -> onDone(submissionId, emitter));
        register(submissionId, emitter, userId);
        return emitter;
    }

    /**
     * Adds a subscriber. Beyond the per-submission cap the oldest one is evicted and completed
     * (outside any map lock); beyond the per-user or the per-JVM cap the new stream is refused.
     */
    void register(String submissionId, SseEmitter emitter, UUID userId) {
        if (userId != null && !claimUserSlot(userId)) {
            throw new RateLimitedException(ErrorCode.STREAMS_FULL, RETRY_AFTER_SECONDS);
        }
        if (total.incrementAndGet() > maxTotal) {
            total.decrementAndGet();
            releaseUserSlot(userId);
            throw new RateLimitedException(ErrorCode.STREAMS_FULL, RETRY_AFTER_SECONDS);
        }
        if (userId != null) {
            owners.put(emitter, userId);
        }
        List<SseEmitter> evicted = new ArrayList<>(1);
        emitters.compute(submissionId, (id, deque) -> {
            Deque<SseEmitter> d = deque == null ? new ArrayDeque<>() : deque;
            d.addLast(emitter);
            while (d.size() > maxPerSubmission) {
                evicted.add(d.pollFirst());
            }
            return d;
        });
        total.addAndGet(-evicted.size());
        for (SseEmitter old : evicted) {
            release(old);
            try {
                old.complete();
            } catch (Exception e) {
                log.debug("Evicted SSE emitter for {} was already gone", submissionId, e);
            }
        }
    }

    /** Completion, timeout and error all end up here; a repeated call is a no-op. */
    void onDone(String submissionId, SseEmitter emitter) {
        boolean[] removed = {false};
        emitters.computeIfPresent(submissionId, (id, deque) -> {
            removed[0] = deque.remove(emitter);
            return deque.isEmpty() ? null : deque;
        });
        if (removed[0]) {
            total.decrementAndGet();
            release(emitter);
        }
    }

    private boolean claimUserSlot(UUID userId) {
        boolean[] claimed = {false};
        perUser.compute(userId, (id, n) -> {
            int held = n == null ? 0 : n;
            claimed[0] = held < maxPerUser;
            return claimed[0] ? held + 1 : n;
        });
        return claimed[0];
    }

    private void releaseUserSlot(UUID userId) {
        if (userId != null) {
            perUser.computeIfPresent(userId, (id, n) -> n <= 1 ? null : n - 1);
        }
    }

    /** Frees the user slot of an emitter that left the registry. */
    private void release(SseEmitter emitter) {
        releaseUserSlot(owners.remove(emitter));
    }

    /** Whether this instance holds an SSE subscriber for the submission. */
    public boolean holds(String submissionId) {
        return emitters.containsKey(submissionId);
    }

    /** Number of live emitters held by this instance. */
    int size() {
        return total.get();
    }

    public void complete(String submissionId, SubmissionResponseDto payload) {
        Deque<SseEmitter> subscribers = emitters.remove(submissionId);
        if (subscribers == null) {
            return;
        }
        total.addAndGet(-subscribers.size());
        for (SseEmitter emitter : subscribers) {
            release(emitter);
            try {
                emitter.send(SseEmitter.event().name("verdict").data(payload));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Failed to push SSE verdict for {}", submissionId, e);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                    // already completed or the connection is gone; the emitter is out of the map
                }
            }
        }
    }
}
