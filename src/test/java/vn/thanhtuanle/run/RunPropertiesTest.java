package vn.thanhtuanle.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunPropertiesTest {

    @Test
    void theResultsGroupIsOneGroupPerInstance() {
        RunProperties props = new RunProperties();
        props.setInstanceId("submission-service-7c9f");

        assertThat(props.getResultsGroup()).isEqualTo("run-results-submission-service-7c9f");
    }

    @Test
    void anInstanceIdKafkaWouldRejectAsAGroupNameIsMadeSafe() {
        RunProperties props = new RunProperties();
        props.setInstanceId("pod/x y:1");

        assertThat(props.getResultsGroup()).isEqualTo("run-results-pod-x-y-1");
    }

    @Test
    void thereIsAnInstanceIdWithoutConfiguration() {
        assertThat(new RunProperties().getInstanceId()).isNotBlank();
    }
}
