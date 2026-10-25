package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.submission.problem.ProblemNotJudgeableException;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A problem that exists but has no published test-case bundle (a draft) is a client-visible condition, not a server
 * fault: 409 with its own code, as the portal sees it through the shared error handling.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProblemNotJudgeableResponseTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean ProblemCatalog problemCatalog;

    @Test
    void aSubmitForAProblemWithoutTestDataIs409() throws Exception {
        when(problemCatalog.judgeSpec(eq("draft"), anyBoolean(), isNull(), any()))
                .thenThrow(new ProblemNotJudgeableException("draft"));

        mockMvc.perform(post("/api/v1/submissions")
                        .with(jwt().jwt(t -> t.claim(OjJwtDecoders.UID_CLAIM, UUID.randomUUID().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceCode": "int main(){}", "languageIdentifier": "cpp", "problemSlug": "draft"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROBLEM_NOT_JUDGEABLE"))
                .andExpect(jsonPath("$.message").value("This problem has no test data yet"));
    }
}
