package vn.thanhtuanle.messaging.outbox;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "oj.outbox")
@Getter
@Setter
public class OutboxProperties {
    /** Off in the test profile: tests drive {@link OutboxRelay} directly. */
    private boolean relayEnabled = true;
    /** Most rows one relay pass locks and sends. */
    private int batchSize = 100;
    /** How long an instance owns the rows it claimed: a crashed instance's rows are sent by another one after this. */
    private int leaseSeconds = 60;
    /**
     * The longest one send may take (KafkaOutboxSender waits this long for the acknowledgement). A pass starts no send
     * in the last this-many seconds of its lease: it would still be sending when another instance claims the row.
     */
    private int sendTimeoutSeconds = KafkaOutboxSender.SEND_TIMEOUT_SECONDS;
    /** A record the broker refuses for good this many times is dead: never sent again, and the alert fires. */
    private int maxAttempts = 5;
    /** After a refusal for good, the row waits this long times the number of refusals before it is tried again. */
    private int retryBackoffSeconds = 30;
    /** Published rows older than this are deleted by the daily clean-up. */
    private int retentionDays = 7;
}
