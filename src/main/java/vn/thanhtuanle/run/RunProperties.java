package vn.thanhtuanle.run;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
}
