package vn.thanhtuanle.submission.problem;

import lombok.Getter;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.oj.common.web.error.AppException;

/**
 * The problem exists but cannot be judged: it has no published test-case bundle (a draft). Answered 409
 * {@code PROBLEM_NOT_JUDGEABLE} for a submit and for a run alike (Run code design §5); raised before the cooldown is
 * taken. The slug is for the log line, never for the response.
 */
@Getter
public class ProblemNotJudgeableException extends AppException {

    private final String slug;

    public ProblemNotJudgeableException(String slug) {
        super(ErrorCode.PROBLEM_NOT_JUDGEABLE);
        this.slug = slug;
    }
}
