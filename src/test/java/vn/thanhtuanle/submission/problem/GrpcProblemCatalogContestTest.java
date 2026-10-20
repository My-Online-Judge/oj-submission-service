package vn.thanhtuanle.submission.problem;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.oj.common.grpc.ProblemRpcReasons;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetJudgeSpecRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.JudgeSpec;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;
import vn.thanhtuanle.oj.common.web.error.AppException;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A contest submit against an in-process problem-service: the contest slug rides on the request, the contest id
 * comes back on the spec, and a contest that is not running is the submitter's 400, told apart from a problem
 * without a bundle by the {@code oj-reason} trailer. A checker problem's spec carries the checker; an empty field means
 * none.
 */
class GrpcProblemCatalogContestTest {

    private static final UUID PROBLEM_ID = UUID.fromString("5b9c3f2e-6c1a-4f61-9d55-2c4f0f1b7a10");
    private static final UUID CONTEST_ID = UUID.fromString("0f4e2a9b-3c7d-4e1f-8a6b-9d2c5e7f1a30");

    private final FakeProblemService problems = new FakeProblemService();
    private Server server;
    private ManagedChannel channel;
    private GrpcProblemCatalog catalog;

    @BeforeEach
    void start() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).addService(problems).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        catalog = new GrpcProblemCatalog(ProblemInternalGrpc.newBlockingStub(channel),
                CircuitBreaker.of("problem-service", ProblemServiceResilience.circuitBreakerConfig()),
                Retry.of("problem-service", ProblemServiceResilience.retryConfig()),
                CircuitBreaker.of("problem-service-visibility", ProblemServiceResilience.circuitBreakerConfig()),
                Retry.of("problem-service-visibility", ProblemServiceResilience.retryConfig()), Duration.ofSeconds(2),
                new ChannelRecovery(channel));
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static JudgeSpec.Builder spec() {
        return JudgeSpec.newBuilder().setProblemId(PROBLEM_ID.toString()).setProblemSlug("a-plus-b")
                .setTimeLimitMs(1000).setMemoryLimitMb(256).setTestCaseVersion("v1");
    }

    @Test
    void contestSlugIsSentAndContestIdParsed() {
        problems.answer = spec().setContestId(CONTEST_ID.toString()).build();

        vn.thanhtuanle.submission.problem.JudgeSpec judged = catalog.judgeSpec("a-plus-b", false, "icpc-1");

        assertThat(problems.lastRequest.getContestSlug()).isEqualTo("icpc-1");
        assertThat(problems.lastRequest.getProblemSlug()).isEqualTo("a-plus-b");
        assertThat(judged).isEqualTo(new vn.thanhtuanle.submission.problem.JudgeSpec(
                PROBLEM_ID, "a-plus-b", 1000, 256L, "v1", CONTEST_ID));
    }

    @Test
    void contestNotRunningBecomes400() {
        problems.failure = Status.FAILED_PRECONDITION.withDescription("contest not running")
                .asRuntimeException(ProblemRpcReasons.contestNotRunning());

        assertThatThrownBy(() -> catalog.judgeSpec("a-plus-b", false, "icpc-1"))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONTEST_NOT_RUNNING));
        assertThat(ErrorCode.CONTEST_NOT_RUNNING.getStatusCode().value()).isEqualTo(400);
        assertThat(problems.calls).as("an answer, not retried").hasValue(1);
    }

    @Test
    void failedPreconditionWithoutReasonIsStillNotJudgeable() {
        problems.failure = Status.FAILED_PRECONDITION.withDescription("no bundle").asRuntimeException();

        assertThatThrownBy(() -> catalog.judgeSpec("a-plus-b", false, "icpc-1"))
                .isInstanceOf(ProblemNotJudgeableException.class);
    }

    @Test
    void aMissingCheckerSourceIs503AndLoggedAsTheCheckerNotTheBundle() {
        problems.failure = Status.FAILED_PRECONDITION
                .withDescription("Checker source missing for problem: a-plus-b").asRuntimeException();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GrpcProblemCatalog.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> catalog.judgeSpec("a-plus-b", false, null))
                    .isInstanceOf(ProblemCatalogUnavailableException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(e -> e.getFormattedMessage())
                .anyMatch(m -> m.contains("checker source missing for a-plus-b"))
                .noneMatch(m -> m.contains("No test-case bundle"));
        assertThat(problems.calls).as("an answer, not retried").hasValue(1);
    }

    @Test
    void emptyContestIdIsNull() {
        problems.answer = spec().build();

        vn.thanhtuanle.submission.problem.JudgeSpec judged = catalog.judgeSpec("a-plus-b", false);

        assertThat(judged.contestId()).isNull();
        assertThat(problems.lastRequest.getContestSlug()).as("practice names no contest").isEmpty();
    }

    @Test
    void spjFieldsAreMapped() {
        problems.answer = spec().setSpjSource("int main(){return 0;}").setSpjVersion("ab12").build();

        vn.thanhtuanle.submission.problem.JudgeSpec checker = catalog.judgeSpec("a-plus-b", false);

        assertThat(checker.spjSource()).isEqualTo("int main(){return 0;}");
        assertThat(checker.spjVersion()).isEqualTo("ab12");

        problems.answer = spec().build();

        vn.thanhtuanle.submission.problem.JudgeSpec plain = catalog.judgeSpec("a-plus-b", false);

        assertThat(plain.spjSource()).as("empty means no checker").isNull();
        assertThat(plain.spjVersion()).isNull();
    }

    /** Answers every GetJudgeSpec with {@link #failure} when set, else with {@link #answer}. */
    private static final class FakeProblemService extends ProblemInternalGrpc.ProblemInternalImplBase {
        final AtomicInteger calls = new AtomicInteger();
        volatile JudgeSpec answer;
        volatile StatusRuntimeException failure;
        volatile GetJudgeSpecRequest lastRequest;

        @Override
        public void getJudgeSpec(GetJudgeSpecRequest request, StreamObserver<JudgeSpec> observer) {
            calls.incrementAndGet();
            lastRequest = request;
            if (failure != null) {
                observer.onError(failure);
            } else {
                observer.onNext(answer);
                observer.onCompleted();
            }
        }
    }
}
