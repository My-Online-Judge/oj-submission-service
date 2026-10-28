package vn.thanhtuanle.run;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import vn.thanhtuanle.messaging.KafkaTopics;

import java.time.Duration;

/** The Run lane's knobs ({@code oj.run.*}; Run code design §5, §6). The defaults are the spec's. */
@Component
@ConfigurationProperties(prefix = "oj.run")
@Getter
@Setter
public class RunProperties {

    /** Most cases one run may carry; the portal offers six. */
    private int maxCases = 6;

    /** Largest input, and largest expected output, of one case, in UTF-8 bytes. */
    private int maxCaseBytes = 65_536;

    /** Largest serialised {@code run.requested} event; Kafka's record cap is 1 MB. */
    private int maxEventBytes = 900_000;

    /** Per-user cooldown between runs, independent of the submit cooldown. */
    private long cooldownSeconds = 3;

    /** Most runs this instance keeps waiting at once; beyond it 503 RUN_BUSY. */
    private int maxInflight = 20;

    /** From publish to the worker's reply; then 504 RUN_TIMED_OUT. */
    private Duration timeout = Duration.ofSeconds(45);

    /**
     * Names this instance on the runs it publishes ({@code replyTo}) and in its {@code run.judged} consumer group. The
     * pending runs live in this instance's memory, so only this instance can complete them: it reads every reply (a
     * group of its own) and acts on the ones that name it. {@code RUN_INSTANCE_ID}, else the container's {@code HOSTNAME}
     * (the pod name under Kubernetes), else "local".
     */
    private String instanceId = defaultInstanceId();

    /** {@code run-results-<instance>}: this instance's consumer group on run.judged, one group per instance. */
    public String getResultsGroup() {
        // A Kafka group id takes letters, digits, '.', '_' and '-'; a hostname or a configured id may hold more.
        return KafkaTopics.RUN_RESULTS_GROUP_PREFIX + instanceId.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String defaultInstanceId() {
        String id = System.getenv("RUN_INSTANCE_ID");
        if (id == null || id.isBlank()) {
            id = System.getenv("HOSTNAME");
        }
        return id == null || id.isBlank() ? "local" : id;
    }
}
