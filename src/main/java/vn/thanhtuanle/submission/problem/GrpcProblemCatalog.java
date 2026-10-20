package vn.thanhtuanle.submission.problem;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.oj.common.grpc.ProblemRpcReasons;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetJudgeSpecRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetSampleTestCasesRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ListVisibleSlugsRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;
import vn.thanhtuanle.oj.common.web.error.AppException;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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
    public JudgeSpec judgeSpec(String slug, boolean allowInactive, String contestSlug) {
        GetJudgeSpecRequest.Builder builder = GetJudgeSpecRequest.newBuilder().setProblemSlug(slug)
                .setAllowInactive(allowInactive);
        if (contestSlug != null) {
            builder.setContestSlug(contestSlug);
        }
        GetJudgeSpecRequest request = builder.build();
        try {
            var spec = call(() -> withDeadline().getJudgeSpec(request));
            // An empty contest_id means none: practice, or a problem-service that predates contests.
            UUID contestId = spec.getContestId().isEmpty() ? null : UUID.fromString(spec.getContestId());
            return new JudgeSpec(UUID.fromString(spec.getProblemId()), spec.getProblemSlug(), spec.getTimeLimitMs(),
                    spec.getMemoryLimitMb(), spec.getTestCaseVersion(), contestId);
        } catch (StatusRuntimeException e) {
            switch (e.getStatus().getCode()) {
                case NOT_FOUND -> throw new ProblemNotFoundException();
                case FAILED_PRECONDITION -> {
                    if (ProblemRpcReasons.isContestNotRunning(e)) {
                        throw new AppException(ErrorCode.CONTEST_NOT_RUNNING);
                    }
                    throw new ProblemNotJudgeableException(slug);
                }
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

    @Override
    public Set<String> visibleSlugs(Collection<String> slugs) {
        ListVisibleSlugsRequest request = ListVisibleSlugsRequest.newBuilder().addAllSlugs(slugs).build();
        try {
            return Set.copyOf(call(() -> withDeadline().listVisibleSlugs(request)).getSlugsList());
        } catch (StatusRuntimeException e) {
            log.warn("problem-service could not list the visible slugs: {}", e.getStatus());
            throw new ProblemCatalogUnavailableException();
        } catch (CallNotPermittedException e) {
            throw new ProblemCatalogUnavailableException();
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
