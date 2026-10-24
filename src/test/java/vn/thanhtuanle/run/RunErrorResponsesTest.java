package vn.thanhtuanle.run;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The refusals of Run code design §5 as the portal sees them, through the shared error handling. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunErrorResponsesTest {

    private static final String BODY = """
            {"sourceCode": "int main(){}", "languageIdentifier": "cpp", "problemSlug": "a-plus-b",
             "cases": [{"input": "1 2\\n", "expectedOutput": "3\\n"}]}
            """;

    @Autowired MockMvc mockMvc;

    @MockitoBean RunService runService;

    private static MockHttpServletRequestBuilder run() {
        return post("/api/v1/submissions/run").contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    private static MockHttpServletRequestBuilder runAs(UUID userId) {
        return run().with(jwt().jwt(t -> t.claim(OjJwtDecoders.UID_CLAIM, userId.toString())));
    }

    @Test
    void aCooldownRefusalIs429WithRetryAfter() throws Exception {
        when(runService.run(any())).thenThrow(new RateLimitedException(ErrorCode.RUN_RATE_LIMITED, 2));

        mockMvc.perform(runAs(UUID.randomUUID()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value("RUN_RATE_LIMITED"));
    }

    @Test
    void aBusyLaneIs503WithRetryAfter10() throws Exception {
        when(runService.run(any())).thenThrow(new RateLimitedException(ErrorCode.RUN_BUSY, 10));

        mockMvc.perform(runAs(UUID.randomUUID()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "10"))
                .andExpect(jsonPath("$.code").value("RUN_BUSY"));
    }

    @Test
    void aRunInProgressIs429() throws Exception {
        when(runService.run(any())).thenThrow(new RateLimitedException(ErrorCode.RUN_IN_PROGRESS, 1));

        mockMvc.perform(runAs(UUID.randomUUID()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("RUN_IN_PROGRESS"));
    }

    @Test
    void anAnonymousRunIs401() throws Exception {
        mockMvc.perform(run()).andExpect(status().isUnauthorized());

        verify(runService, never()).run(any());
    }
}
