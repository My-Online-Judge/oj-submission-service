package vn.thanhtuanle.stats;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.stats.dto.RecentSubmissionDto;
import vn.thanhtuanle.stats.dto.UserStatsDto;
import vn.thanhtuanle.submission.SubmissionRepository;
import vn.thanhtuanle.submission.problem.ProblemCatalog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class UserStatsService {

    static final int RECENT = 20;
    /** The most slugs one visibility call carries. */
    static final int MAX_SLUGS = 100;

    private final SubmissionRepository submissions;
    private final ProblemCatalog catalog;
    private final Counter fallbacks;

    public UserStatsService(SubmissionRepository submissions, ProblemCatalog catalog, MeterRegistry registry) {
        this.submissions = submissions;
        this.catalog = catalog;
        this.fallbacks = Counter.builder("oj.grpc.fallback").tag("call", "list_visible_slugs")
                .description("Calls to problem-service that failed and were answered by failing closed")
                .register(registry);
    }

    /**
     * @param viewerId the caller's user id, or null when anonymous. Anyone but the owner sees only the slugs of
     *                 problems problem-service calls visible; the counts are never filtered.
     */
    @Transactional(readOnly = true)
    public UserStatsDto stats(UUID userId, UUID viewerId) {
        List<Object[]> rows = submissions.aggregateTerminal(userId);
        Object[] row = rows.isEmpty() ? new Object[]{0L, null, 0L} : rows.get(0);
        long submissionCount = ((Number) row[0]).longValue();
        long acceptedCount = row[1] == null ? 0 : ((Number) row[1]).longValue();
        long attemptedCount = ((Number) row[2]).longValue();

        List<String> solved = submissions.findSolvedSlugs(userId);
        List<RecentSubmissionDto> recent = submissions.findRecent(userId, PageRequest.of(0, RECENT)).stream()
                .map(UserStatsService::recent).toList();
        long solvedCount = solved.size();

        if (!userId.equals(viewerId)) {
            Set<String> visible = visible(solved, recent);
            solved = solved.stream().filter(visible::contains).toList();
            recent = recent.stream().filter(r -> visible.contains(r.problemSlug())).toList();
        }
        return new UserStatsDto(solvedCount, attemptedCount, submissionCount, acceptedCount, solved, recent);
    }

    /** One call for every distinct slug (recent first, at most 100). Fails closed: nothing is visible on a failure. */
    private Set<String> visible(List<String> solved, List<RecentSubmissionDto> recent) {
        Set<String> asked = new LinkedHashSet<>();
        recent.forEach(r -> asked.add(r.problemSlug()));
        for (String slug : solved) {
            if (asked.size() >= MAX_SLUGS) {
                break;
            }
            asked.add(slug);
        }
        if (asked.isEmpty()) {
            return Set.of();
        }
        try {
            return catalog.visibleSlugs(asked);
        } catch (RuntimeException e) {
            fallbacks.increment();
            log.warn("problem-service could not list the visible slugs, hiding all {}: {}", asked.size(), e.toString());
            return Set.of();
        }
    }

    private static RecentSubmissionDto recent(Submission s) {
        return new RecentSubmissionDto(s.getId(), s.getProblemSlug(), s.getStatus(), s.getLanguage().getName(),
                s.getCreatedAt());
    }
}
