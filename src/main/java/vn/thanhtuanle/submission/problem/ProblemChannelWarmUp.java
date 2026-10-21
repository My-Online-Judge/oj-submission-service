package vn.thanhtuanle.submission.problem;

import io.grpc.ManagedChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.util.StringUtils;
import vn.thanhtuanle.oj.common.grpc.ServiceTokenClientInterceptor;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ListVisibleSlugsRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Pays the cold start of the channel to problem-service (TLS handshake, class loading, JIT) once the app is ready, off
 * the request path: the first submit after a deploy used to meet it inside its 2 s deadline and got 503
 * PROBLEMS_UNAVAILABLE. It makes one cheap call ({@code ListVisibleSlugs} with no slugs) on a background thread, with
 * a generous deadline, and outside the circuit breakers, so a failure here counts against nothing. It logs at INFO on
 * success and at WARN on failure, and never throws: start-up and readiness do not wait for it.
 */
@Slf4j
public class ProblemChannelWarmUp {

    private final ProblemInternalGrpc.ProblemInternalBlockingStub stub;
    private final Duration deadline;
    private final Executor executor;

    public ProblemChannelWarmUp(ManagedChannel channel, String token, Duration deadline, Executor executor) {
        ProblemInternalGrpc.ProblemInternalBlockingStub stub = ProblemInternalGrpc.newBlockingStub(channel);
        this.stub = StringUtils.hasText(token) ? stub.withInterceptors(new ServiceTokenClientInterceptor(token)) : stub;
        this.deadline = deadline;
        this.executor = executor;
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        try {
            executor.execute(this::warm);
        } catch (RuntimeException e) {
            log.warn("problem-service channel warm-up could not start: {}", e.toString());
        }
    }

    private void warm() {
        long start = System.nanoTime();
        try {
            stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .listVisibleSlugs(ListVisibleSlugsRequest.getDefaultInstance());
            log.info("problem-service channel warmed in {} ms", (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("problem-service channel warm-up failed after {} ms: {}", (System.nanoTime() - start) / 1_000_000,
                    e.toString());
        }
    }
}
