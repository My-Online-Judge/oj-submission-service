package vn.thanhtuanle.submission.problem;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetJudgeSpecRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetSampleTestCasesRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@link ProblemCatalog} over problem-service's internal gRPC API. Each attempt has its own deadline; the
 * retry wraps the circuit breaker, so every attempt counts and an open breaker is not retried. An attempt is refused at
 * once while the channel recovers from a failure ({@link ChannelRecovery}).
 */
@Slf4j
public class GrpcProblemCatalog implements ProblemCatalog {

    private final ProblemInternalGrpc.ProblemInternalBlockingStub stub;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Duration deadline;
    private final ChannelRecovery recovery;

    public GrpcProblemCatalog(ProblemInternalGrpc.ProblemInternalBlockingStub stub, CircuitBreaker circuitBreaker,
            Retry retry, Duration deadline, ChannelRecovery recovery) {
        this.stub = stub;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.deadline = deadline;
        this.recovery = recovery;
    }

    @Override
    public JudgeSpec judgeSpec(String slug) {
        GetJudgeSpecRequest request = GetJudgeSpecRequest.newBuilder().setProblemSlug(slug).build();
        try {
            var spec = call(() -> withDeadline().getJudgeSpec(request));
            return new JudgeSpec(UUID.fromString(spec.getProblemId()), spec.getProblemSlug(), spec.getTimeLimitMs(),
                    spec.getMemoryLimitMb(), spec.getTestCaseVersion());
        } catch (StatusRuntimeException e) {
            switch (e.getStatus().getCode()) {
                case NOT_FOUND -> throw new ProblemNotFoundException();
                case FAILED_PRECONDITION -> throw new ProblemNotJudgeableException(slug);
                default -> {
                    log.warn("problem-service could not give the judge spec of {}: {}", slug, e.getStatus());
                    throw new ProblemCatalogUnavailableException();
                }
            }
        } catch (CallNotPermittedException e) {
            throw new ProblemCatalogUnavailableException();
        }
    }

    @Override
    public Map<String, SampleTestCase> sampleCases(UUID problemId) {
        GetSampleTestCasesRequest request = GetSampleTestCasesRequest.newBuilder()
                .setProblemId(problemId.toString()).build();
        try {
            Map<String, SampleTestCase> samples = new HashMap<>();
            call(() -> withDeadline().getSampleTestCases(request)).getCasesList().forEach(c -> samples.put(c.getName(),
                    new SampleTestCase(c.getName(), c.getInput(), c.getExpectedOutput())));
            return samples;
        } catch (RuntimeException e) {
            log.warn("Sample test cases of problem {} unavailable, every row shows as hidden: {}", problemId, e.toString());
            return Map.of();
        }
    }

    private ProblemInternalGrpc.ProblemInternalBlockingStub withDeadline() {
        return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private <T> T call(Supplier<T> rpc) {
        // The recovery check runs inside the breaker, so a refusal counts as a failure (sub-project 8 G2).
        Supplier<T> attempt = () -> {
            recovery.admit();
            return rpc.get();
        };
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, attempt)).get();
    }
}
