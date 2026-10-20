package vn.thanhtuanle.submission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.oj.common.identity.IdentityUnavailableException;
import vn.thanhtuanle.oj.common.identity.PublicUser;
import vn.thanhtuanle.oj.common.identity.PublicUserClient;
import vn.thanhtuanle.oj.common.web.error.AppException;
import vn.thanhtuanle.oj.common.web.payload.PageResponse;
import vn.thanhtuanle.submission.dto.SubmissionListItemDto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** The admin search: 2 users x 2 problems x statuses {0, -1} x dates {2026-10-01, 2026-10-05}. */
@SpringBootTest
@ActiveProfiles("test")
class SubmissionSearchTest {

    @Autowired SubmissionRepository submissionRepository;
    @Autowired LanguageRepository languageRepository;
    @Autowired SubmissionService service;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean PublicUserClient publicUserClient;

    private final String p1 = "p1-" + UUID.randomUUID();
    private final String p2 = "p2-" + UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final List<UUID> created = new ArrayList<>();
    private Language language;

    @BeforeEach
    void seed() {
        language = languageRepository.save(Language.builder()
                .name("Python 3").identifier("py-" + UUID.randomUUID()).build());
        for (UUID user : List.of(alice, bob)) {
            for (String slug : List.of(p1, p2)) {
                for (int status : new int[] {0, -1}) {
                    for (int day : new int[] {1, 5}) {
                        add(user, slug, status, LocalDateTime.of(2026, 10, day, user.equals(alice) ? 10 : 11, 0));
                    }
                }
            }
        }
    }

    private Submission add(UUID user, String slug, int status, LocalDateTime at) {
        Submission s = submissionRepository.save(Submission.builder()
                .sourceCode("print(1)").status(status).time(0).memory(1L).cpuTime(2)
                .problemId(UUID.randomUUID()).problemSlug(slug).language(language).userId(user).build());
        jdbc.update("update t_submissions set created_at = ? where id = ?", at, s.getId());
        created.add(s.getId());
        return s;
    }

    @AfterEach
    void cleanUp() {
        created.forEach(submissionRepository::deleteById);
        languageRepository.deleteById(language.getId());
    }

    private static SubmissionSearch search(String slug, String username, Integer result, LocalDate from, LocalDate to,
                                           int size) {
        return new SubmissionSearch(slug, username, result, from, to, 0, size);
    }

    private static PublicUser profile(UUID id, String username) {
        return new PublicUser(id, username, username, null, null);
    }

    @Test
    void filtersCombine() {
        when(publicUserClient.byIds(any())).thenReturn(Map.of(alice, profile(alice, "alice"), bob, profile(bob, "bob")));

        PageResponse<SubmissionListItemDto> page = service.search(
                search(p1, null, 0, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5), 20));

        List<UUID> expected = jdbc.queryForList(
                "select id from t_submissions where problem_slug = ? and status = 0 "
                        + "and created_at >= '2026-10-02 00:00:00' and created_at < '2026-10-06 00:00:00' "
                        + "order by created_at desc", UUID.class, p1);
        assertThat(expected).hasSize(2);
        assertThat(page.getData()).extracting(SubmissionListItemDto::id).containsExactlyElementsOf(expected);
        assertThat(page.getData()).extracting(SubmissionListItemDto::username).containsExactly("bob", "alice");
        assertThat(page.getData()).extracting(SubmissionListItemDto::language).containsOnly("Python 3");
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void usernameResolvesThroughIdentity() {
        when(publicUserClient.byUsername("alice")).thenReturn(Optional.of(profile(alice, "alice")));
        when(publicUserClient.byIds(any())).thenReturn(Map.of(alice, profile(alice, "alice")));

        PageResponse<SubmissionListItemDto> page = service.search(search(p2, "alice", null, null, null, 20));

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getData()).extracting(SubmissionListItemDto::userId).containsOnly(alice);
    }

    @Test
    void unknownUsernameGivesAnEmptyPage() {
        when(publicUserClient.byUsername("ghost")).thenReturn(Optional.empty());

        PageResponse<SubmissionListItemDto> page = service.search(search(p1, "ghost", null, null, null, 20));

        assertThat(page.getData()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void identityDownForAUsernameFilterIs503() {
        when(publicUserClient.byUsername("alice")).thenThrow(new IdentityUnavailableException("down", null));

        assertThatThrownBy(() -> service.search(search(p1, "alice", null, null, null, 20)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDENTITY_UNAVAILABLE));
    }

    @Test
    void sizeIsCappedAt100() {
        assertThat(service.search(search(p1, null, null, null, null, 500)).getSize()).isEqualTo(100);
    }

    @Test
    void usernamesAreNullWhenIdentityIsDown() {
        when(publicUserClient.byIds(any())).thenReturn(Map.of());

        PageResponse<SubmissionListItemDto> page = service.search(search(p1, null, null, null, null, 20));

        assertThat(page.getData()).hasSize(8);
        assertThat(page.getData()).extracting(SubmissionListItemDto::username).containsOnlyNulls();
    }
}
