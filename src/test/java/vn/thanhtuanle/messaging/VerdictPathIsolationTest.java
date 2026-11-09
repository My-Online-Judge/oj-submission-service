package vn.thanhtuanle.messaging;

import org.junit.jupiter.api.Test;
import vn.thanhtuanle.submission.SubmissionDetailAssembler;
import vn.thanhtuanle.submission.SubmissionReconcileJob;
import vn.thanhtuanle.submission.problem.ProblemCatalog;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verdict path never waits for problem-service: the writers of a verdict and the Redis listeners cannot reach it,
 * nor can the start and progress consumers. Only VerdictPush, on its own pool, builds the view with the sample test
 * cases.
 */
class VerdictPathIsolationTest {

    @Test
    void noVerdictWriterNorTheListenerDependsOnTheProblemCatalog() {
        for (Class<?> type : List.of(JudgeResultConsumer.class, SubmissionReconcileJob.class, VerdictPubSub.class,
                SubmissionStartedConsumer.class, SubmissionProgressConsumer.class, JudgingPubSub.class)) {
            assertThat(type.getDeclaredConstructors()).allSatisfy(constructor ->
                    assertThat(constructor.getParameterTypes())
                            .as(type.getSimpleName())
                            .doesNotContain(ProblemCatalog.class, SubmissionDetailAssembler.class));
        }
    }
}
