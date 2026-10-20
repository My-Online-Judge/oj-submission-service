package vn.thanhtuanle.submission.problem;

import vn.thanhtuanle.oj.common.web.error.AppException;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the submission side asks of problems — the only way it reaches them. Today the problems
 * live in this service ({@code LocalProblemCatalog}); in sub-project 2b they move to problem-service and
 * this interface gets a gRPC implementation, with no change to its callers.
 */
public interface ProblemCatalog {

    /**
     * @param allowInactive true lets an INACTIVE problem be judged (problem editors, rejudges)
     * @param contestSlug   the contest the submission is made in, or null for practice; the spec then carries the
     *                      contest's id
     * @throws ProblemNotFoundException           unknown or deleted slug (404)
     * @throws ProblemCatalogUnavailableException problems cannot be reached right now (503)
     * @throws AppException                       {@code CONTEST_NOT_RUNNING} (400): the contest is not running or
     *                                            does not contain the problem
     */
    JudgeSpec judgeSpec(String slug, boolean allowInactive, String contestSlug);

    /** Outside any contest. */
    default JudgeSpec judgeSpec(String slug, boolean allowInactive) {
        return judgeSpec(slug, allowInactive, null);
    }

    /**
     * The problem's sample test cases by name; a name that is not in the map is hidden. Deleted problems
     * included (old submissions stay readable). Never throws: if problems cannot be reached the map is
     * empty, so every row shows as hidden — failing closed.
     */
    Map<String, SampleTestCase> sampleCases(UUID problemId);

    /**
     * Which of the slugs are ACTIVE problems (at most 100 per call). One call.
     *
     * @throws ProblemCatalogUnavailableException problems cannot be reached right now
     */
    Set<String> visibleSlugs(Collection<String> slugs);
}
