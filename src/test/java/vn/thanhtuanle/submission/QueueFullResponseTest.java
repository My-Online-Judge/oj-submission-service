package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The refusal as the portal sees it: through the shared error handling, a 503 that says when to come back. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueueFullResponseTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean SubmissionAdmission admission;

    @Test
    void aRefusedSubmitIs503WithRetryAfter() throws Exception {
        doThrow(new RateLimitedException(ErrorCode.QUEUE_FULL, SubmissionAdmission.RETRY_AFTER_SECONDS))
                .when(admission).admit(1);

        mockMvc.perform(post("/api/v1/submissions")
                        .with(jwt().jwt(token -> token.claim(OjJwtDecoders.UID_CLAIM, UUID.randomUUID().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceCode": "int main(){}", "languageIdentifier": "cpp", "problemSlug": "a-plus-b"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.message").value("The judge is busy, try again in a moment"));
    }
}
