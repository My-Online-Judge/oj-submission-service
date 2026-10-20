package vn.thanhtuanle.submission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.judge.dto.JudgeResultDto;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.oj.common.identity.PublicUserClient;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/v1/submissions} is the admin search: {@code submission:read_any} only, and never source or details. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubmissionSearchControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired SubmissionRepository submissionRepository;
    @Autowired LanguageRepository languageRepository;

    @MockitoBean PublicUserClient publicUserClient;

    private final String slug = "ctl-" + UUID.randomUUID();
    private Language language;
    private UUID id;

    @BeforeEach
    void seed() {
        language = languageRepository.save(Language.builder()
                .name("C++").identifier("cpp-" + UUID.randomUUID()).build());
        id = submissionRepository.save(Submission.builder()
                .sourceCode("secret source").status(0).time(0).memory(1L)
                .details(List.of(new JudgeResultDto()))
                .problemId(UUID.randomUUID()).problemSlug(slug).language(language).userId(UUID.randomUUID())
                .build()).getId();
        when(publicUserClient.byIds(any())).thenReturn(Map.of());
    }

    @AfterEach
    void cleanUp() {
        submissionRepository.deleteById(id);
        languageRepository.deleteById(language.getId());
    }

    private static RequestPostProcessor userWith(String... authorities) {
        SimpleGrantedAuthority[] granted = new SimpleGrantedAuthority[authorities.length];
        for (int i = 0; i < authorities.length; i++) {
            granted[i] = new SimpleGrantedAuthority(authorities[i]);
        }
        return jwt().jwt(token -> token.claim(OjJwtDecoders.UID_CLAIM, UUID.randomUUID().toString()))
                .authorities(granted);
    }

    @Test
    void listItemsCarryNoSourceOrDetails() throws Exception {
        mockMvc.perform(get("/api/v1/submissions").param("problemSlug", slug)
                        .with(userWith("submission:read_any")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id.toString()))
                .andExpect(jsonPath("$.data[0].language").value("C++"))
                .andExpect(jsonPath("$.pagination.totalElements").value(1))
                .andExpect(jsonPath("$..sourceCode").doesNotExist())
                .andExpect(jsonPath("$..details").doesNotExist());
    }

    @Test
    void withoutReadAnyIs403() throws Exception {
        mockMvc.perform(get("/api/v1/submissions").param("problem", slug).with(userWith("submission:rejudge")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIs401() throws Exception {
        mockMvc.perform(get("/api/v1/submissions")).andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedFilterIs400() throws Exception {
        mockMvc.perform(get("/api/v1/submissions").param("from", "yesterday").with(userWith("submission:read_any")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/submissions").param("result", "x").with(userWith("submission:read_any")))
                .andExpect(status().isBadRequest());
    }
}
