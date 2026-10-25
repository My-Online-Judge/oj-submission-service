package vn.thanhtuanle.run;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.support.PostgresTest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kafka is away when the user presses Run (plan Review Focus 4): the request must fail within seconds with a 500 and
 * free its slot — not wait the full 45 s for a reply that cannot come, nor park a Tomcat thread for Kafka's default
 * 60 s metadata wait. Against a port nobody listens on, so the producer's own timeouts decide. No Redis: the cooldown
 * fails open, so the second request proves the slot (not the cooldown) was freed.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.kafka.bootstrap-servers=localhost:1", "spring.kafka.admin.auto-create=false"})
class RunKafkaDownTest extends PostgresTest {   // Postgres: Flyway seeds the languages (cpp); H2's test profile has none

    private static final String BODY = """
            {"sourceCode": "int main(){}", "languageIdentifier": "cpp", "problemSlug": "a-plus-b",
             "cases": [{"input": "1 2\\n", "expectedOutput": "3\\n"}]}
            """;

    @Autowired MockMvc mockMvc;
    @Autowired RunRegistry registry;

    @MockitoBean ProblemCatalog problemCatalog;

    @Test
    void aRunWhileKafkaIsDownIs500WithinSecondsAndFreesItsSlot() throws Exception {
        when(problemCatalog.judgeSpec(eq("a-plus-b"), anyBoolean(), isNull(), any()))
                .thenReturn(new JudgeSpec(UUID.randomUUID(), "a-plus-b", 1000, 256L, "v1"));
        UUID user = UUID.randomUUID();

        Instant before = Instant.now();
        mockMvc.perform(post("/api/v1/submissions/run")
                        .with(jwt().jwt(t -> t.claim(OjJwtDecoders.UID_CLAIM, user.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(request().asyncNotStarted())
                .andExpect(status().isInternalServerError());

        assertThat(Duration.between(before, Instant.now())).isLessThan(Duration.ofSeconds(15));
        assertThat(registry.inflight()).isZero();
    }
}
