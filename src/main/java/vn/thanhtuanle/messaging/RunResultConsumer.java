package vn.thanhtuanle.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import vn.thanhtuanle.messaging.event.RunJudgedEvent;
import vn.thanhtuanle.run.RunMetrics;
import vn.thanhtuanle.run.RunProperties;
import vn.thanhtuanle.run.RunRegistry;

/**
 * Hands judge-worker's replies on {@code run.judged} to the runs waiting for them (Run code design §7). Every instance
 * reads all replies, in a consumer group of its own ({@code run-results-<instance>}), because the pending runs are in
 * the memory of the instance that took the request: a shared group would hand a reply to the wrong instance and the
 * run would end in 504. A reply that names another instance is dropped silently (not "late": nothing timed out). Its own
 * container factory ({@code RunKafkaConfig}) skips a record it cannot read at once; a reply nobody waits for is
 * counted as late by the registry. Replies are of no use after a restart, so the group starts at the latest offset.
 * The worker sends plain JSON without type headers, so this listener names its own default type.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunResultConsumer {

    private final RunRegistry registry;
    private final RunMetrics metrics;
    private final RunProperties props;

    @KafkaListener(topics = KafkaTopics.RUN_JUDGED, groupId = "#{@runProperties.resultsGroup}",
            containerFactory = "runJudgedContainerFactory",
            properties = {"spring.json.value.default.type=vn.thanhtuanle.messaging.event.RunJudgedEvent",
                    "auto.offset.reset=latest"})
    public void onJudged(RunJudgedEvent event) {
        if (event == null || event.runId() == null) {
            log.warn("Ignoring a run.judged record without a runId: {}", event);
            metrics.unreadableReply();
            return;
        }
        // A reply with no replyTo (a worker that predates it, a run in flight during an upgrade) is tried here as before.
        if (event.replyTo() != null && !event.replyTo().equals(props.getInstanceId())) {
            return;
        }
        registry.complete(event);
    }
}
