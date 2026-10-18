package vn.thanhtuanle.submission.problem;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.thanhtuanle.oj.common.grpc.ServiceTokenClientInterceptor;
import vn.thanhtuanle.oj.common.grpc.ServiceTokenServerInterceptor;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetJudgeSpecRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetSampleTestCasesRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.JudgeSpec;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;
import vn.thanhtuanle.oj.common.grpc.problem.v1.SampleTestCase;
import vn.thanhtuanle.oj.common.grpc.problem.v1.SampleTestCases;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gRPC adapter against an in-process problem-service, with the production retry and circuit-breaker
 * policy: what each status becomes for the submission side, what is retried, and what opens the breaker.
 */
class GrpcProblemCatalogTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final UUID PROBLEM_ID = UUID.fromString("5b9c3f2e-6c1a-4f61-9d55-2c4f0f1b7a10");

    private final FakeProblemService problems = new FakeProblemService();
    private Server server;
    private ManagedChannel channel;
    private CircuitBreaker breaker;

    @BeforeEach
    void start() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .addService(ServerInterceptors.intercept(problems, new ServiceTokenServerInterceptor(TOKEN)))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        breaker = CircuitBreaker.of("problem-service", ProblemServiceResilience.circuitBreakerConfig());
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private GrpcProblemCatalog catalog(Duration deadline) {
        return new GrpcProblemCatalog(
                ProblemInternalGrpc.newBlockingStub(channel).withInterceptors(new ServiceTokenClientInterceptor(TOKEN)),
                breaker, Retry.of("problem-service", ProblemServiceResilience.retryConfig()), deadline,
                new ChannelRecovery(channel));
    }

    @Test
    void aRefusedCallCountsAsAFailureAndNeverReachesTheServer() {
        GrpcProblemCatalog catalog = new GrpcProblemCatalog(
                ProblemInternalGrpc.newBlockingStub(channel).withInterceptors(new ServiceTokenClientInterceptor(TOKEN)),
                breaker, Retry.of("problem-service", ProblemServiceResilience.retryConfig()), Duration.ofSeconds(2),
                new ChannelRecovery(new StateDrivenChannel(ConnectivityState.TRANSIENT_FAILURE)));

        assertThatThrownBy(() -> catalog.judgeSpec("a-plus-b")).isInstanceOf(ProblemCatalogUnavailableException.class);
        assertThat(problems.calls).hasValue(0);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isGreaterThanOrEqualTo(1);
    }

    private GrpcProblemCatalog catalog() {
        return catalog(Duration.ofSeconds(2));
    }

    @Test
    void theJudgeSpecIsMappedFieldByField() {
        problems.answer(JudgeSpec.newBuilder().setProblemId(PROBLEM_ID.toString()).setProblemSlug("a-plus-b")
                .setTimeLimitMs(1000).setMemoryLimitMb(256).setTestCaseVersion("f7837fd99ca5").build());

        assertThat(catalog().judgeSpec("a-plus-b"))
                .isEqualTo(new vn.thanhtuanle.submission.problem.JudgeSpec(PROBLEM_ID, "a-plus-b", 1000, 256L, "f7837fd99ca5"));
        assertThat(problems.lastSlug).isEqualTo("a-plus-b");
    }

    @Test
    void notFoundIsTheUsual404AndNeverOpensTheBreaker() {
        GrpcProblemCatalog catalog = catalog();
        for (int i = 0; i < 25; i++) {
            problems.fail(Status.NOT_FOUND);
            assertThatThrownBy(() -> catalog.judgeSpec("no-such")).isInstanceOf(ProblemNotFoundException.class);
        }
        assertThat(problems.calls.get()).isEqualTo(25);   // not retried
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void noPublishedBundleIsNotJudgeableAndNeverOpensTheBreaker() {
        GrpcProblemCatalog catalog = catalog();
        for (int i = 0; i < 25; i++) {
            problems.fail(Status.FAILED_PRECONDITION);
            assertThatThrownBy(() -> catalog.judgeSpec("a-plus-b")).isInstanceOf(ProblemNotJudgeableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void unavailableIsRetriedTwiceThenIs503() {
        problems.fail(Status.UNAVAILABLE, Status.UNAVAILABLE, Status.UNAVAILABLE);

        assertThatThrownBy(() -> catalog().judgeSpec("a-plus-b")).isInstanceOf(ProblemCatalogUnavailableException.class);
        assertThat(problems.calls.get()).isEqualTo(3);
    }

    @Test
    void aBriefOutageIsRetriedAway() {
        problems.fail(Status.UNAVAILABLE);
        problems.answer(JudgeSpec.newBuilder().setProblemId(PROBLEM_ID.toString()).setProblemSlug("a-plus-b")
                .setTestCaseVersion("v").build());

        assertThat(catalog().judgeSpec("a-plus-b").testCaseVersion()).isEqualTo("v");
        assertThat(problems.calls.get()).isEqualTo(2);
    }

    @Test
    void aSlowAnswerHitsTheDeadlineAndIs503WithoutARetry() {
        problems.delayMillis = 1_000;
        problems.answer(JudgeSpec.newBuilder().setProblemId(PROBLEM_ID.toString()).build());

        assertThatThrownBy(() -> catalog(Duration.ofMillis(100)).judgeSpec("a-plus-b"))
                .isInstanceOf(ProblemCatalogUnavailableException.class);
        assertThat(problems.calls.get()).isEqualTo(1);
    }

    @Test
    void anOpenBreakerAnswers503WithoutCallingTheService() {
        GrpcProblemCatalog catalog = catalog();
        for (int i = 0; i < 4; i++) {   // 4 calls x 3 attempts = 12 recorded failures >= the minimum of 10
            problems.fail(Status.UNAVAILABLE, Status.UNAVAILABLE, Status.UNAVAILABLE);
            assertThatThrownBy(() -> catalog.judgeSpec("a-plus-b")).isInstanceOf(ProblemCatalogUnavailableException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsBefore = problems.calls.get();

        assertThatThrownBy(() -> catalog.judgeSpec("a-plus-b")).isInstanceOf(ProblemCatalogUnavailableException.class);
        assertThat(catalog.sampleCases(PROBLEM_ID)).isEmpty();
        assertThat(problems.calls.get()).isEqualTo(callsBefore);
    }

    @Test
    void aRecoveredServiceClosesTheBreakerAfterThreeProbes() {
        // Little traffic after an outage: the breaker must not linger HALF_OPEN (and keep ProblemServiceCircuitOpen
        // firing) waiting for many probe calls.
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        GrpcProblemCatalog catalog = catalog();
        for (int i = 0; i < 3; i++) {
            problems.answer(JudgeSpec.newBuilder().setProblemId(PROBLEM_ID.toString()).setTestCaseVersion("v").build());
            catalog.judgeSpec("a-plus-b");
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void sampleCasesAreKeyedByName() {
        problems.answer(SampleTestCases.newBuilder()
                .addCases(SampleTestCase.newBuilder().setName("1").setInput("1 2\n").setExpectedOutput("3\n"))
                .build());

        assertThat(catalog().sampleCases(PROBLEM_ID))
                .containsOnlyKeys("1")
                .containsEntry("1", new vn.thanhtuanle.submission.problem.SampleTestCase("1", "1 2\n", "3\n"));
        assertThat(problems.lastProblemId).isEqualTo(PROBLEM_ID.toString());
    }

    @Test
    void sampleCasesFailClosedToAnEmptyMap() {
        problems.fail(Status.INTERNAL);

        assertThat(catalog().sampleCases(PROBLEM_ID)).isEmpty();
    }

    @Test
    void aWrongServiceTokenIs503() {
        GrpcProblemCatalog wrongToken = new GrpcProblemCatalog(
                ProblemInternalGrpc.newBlockingStub(channel)
                        .withInterceptors(new ServiceTokenClientInterceptor("ffffffffffffffffffffffffffffffff")),
                breaker, Retry.of("problem-service", ProblemServiceResilience.retryConfig()), Duration.ofSeconds(2),
                new ChannelRecovery(channel));

        assertThatThrownBy(() -> wrongToken.judgeSpec("a-plus-b")).isInstanceOf(ProblemCatalogUnavailableException.class);
    }

    /** Answers from a queue: a Status fails the call, a message answers it. */
    private static final class FakeProblemService extends ProblemInternalGrpc.ProblemInternalImplBase {
        final Deque<Object> script = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile long delayMillis;
        volatile String lastSlug;
        volatile String lastProblemId;

        void fail(Status... statuses) {
            script.addAll(java.util.List.of(statuses));
        }

        void answer(Object message) {
            script.add(message);
        }

        @Override
        public void getJudgeSpec(GetJudgeSpecRequest request, StreamObserver<JudgeSpec> observer) {
            lastSlug = request.getProblemSlug();
            reply(observer);
        }

        @Override
        public void getSampleTestCases(GetSampleTestCasesRequest request, StreamObserver<SampleTestCases> observer) {
            lastProblemId = request.getProblemId();
            reply(observer);
        }

        @SuppressWarnings("unchecked")
        private <T> void reply(StreamObserver<T> observer) {
            calls.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Object next = script.poll();
            if (next instanceof Status status) {
                observer.onError(status.asRuntimeException());
            } else if (next != null) {
                observer.onNext((T) next);
                observer.onCompleted();
            } else {
                observer.onError(Status.INTERNAL.withDescription("nothing scripted").asRuntimeException());
            }
        }
    }
}
