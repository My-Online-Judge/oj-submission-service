package vn.thanhtuanle.submission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.oj.common.web.payload.PageResponse;
import vn.thanhtuanle.submission.dto.SubmissionListItemDto;
import vn.thanhtuanle.support.PostgresTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin search on the real schema, where {@code user_id} is nullable (rows came over from the monolith at cutover),
 * with oj-common's real identity client, whose cache lookup throws on a null id.
 */
class SubmissionSearchPostgresTest extends PostgresTest {

    @Autowired SubmissionService service;
    @Autowired SubmissionRepository submissions;
    @Autowired LanguageRepository languages;
    @Autowired JdbcTemplate jdbc;

    private final String slug = "no-user-" + UUID.randomUUID();
    private Language language;
    private UUID id;

    @BeforeEach
    void seed() {
        language = languages.save(Language.builder().name("C++").identifier("cpp-" + UUID.randomUUID()).build());
        id = submissions.save(Submission.builder().sourceCode("int main(){}").status(0).time(0).memory(1L)
                .problemId(UUID.randomUUID()).problemSlug(slug).language(language).userId(UUID.randomUUID())
                .build()).getId();
        jdbc.update("UPDATE t_submissions SET user_id = NULL WHERE id = ?", id);
    }

    @AfterEach
    void clean() {
        submissions.deleteById(id);
        languages.deleteById(language.getId());
    }

    @Test
    void aRowWithoutAUserIsListedWithoutAUsername() {
        PageResponse<SubmissionListItemDto> page =
                service.search(new SubmissionSearch(slug, null, null, null, null, 0, 20));

        assertThat(page.getData()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(id);
            assertThat(row.userId()).isNull();
            assertThat(row.username()).isNull();
            assertThat(row.language()).isEqualTo("C++");
        });
    }
}
