package vn.thanhtuanle.submission.problem;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.time.Duration;
import java.util.Set;

/**
 * The policy around every call to problem-service. The judge spec and the sample test cases share the breaker and retry
 * {@value #NAME}; the profile's visible slugs have their own, {@value #VISIBILITY_NAME}, with the same settings, so
 * anonymous profile traffic never opens the breaker that gates submits. A breaker counts each attempt:
 * 20-call window, judged from 10 calls, open at 50 % failures for 10 s, then three probe calls decide
 * (the default ten kept a quiet site HALF_OPEN, and its alert firing, long after a recovery). Only
 * UNAVAILABLE is retried, twice, 50 ms apart (sub-project 7 F3: a failed call costs ~0.1 s, not ~0.4 s); a deadline
 * that passed is not (the next attempt would wait as long again).
 */
public final class ProblemServiceResilience {

    public static final String NAME = "problem-service";
    public static final String VISIBILITY_NAME = "problem-service-visibility";

    /** Answers about the request, not failures of the service: they never open the breaker. */
    private static final Set<Status.Code> ANSWERS =
            Set.of(Status.Code.NOT_FOUND, Status.Code.FAILED_PRECONDITION, Status.Code.INVALID_ARGUMENT);

    private ProblemServiceResilience() {
    }

    public static CircuitBreakerConfig circuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(ProblemServiceResilience::isFailure)
                .build();
    }

    public static RetryConfig retryConfig() {
        return RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(50))
                .retryOnException(e -> e instanceof StatusRuntimeException s
                        && s.getStatus().getCode() == Status.Code.UNAVAILABLE)
                .build();
    }

    static boolean isFailure(Throwable e) {
        return !(e instanceof StatusRuntimeException s && ANSWERS.contains(s.getStatus().getCode()));
    }
}
