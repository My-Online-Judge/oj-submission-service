package vn.thanhtuanle.judge;

import org.junit.jupiter.api.Test;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.messaging.event.SubmissionRequestedEvent;
import vn.thanhtuanle.submission.problem.JudgeSpec;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A problem without a checker sends none of qduoj's spj fields, so its request is byte-for-byte what it was. */
class JudgeServiceSpjTest {

    @Test
    void plainProblemHasNoSpjFields() {
        JudgeSpec spec = new JudgeSpec(UUID.randomUUID(), "simple-a-plus-b", 1000, 64L, "abc123def456");
        Language c = new Language();
        c.setIdentifier("c");
        c.setCompileCommand("/usr/bin/gcc {src_path} -o {exe_path}");

        SubmissionRequestedEvent event = new JudgeService().buildRequestedEvent("sub-1", "int main(){}", spec, c, 0,
                null);

        assertThat(event.getSpjVersion()).isNull();
        assertThat(event.getSpjSrc()).isNull();
        assertThat(event.getSpjConfig()).isNull();
        assertThat(event.getSpjCompileConfig()).isNull();
    }
}
