package vn.thanhtuanle.submission;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of SSE subscribers keyed by submissionId.
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
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String submissionId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.put(submissionId, emitter);
        emitter.onCompletion(() -> emitters.remove(submissionId));
        emitter.onTimeout(() -> emitters.remove(submissionId));
        return emitter;
    }

    /** Whether this instance holds an SSE subscriber for the submission. */
    public boolean holds(String submissionId) {
        return emitters.containsKey(submissionId);
    }

    public void complete(String submissionId, SubmissionResponseDto payload) {
        SseEmitter emitter = emitters.remove(submissionId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("verdict").data(payload));
            emitter.complete();
        } catch (IOException e) {
            log.warn("Failed to push SSE verdict for {}", submissionId, e);
            emitter.completeWithError(e);
        }
    }
}
