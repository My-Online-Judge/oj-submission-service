package vn.thanhtuanle.submission.problem;

import java.util.Map;
import java.util.UUID;

/**
 * Everything the submission side asks of problems — the only way it reaches them. Today the problems
 * live in this service ({@code LocalProblemCatalog}); in sub-project 2b they move to problem-service and
 * this interface gets a gRPC implementation, with no change to its callers.
 */
public interface ProblemCatalog {

    /**
     * @param allowInactive true lets an INACTIVE problem be judged (problem editors, rejudges)
     * @throws ProblemNotFoundException           unknown or deleted slug (404)
     * @throws ProblemCatalogUnavailableException problems cannot be reached right now (503)
     */
    JudgeSpec judgeSpec(String slug, boolean allowInactive);

    /**
     * The problem's sample test cases by name; a name that is not in the map is hidden. Deleted problems
     * included (old submissions stay readable). Never throws: if problems cannot be reached the map is
     * empty, so every row shows as hidden — failing closed.
     */
    Map<String, SampleTestCase> sampleCases(UUID problemId);
}
