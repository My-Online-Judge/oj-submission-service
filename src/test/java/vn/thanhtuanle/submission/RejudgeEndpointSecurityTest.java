package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;
import vn.thanhtuanle.submission.dto.RejudgeQueued;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Rejudging, one submission or a problem's, is for holders of {@code submission:rejudge} (ADMIN, SYS_ROOT) only. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RejudgeEndpointSecurityTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean RejudgeService rejudgeService;

    private static RequestPostProcessor userWith(String... authorities) {
        SimpleGrantedAuthority[] granted = new SimpleGrantedAuthority[authorities.length];
        for (int i = 0; i < authorities.length; i++) {
            granted[i] = new SimpleGrantedAuthority(authorities[i]);
        }
        return jwt().jwt(token -> token.claim(OjJwtDecoders.UID_CLAIM, UUID.randomUUID().toString()))
                .authorities(granted);
    }

    @Test
    void anAnonymousRejudgeIs401() throws Exception {
        mockMvc.perform(post("/api/v1/submissions/{id}/rejudge", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(rejudgeService);
    }

    @Test
    void aUserWithoutThePermissionIs403() throws Exception {
        mockMvc.perform(post("/api/v1/submissions/{id}/rejudge", UUID.randomUUID())
                        .with(userWith("submission:read_any")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(rejudgeService);
    }

    @Test
    void aUserWithThePermissionRejudges() throws Exception {
        UUID id = UUID.randomUUID();
        when(rejudgeService.rejudge(id)).thenReturn(SubmissionResponseDto.builder().id(id).status(6).build());

        mockMvc.perform(post("/api/v1/submissions/{id}/rejudge", id).with(userWith("submission:rejudge")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.status").value(6));
    }

    @Test
    void anAnonymousProblemRejudgeIs401() throws Exception {
        mockMvc.perform(post("/api/v1/problems/{slug}/rejudge", "a-plus-b"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(rejudgeService);
    }

    @Test
    void aUserWithoutThePermissionIs403ForAProblemRejudge() throws Exception {
        mockMvc.perform(post("/api/v1/problems/{slug}/rejudge", "a-plus-b").with(userWith("submission:read_any")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(rejudgeService);
    }

    @Test
    void aUserWithThePermissionRejudgesAProblemOptionallyOfOneVerdict() throws Exception {
        when(rejudgeService.rejudgeProblem("a-plus-b", -1)).thenReturn(new RejudgeQueued(3));
        when(rejudgeService.rejudgeProblem("a-plus-b", null)).thenReturn(new RejudgeQueued(7));

        mockMvc.perform(post("/api/v1/problems/{slug}/rejudge", "a-plus-b").param("status", "-1")
                        .with(userWith("submission:rejudge")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queued").value(3));
        mockMvc.perform(post("/api/v1/problems/{slug}/rejudge", "a-plus-b").with(userWith("submission:rejudge")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queued").value(7));
    }
}
