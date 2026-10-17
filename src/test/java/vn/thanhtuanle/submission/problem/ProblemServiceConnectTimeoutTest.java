package vn.thanhtuanle.submission.problem;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.test.context.ActiveProfiles;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetJudgeSpecRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A connect that gets no answer gives up after oj.problems.connect-timeout (sub-project 7 §3.1 F2), so a call fails
 * UNAVAILABLE (and is retried) instead of waiting out its deadline. 192.0.2.1 (TEST-NET-1) is never routed: the SYN
 * gets no answer, as for a stopped container's address on the Docker bridge.
 */
@SpringBootTest(properties = "spring.grpc.client.channels.blackhole.address=static://192.0.2.1:9090")
@ActiveProfiles("test")
class ProblemServiceConnectTimeoutTest {

    @Autowired
    GrpcChannelFactory channels;

    @Test
    void aConnectThatGetsNoAnswerFailsAtTheConnectTimeoutNotTheDeadline() {
        var stub = ProblemInternalGrpc.newBlockingStub(channels.createChannel("blackhole"))
                .withDeadlineAfter(2, TimeUnit.SECONDS);
        long start = System.nanoTime();

        assertThatThrownBy(() -> stub.getJudgeSpec(GetJudgeSpecRequest.newBuilder().setProblemSlug("a").build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }
}
