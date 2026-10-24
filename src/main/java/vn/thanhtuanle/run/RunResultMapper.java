package vn.thanhtuanle.run;

import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.judge.dto.JudgeResultDto;
import vn.thanhtuanle.messaging.event.RunJudgedEvent;
import vn.thanhtuanle.run.dto.RunCaseResultDto;
import vn.thanhtuanle.run.dto.RunResponseDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The worker's reply as the portal's answer (Run code design §5). A reply without case details is a whole-run
 * outcome (a compile error, a system error). Otherwise the cases are put back in request order — the sandbox names
 * them "1".."n" — and each keeps the sandbox's verdict, except that a case the user gave no expected output for, on a
 * problem without a checker, reports WRONG_ANSWER as 0: all such a case can say is that the program ran. A crash or
 * a limit on such a case stands; on a checker problem the checker's verdict stands for every case.
 */
public final class RunResultMapper {

    private static final int WRONG_ANSWER = SubmissionResult.WRONG_ANSWER.getValue();
    private static final int SYSTEM_ERROR = SubmissionResult.SYSTEM_ERROR.getValue();

    private RunResultMapper() {
    }

    public static RunResponseDto toResponse(PendingRun run, RunJudgedEvent event) {
        List<JudgeResultDto> details = event.details();
        if (details == null || details.isEmpty()) {
            int status = event.status() == null ? SYSTEM_ERROR : event.status();
            return new RunResponseDto(status, event.errorMessage(), List.of());
        }
        List<JudgeResultDto> ordered = new ArrayList<>(details);
        ordered.sort(Comparator.comparingLong(d -> caseNumber(d, details.indexOf(d))));
        List<RunCaseResultDto> cases = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            JudgeResultDto d = ordered.get(i);
            int result = verdictOf(d);
            boolean hasExpected = i >= run.hasExpected().size() || Boolean.TRUE.equals(run.hasExpected().get(i));
            if (!run.checker() && !hasExpected && result == WRONG_ANSWER) {
                result = SubmissionResult.ACCEPTED.getValue();
            }
            cases.add(new RunCaseResultDto(result, d.getOutput(), d.getCpuTime(), d.getMemory(), null));
        }
        return new RunResponseDto(null, null, cases);
    }

    /** The sandbox's name for the case ("1".."n") as its position; a name that is not a number keeps its position. */
    private static long caseNumber(JudgeResultDto d, int position) {
        try {
            return Long.parseLong(d.getTestCase());
        } catch (NumberFormatException | NullPointerException e) {
            return position + 1L;
        }
    }

    private static int verdictOf(JudgeResultDto d) {
        if (d.getError() != null && d.getError() != 0) {
            return SYSTEM_ERROR;
        }
        return d.getResult() == null ? SYSTEM_ERROR : d.getResult();
    }
}
