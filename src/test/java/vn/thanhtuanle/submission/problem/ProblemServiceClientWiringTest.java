package vn.thanhtuanle.submission.problem;

import io.grpc.ManagedChannel;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** The submission side talks to problem-service over gRPC, and the breaker's state is a Prometheus metric. */
@SpringBootTest
@ActiveProfiles("test")
class ProblemServiceClientWiringTest {

    @Autowired ProblemCatalog problemCatalog;
    @Autowired MeterRegistry meters;
    @Autowired ManagedChannel problemServiceChannel;
    @Autowired ChannelRecovery problemServiceRecovery;

    @Test
    void theCatalogWatchesTheChannelItCalls() {
        assertThat(problemServiceRecovery.channel()).isSameAs(problemServiceChannel);
    }

    @Test
    void problemsComeFromProblemService() {
        assertThat(problemCatalog).isInstanceOf(GrpcProblemCatalog.class);
    }

    @Test
    void theBreakerStateIsExported() {
        // ProblemServiceCircuitOpen alerts on resilience4j_circuitbreaker_state{name="problem-service",state="open"}.
        assertThat(meters.find("resilience4j.circuitbreaker.state")
                .tag("name", ProblemServiceResilience.NAME).tag("state", "open").gauge())
                .isNotNull()
                .satisfies(g -> assertThat(g.value()).isZero());
    }

    @Test
    void profileVisibilityHasABreakerOfItsOwn() {
        // ListVisibleSlugs serves anonymous profile views: its failures never open the breaker that gates submits.
        assertThat(meters.find("resilience4j.circuitbreaker.state")
                .tag("name", ProblemServiceResilience.VISIBILITY_NAME).tag("state", "closed").gauge())
                .isNotNull()
                .satisfies(g -> assertThat(g.value()).isEqualTo(1.0));
    }
}
