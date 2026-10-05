package vn.thanhtuanle.messaging;

import org.junit.jupiter.api.Test;
import vn.thanhtuanle.submission.SubmissionDetailAssembler;
import vn.thanhtuanle.submission.SubmissionReconcileJob;
import vn.thanhtuanle.submission.problem.ProblemCatalog;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verdict path never waits for problem-service: the writers of a verdict and the Redis listener cannot reach it.
 * Only VerdictPush, on its own pool, builds the view with the sample test cases.
 */
class VerdictPathIsolationTest {

    @Test
    void noVerdictWriterNorTheListenerDependsOnTheProblemCatalog() {
        for (Class<?> type : List.of(JudgeResultConsumer.class, SubmissionReconcileJob.class, VerdictPubSub.class)) {
            assertThat(type.getDeclaredConstructors()).allSatisfy(constructor ->
                    assertThat(constructor.getParameterTypes())
                            .as(type.getSimpleName())
                            .doesNotContain(ProblemCatalog.class, SubmissionDetailAssembler.class));
        }
    }
}
