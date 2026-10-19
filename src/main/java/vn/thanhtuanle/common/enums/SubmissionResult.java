package vn.thanhtuanle.common.enums;

import lombok.Getter;

/**
 * Canonical verdict for a submission — mirrors QingdaoU OnlineJudge's backend
 * {@code JudgeStatus} (NOT the raw judge_server result codes).
 *
 * Values 0..5 / -1 coincide with judge_server's {@code result} codes on purpose,
 * so a raw result can be assigned straight through. Values -2, 6, 7, 8 are
 * backend-only: they never arrive from judge_server.
 *
 * This is the single source of truth stored in {@code Submission.status}.
 */
@Getter
public enum SubmissionResult {
    COMPILE_ERROR(-2),
    WRONG_ANSWER(-1),
    ACCEPTED(0),
    CPU_TIME_LIMIT_EXCEEDED(1),
    REAL_TIME_LIMIT_EXCEEDED(2),
    MEMORY_LIMIT_EXCEEDED(3),
    RUNTIME_ERROR(4),
    SYSTEM_ERROR(5),
    PENDING(6),
    JUDGING(7),
    PARTIALLY_ACCEPTED(8);

    private final int value;

    SubmissionResult(int value) {
        this.value = value;
    }

    public static SubmissionResult fromValue(int value) {
        for (SubmissionResult result : SubmissionResult.values()) {
            if (result.getValue() == value) {
                return result;
            }
        }
        throw new IllegalArgumentException("Invalid SubmissionResult value: " + value);
    }

    public static boolean isTerminal(int status) {
        return status != PENDING.getValue() && status != JUDGING.getValue();
    }

    /** A status a verdict may carry: present, one of the known values, and neither PENDING nor JUDGING. */
    public static boolean isTerminalVerdict(Integer status) {
        if (status == null || !isTerminal(status)) {
            return false;
        }
        for (SubmissionResult result : values()) {
            if (result.getValue() == status) {
                return true;
            }
        }
        return false;
    }
}
