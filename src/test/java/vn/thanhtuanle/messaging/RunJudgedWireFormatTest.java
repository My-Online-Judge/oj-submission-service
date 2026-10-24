package vn.thanhtuanle.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.test.context.ActiveProfiles;
import vn.thanhtuanle.messaging.event.RunJudgedEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * judge-worker answers on {@code run.judged} with its verdict envelope — the one of {@code submission.judged}, named by
 * {@code runId} — as plain JSON with Python's names (Run code design §7). Fields this service does not know must not
 * break the reply.
 */
@JsonTest
@ActiveProfiles("test")
class RunJudgedWireFormatTest {

    @Autowired ObjectMapper objectMapper;

    @Test
    void theWorkersReplyDeserialisesWithUnknownFieldsAndSnakeCaseNames() throws Exception {
        String json = "{\"runId\": \"r1\", \"status\": -1, \"result\": -1, \"cpu_time\": 3, \"real_time\": 4, "
                + "\"memory\": 1024, \"error_message\": null, \"details\": [{\"result\": -1, \"cpu_time\": 3, "
                + "\"memory\": 1024, \"output\": \"2\\n\", \"test_case\": \"1\", \"error\": 0}], \"rejudgeCount\": null, "
                + "\"somethingNewer\": true}";

        RunJudgedEvent event = objectMapper.readValue(json, RunJudgedEvent.class);

        assertThat(event.runId()).isEqualTo("r1");
        assertThat(event.status()).isEqualTo(-1);
        assertThat(event.cpuTime()).isEqualTo(3);
        assertThat(event.realTime()).isEqualTo(4);
        assertThat(event.memory()).isEqualTo(1024L);
        assertThat(event.errorMessage()).isNull();
        assertThat(event.details()).singleElement().satisfies(d -> {
            assertThat(d.getTestCase()).isEqualTo("1");
            assertThat(d.getOutput()).isEqualTo("2\n");
            assertThat(d.getResult()).isEqualTo(-1);
        });
    }

    @Test
    void aCompileErrorReplyHasNoDetails() throws Exception {
        String json = "{\"runId\": \"r2\", \"status\": -2, \"result\": null, \"cpu_time\": 0, \"real_time\": 0, "
                + "\"memory\": 0, \"error_message\": \"CompileError: x\", \"details\": []}";

        RunJudgedEvent event = objectMapper.readValue(json, RunJudgedEvent.class);

        assertThat(event.status()).isEqualTo(-2);
        assertThat(event.errorMessage()).isEqualTo("CompileError: x");
        assertThat(event.details()).isEmpty();
    }
}
