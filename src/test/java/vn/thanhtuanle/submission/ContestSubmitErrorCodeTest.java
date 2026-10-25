package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;
import vn.thanhtuanle.oj.common.web.error.AppException;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.submission.problem.ProblemCatalogUnavailableException;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the portal's submit box reads from a refusal: status, message, the machine-readable {@code code} that
 * oj-common 0.8.1 adds to the body, and {@code Retry-After} where the refusal has one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContestSubmitErrorCodeTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean SubmissionAdmission admission;
    @MockitoBean ProblemCatalog problemCatalog;

    private ResultActions submit(String contestSlug) throws Exception {
        String contest = contestSlug == null ? "" : ", \"contestSlug\": \"" + contestSlug + "\"";
        return mockMvc.perform(post("/api/v1/submissions")
                .with(jwt().jwt(token -> token.claim(OjJwtDecoders.UID_CLAIM, UUID.randomUUID().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceCode\": \"int main(){}\", \"languageIdentifier\": \"cpp\", "
                        + "\"problemSlug\": \"a-plus-b\"" + contest + "}"));
    }

    @Test
    void aContestSubmitOutsideTheWindowIs400WithItsCode() throws Exception {
        when(problemCatalog.judgeSpec(anyString(), anyBoolean(), any(), any()))
                .thenThrow(new AppException(ErrorCode.CONTEST_NOT_RUNNING));

        submit("ended")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONTEST_NOT_RUNNING"))
                .andExpect(jsonPath("$.message").value("Contest is not running"));
    }

    @Test
    void aFullQueueIs503WithCodeAndRetryAfter() throws Exception {
        doThrow(new RateLimitedException(ErrorCode.QUEUE_FULL, SubmissionAdmission.RETRY_AFTER_SECONDS))
                .when(admission).admit(1);

        submit(null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("QUEUE_FULL"))
                .andExpect(jsonPath("$.message").value("The judge is busy, try again in a moment"));
    }

    @Test
    void unavailableProblemsAre503WithCodeAndRetryAfter() throws Exception {
        when(problemCatalog.judgeSpec(anyString(), anyBoolean(), any(), any()))
                .thenThrow(new ProblemCatalogUnavailableException());

        submit(null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("PROBLEMS_UNAVAILABLE"));
    }

    // ---- registration (contest enrollment design §4.5) -----------------------------------------------------------

    @Test
    void aContestSubmitWithoutRegistrationIs403WithItsCode() throws Exception {
        when(problemCatalog.judgeSpec(anyString(), anyBoolean(), any(), any()))
                .thenThrow(new AppException(ErrorCode.CONTEST_NOT_ENROLLED));

        submit("icpc-1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONTEST_NOT_ENROLLED"))
                .andExpect(jsonPath("$.message").value("Register for this contest to submit"));
    }

    @Test
    void aContestRunWithoutRegistrationIs403() throws Exception {
        when(problemCatalog.judgeSpec(anyString(), anyBoolean(), any(), any()))
                .thenThrow(new AppException(ErrorCode.CONTEST_NOT_ENROLLED));

        mockMvc.perform(post("/api/v1/submissions/run")
                        .with(jwt().jwt(token -> token.claim(OjJwtDecoders.UID_CLAIM, UUID.randomUUID().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\": \"int main(){}\", \"languageIdentifier\": \"cpp\", "
                                + "\"problemSlug\": \"a-plus-b\", \"contestSlug\": \"icpc-1\", "
                                + "\"cases\": [{\"input\": \"1 2\\n\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONTEST_NOT_ENROLLED"));
    }
}
