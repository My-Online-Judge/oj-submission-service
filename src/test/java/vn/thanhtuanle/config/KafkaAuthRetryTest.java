package vn.thanhtuanle.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A listener that starts before its ACLs exist (on K3s the {@code kafka-init} Job can finish a minute after this pod
 * starts) gets TOPIC_AUTHORIZATION_FAILED. Without an auth-exception retry interval Spring Kafka stops that container
 * for good, and Run answers 504 until the pod is restarted. Every container factory must keep retrying instead.
 */
class KafkaAuthRetryTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(RunKafkaConfig.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void everyListenerContainerFactoryRetriesAfterAnAuthorizationFailure() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var factories = context.getBeansOfType(ConcurrentKafkaListenerContainerFactory.class);
            assertThat(factories).containsKeys("kafkaListenerContainerFactory", "runJudgedContainerFactory");
            factories.forEach((name, factory) -> assertThat(factory.getContainerProperties().getAuthExceptionRetryInterval())
                    .as(name).isNotNull().isPositive().isLessThanOrEqualTo(Duration.ofSeconds(30)));
        });
    }
}
