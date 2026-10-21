package vn.thanhtuanle.submission.problem;

import vn.thanhtuanle.oj.common.web.error.RateLimitedException;
import vn.thanhtuanle.common.exception.ErrorCode;

/** Problems cannot be reached right now (in 2b: problem-service down, slow, or its circuit open); a 503 with {@code Retry-After: 5}. */
public class ProblemCatalogUnavailableException extends RateLimitedException {

    /** Seconds the 503 asks the client to wait (Retry-After). */
    public static final long RETRY_AFTER_SECONDS = 5;

    public ProblemCatalogUnavailableException() {
        super(ErrorCode.PROBLEMS_UNAVAILABLE, RETRY_AFTER_SECONDS);
    }
}
