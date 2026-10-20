package vn.thanhtuanle.stats;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;
import vn.thanhtuanle.submission.SubmissionRepository;
import vn.thanhtuanle.submission.problem.ProblemCatalog;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserStatsControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired SubmissionRepository submissions;
    @Autowired LanguageRepository languages;

    @MockitoBean ProblemCatalog catalog;

    private final UUID user = UUID.randomUUID();
    private Language language;
    private UUID id;

    @BeforeEach
    void seed() {
        language = languages.save(Language.builder().name("C++").identifier("cpp-" + UUID.randomUUID()).build());
        id = submissions.save(Submission.builder().sourceCode("secret source").status(0).time(0).memory(1L)
                .problemId(UUID.randomUUID()).problemSlug("hidden-one").language(language).userId(user).build()).getId();
        when(catalog.visibleSlugs(any())).thenReturn(Set.of());
    }

    @AfterEach
    void clean() {
        submissions.deleteById(id);
        languages.deleteById(language.getId());
    }

    @Test
    void anonymousCanRead() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}/stats", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.solvedCount").value(1))
                .andExpect(jsonPath("$.data.submissionCount").value(1))
                .andExpect(jsonPath("$.data.solvedProblems").isEmpty())
                .andExpect(jsonPath("$..sourceCode").doesNotExist());
    }

    @Test
    void theOwnerSeesTheHiddenSlug() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}/stats", user)
                        .with(jwt().jwt(t -> t.claim(OjJwtDecoders.UID_CLAIM, user.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.solvedProblems[0]").value("hidden-one"))
                .andExpect(jsonPath("$.data.recent[0].language").value("C++"))
                .andExpect(jsonPath("$..sourceCode").doesNotExist());
    }

    @Test
    void anotherSignedInUserDoesNot() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}/stats", user)
                        .with(jwt().jwt(t -> t.claim(OjJwtDecoders.UID_CLAIM, UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.solvedProblems").isEmpty());
    }

    @Test
    void aMalformedIdIs400() throws Exception {
        mockMvc.perform(get("/api/v1/users/not-a-uuid/stats"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter: userId"));
    }
}
