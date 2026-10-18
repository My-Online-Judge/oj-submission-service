package vn.thanhtuanle.submission.problem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.autoconfigure.client.GrpcClientProperties;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** How the channel to problem-service balances (sub-project 8 G1). */
@SpringBootTest
@ActiveProfiles("test")
class ProblemServiceChannelPolicyTest {

    @Autowired
    GrpcClientProperties properties;

    @Test
    void theProblemServiceChannelPicksFirst() {
        assertThat(properties.getChannels().get("problem-service").getDefaultLoadBalancingPolicy())
                .isEqualTo("pick_first");
    }
}
