package vn.thanhtuanle.submission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.language.LanguageRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A user's submissions to one problem (the portal's per-problem history) are answered from the submissions alone: no
 * problem row is needed.
 */
@SpringBootTest
@ActiveProfiles("test")
class SubmissionsByUserAndProblemTest {

    @Autowired SubmissionRepository submissionRepository;
    @Autowired LanguageRepository languageRepository;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> created = new ArrayList<>();
    private Language language;

    @AfterEach
    void cleanUp() {
        created.forEach(submissionRepository::deleteById);
        if (language != null) {
            languageRepository.deleteById(language.getId());
        }
    }

    private UUID submit(UUID userId, String slug, LocalDateTime at) {
        UUID id = submissionRepository.save(Submission.builder()
                .sourceCode("print(1)").status(SubmissionResult.ACCEPTED.getValue()).time(0).memory(0L)
                .problemId(UUID.randomUUID()).problemSlug(slug).language(language).userId(userId).build()).getId();
        jdbc.update("update t_submissions set created_at = ? where id = ?", at, id);
        created.add(id);
        return id;
    }

    @Test
    void onlyTheUsersSubmissionsToThatProblemNewestFirst() {
        language = languageRepository.save(Language.builder()
                .name("slug-lang").identifier("slug-" + UUID.randomUUID()).build());
        String slug = "slug-only-" + UUID.randomUUID();
        UUID user = UUID.randomUUID();
        LocalDateTime t = LocalDateTime.of(2026, 10, 1, 10, 0);
        UUID older = submit(user, slug, t);
        UUID newer = submit(user, slug, t.plusMinutes(5));
        submit(UUID.randomUUID(), slug, t.plusMinutes(1));
        submit(user, "another-problem", t.plusMinutes(2));

        assertThat(submissionRepository.findByUserIdAndProblemSlugOrderByCreatedAtDesc(user, slug, PageRequest.of(0, 10)))
                .extracting(Submission::getId).containsExactly(newer, older);
    }
}
