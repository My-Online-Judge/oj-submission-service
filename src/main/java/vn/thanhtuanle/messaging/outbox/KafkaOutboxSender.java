package vn.thanhtuanle.messaging.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Sends the stored bytes as they are. It owns a producer instead of declaring a {@code KafkaTemplate}
 * bean, because a second template bean would switch off Boot's auto-configured
 * {@code KafkaTemplate<String, Object>} that the dead-letter recoverer uses.
 */
@Component
public class KafkaOutboxSender implements OutboxSender, DisposableBean {

    private final DefaultKafkaProducerFactory<String, byte[]> producerFactory;
    private final KafkaTemplate<String, byte[]> template;

    /**
     * Starts from the configuration of Boot's own producer factory: Boot 3.5 applies {@code spring.kafka.ssl.bundle}
     * (and other connection details) there, not in {@code KafkaProperties.buildProducerProperties}, so building from
     * the properties alone would talk SSL without our CA and fail every send.
     */
    public KafkaOutboxSender(ProducerFactory<?, ?> bootProducerFactory) {
        Map<String, Object> config = new HashMap<>(bootProducerFactory.getConfigurationProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        // While Kafka is down a relay pass gives up within seconds; the rows wait for the next pass.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        this.producerFactory = new DefaultKafkaProducerFactory<>(config);
        this.template = new KafkaTemplate<>(producerFactory);
    }

    @Override
    public void send(OutboxMessage message) throws Exception {
        template.send(message.getTopic(), message.getMessageKey(), message.getPayload()).get(15, TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        producerFactory.destroy();
    }
}
