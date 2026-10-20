package vn.thanhtuanle.stats;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import io.micrometer.core.instrument.MeterRegistry;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.stats.dto.RecentSubmissionDto;
import vn.thanhtuanle.stats.dto.UserStatsDto;
import vn.thanhtuanle.submission.SubmissionRepository;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.submission.problem.ProblemCatalogUnavailableException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class UserStatsServiceTest {

    @Autowired UserStatsService service;
    @Autowired SubmissionRepository submissions;
    @Autowired LanguageRepository languages;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;

    @MockitoBean ProblemCatalog catalog;

    private final UUID user = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final List<UUID> ids = new ArrayList<>();
    private Language language;

    @BeforeEach
    void seedLanguage() {
        language = languages.save(Language.builder().name("C++ 17").identifier("cpp-" + UUID.randomUUID()).build());
        when(catalog.visibleSlugs(any())).thenAnswer(i -> Set.copyOf(i.<Collection<String>>getArgument(0)));
    }

    @AfterEach
    void clean() {
        submissions.deleteAllById(ids);
        languages.deleteById(language.getId());
    }

    private UUID add(String slug, int status, LocalDateTime at) {
        UUID id = submissions.save(Submission.builder().sourceCode("secret").status(status).time(0).memory(1L)
                .problemId(UUID.randomUUID()).problemSlug(slug).language(language).userId(user).build()).getId();
        jdbc.update("update t_submissions set created_at = ? where id = ?", at, id);
        ids.add(id);
        return id;
    }

    private void seedMixed() {
        LocalDateTime t = LocalDateTime.now().minusHours(1);
        add("p1", 1, t);
        add("p1", 0, t.plusMinutes(1));
        add("p2", 5, t.plusMinutes(2));
        add("p3", 6, t.plusMinutes(3));
    }

    @Test
    void countsAndSolvedSlugs() {
        seedMixed();

        UserStatsDto stats = service.stats(user, user);

        assertThat(stats.submissionCount()).isEqualTo(3);
        assertThat(stats.acceptedCount()).isEqualTo(1);
        assertThat(stats.attemptedCount()).isEqualTo(2);
        assertThat(stats.solvedCount()).isEqualTo(1);
        assertThat(stats.solvedProblems()).containsExactly("p1");
        assertThat(stats.recent()).hasSize(4);
        assertThat(stats.recent().get(0).language()).isEqualTo("C++ 17");
    }

    @Test
    void recentIsTheTwentyNewest() {
        LocalDateTime t = LocalDateTime.now().minusHours(2);
        for (int i = 0; i < 25; i++) {
            add("p" + i, 1, t.plusMinutes(i));
        }

        List<RecentSubmissionDto> recent = service.stats(user, user).recent();

        assertThat(recent).hasSize(20);
        assertThat(recent.get(0).problemSlug()).isEqualTo("p24");
        assertThat(recent.get(19).problemSlug()).isEqualTo("p5");
    }

    @Test
    void unknownUserIsZeros() {
        UserStatsDto stats = service.stats(UUID.randomUUID(), null);

        assertThat(stats).isEqualTo(new UserStatsDto(0, 0, 0, 0, List.of(), List.of()));
    }

    @Test
    void nonOwnerLosesTheSlugsOfProblemsThatAreNotVisible() {
        seedMixed();
        doReturn(Set.of("p2", "p3")).when(catalog).visibleSlugs(any());

        UserStatsDto stats = service.stats(user, stranger);

        assertThat(stats.solvedProblems()).isEmpty();
        assertThat(stats.recent()).extracting(RecentSubmissionDto::problemSlug).containsExactly("p3", "p2");
        assertThat(stats.solvedCount()).isEqualTo(1);
        assertThat(stats.attemptedCount()).isEqualTo(2);
        assertThat(stats.submissionCount()).isEqualTo(3);
        assertThat(stats.acceptedCount()).isEqualTo(1);
    }

    @Test
    void anonymousIsANonOwnerToo() {
        seedMixed();
        doReturn(Set.of()).when(catalog).visibleSlugs(any());

        UserStatsDto stats = service.stats(user, null);

        assertThat(stats.solvedProblems()).isEmpty();
        assertThat(stats.recent()).isEmpty();
    }

    @Test
    void ownerSeesEverythingWithoutTheCall() {
        seedMixed();

        UserStatsDto stats = service.stats(user, user);

        assertThat(stats.solvedProblems()).containsExactly("p1");
        assertThat(stats.recent()).hasSize(4);
        verify(catalog, never()).visibleSlugs(any());
    }

    @Test
    void oneCallCarriesTheDistinctSlugs() {
        seedMixed();

        service.stats(user, stranger);

        verify(catalog, times(1)).visibleSlugs(Set.of("p1", "p2", "p3"));
    }

    @Test
    void anRpcFailureHidesEverySlugAndKeepsTheCounts() {
        seedMixed();
        doThrow(new ProblemCatalogUnavailableException()).when(catalog).visibleSlugs(any());
        double before = meters.counter("oj.grpc.fallback", "call", "list_visible_slugs").count();

        UserStatsDto stats = service.stats(user, stranger);

        assertThat(stats.solvedProblems()).isEmpty();
        assertThat(stats.recent()).isEmpty();
        assertThat(stats.submissionCount()).isEqualTo(3);
        assertThat(stats.solvedCount()).isEqualTo(1);
        assertThat(meters.counter("oj.grpc.fallback", "call", "list_visible_slugs").count()).isEqualTo(before + 1);
    }

    @Test
    void noSlugsMeansNoCall() {
        service.stats(UUID.randomUUID(), stranger);

        verify(catalog, never()).visibleSlugs(any());
    }

    @Test
    void atMostAHundredSlugsAreAsked() {
        LocalDateTime t = LocalDateTime.now().minusHours(3);
        for (int i = 0; i < 120; i++) {
            add(String.format("s%03d", i), 0, t.plusSeconds(i));
        }
        doAnswer(i -> {
            assertThat(i.<Collection<String>>getArgument(0)).hasSizeLessThanOrEqualTo(100);
            return Set.copyOf(i.<Collection<String>>getArgument(0));
        }).when(catalog).visibleSlugs(any());

        UserStatsDto stats = service.stats(user, stranger);

        assertThat(stats.solvedCount()).isEqualTo(120);
        assertThat(stats.solvedProblems()).hasSizeLessThanOrEqualTo(100);
    }
}
