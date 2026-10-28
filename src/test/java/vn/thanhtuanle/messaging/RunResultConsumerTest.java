package vn.thanhtuanle.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.thanhtuanle.messaging.event.RunJudgedEvent;
import vn.thanhtuanle.run.RunMetrics;
import vn.thanhtuanle.run.RunProperties;
import vn.thanhtuanle.run.RunRegistry;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Every submission-service instance reads all of {@code run.judged} in a consumer group of its own, so each reply reaches
 * every instance; only the one that asked (the request's {@code replyTo}, echoed by the worker) completes the run. The
 * others drop it without counting it late: a late reply means a run that timed out, not a reply for a neighbour.
 */
class RunResultConsumerTest {

    private final RunRegistry registry = mock(RunRegistry.class);
    private final RunMetrics metrics = mock(RunMetrics.class);
    private RunResultConsumer consumer;

    @BeforeEach
    void setUp() {
        RunProperties props = new RunProperties();
        props.setInstanceId("inst-a");
        consumer = new RunResultConsumer(registry, metrics, props);
    }

    private static RunJudgedEvent reply(String runId, String replyTo) {
        return new RunJudgedEvent(runId, 0, 0, 1, 1, 1L, null, List.of(), replyTo);
    }

    @Test
    void aReplyForThisInstanceCompletesItsRun() {
        RunJudgedEvent event = reply("r1", "inst-a");

        consumer.onJudged(event);

        verify(registry).complete(event);
    }

    @Test
    void aReplyForAnotherInstanceIsDroppedWithoutCountingItLate() {
        consumer.onJudged(reply("r1", "inst-b"));

        verifyNoInteractions(registry);
        verify(metrics, never()).lateReply();
        verify(metrics, never()).unreadableReply();
    }

    @Test
    void aReplyThatNamesNoInstanceStillCompletesItsRun() {
        // A worker that predates replyTo, or a run in flight during a rolling upgrade.
        RunJudgedEvent event = reply("r1", null);

        consumer.onJudged(event);

        verify(registry).complete(event);
    }

    @Test
    void aRecordWithoutARunIdIsUnreadable() {
        consumer.onJudged(reply(null, "inst-a"));

        verify(metrics).unreadableReply();
        verifyNoInteractions(registry);
    }
}
