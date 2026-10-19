package vn.thanhtuanle.messaging.outbox;

import org.apache.kafka.common.config.SslConfigs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox owns its producer, so it does not get Boot's Kafka SSL bundle for free (Boot 3.5 applies
 * {@code spring.kafka.ssl.bundle} through the connection details of its own factories, not through
 * {@code KafkaProperties.buildProducerProperties}). Without the bundle the producer speaks SSL with the JDK's default
 * trust store and every send fails with "PKIX path building failed" against the private CA.
 */
class KafkaOutboxSenderTlsTest {

    private static final String FIXTURES = "classpath:tls/";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class, KafkaAutoConfiguration.class))
            .withUserConfiguration(KafkaOutboxSender.class);

    @Test
    void outboxProducerUsesTheKafkaSslBundle() {
        runner.withPropertyValues(
                        "spring.kafka.bootstrap-servers=kafka:29092",
                        "spring.kafka.security.protocol=SSL",
                        "spring.kafka.ssl.bundle=oj",
                        "spring.ssl.bundle.pem.oj.keystore.certificate=" + FIXTURES + "submission-service.crt",
                        "spring.ssl.bundle.pem.oj.keystore.private-key=" + FIXTURES + "submission-service.key",
                        "spring.ssl.bundle.pem.oj.truststore.certificate=" + FIXTURES + "ca.crt")
                .run(context -> {
                    Map<String, Object> config = producerConfig(context.getBean(KafkaOutboxSender.class));
                    assertThat(config).containsEntry("security.protocol", "SSL");
                    assertThat(config.get(SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG)).isNotNull();
                    assertThat(config.get(SslBundle.class.getName())).isInstanceOf(SslBundle.class);
                });
    }

    @Test
    void withoutTlsTheProducerStaysPlain() {
        runner.withPropertyValues("spring.kafka.bootstrap-servers=kafka:29092")
                .run(context -> {
                    Map<String, Object> config = producerConfig(context.getBean(KafkaOutboxSender.class));
                    assertThat(config.get(SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG)).isNull();
                    assertThat(config.get("security.protocol")).isNotEqualTo("SSL");
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> producerConfig(KafkaOutboxSender sender) {
        var factory = (DefaultKafkaProducerFactory<String, byte[]>) ReflectionTestUtils.getField(sender, "producerFactory");
        return factory.getConfigurationProperties();
    }
}
