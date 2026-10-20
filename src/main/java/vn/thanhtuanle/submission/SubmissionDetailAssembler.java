package vn.thanhtuanle.submission;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.oj.common.text.TextCaps;
import vn.thanhtuanle.judge.dto.JudgeResultDto;
import vn.thanhtuanle.submission.dto.TestCaseResultDto;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.submission.problem.SampleTestCase;

import java.util.List;
import java.util.Map;

/**
 * The ONLY place a persisted {@code Submission.details} array becomes a user-facing DTO.
 *
 * <p>The stored array is the raw judge payload and contains the contestant's stdout for every
 * test case, including hidden ones. Exposing it verbatim would let a user submit a program that
 * echoes stdin and read back the hidden test data. Everything therefore flows through
 * {@link #assemble}, which drops that data unless the case is explicitly marked sample.
 *
 * <p>Do not read {@code Submission.getDetails()} anywhere else.
 */
@Component
@RequiredArgsConstructor
public class SubmissionDetailAssembler {

    private final ProblemCatalog problemCatalog;

    /**
     * @return one row per judged test case, or {@code null} when the submission was judged
     *         before per-test details were persisted (distinct from an empty list, which means
     *         "judged, but no cases ran" — e.g. a compile error).
     */
    public List<TestCaseResultDto> assemble(Submission submission) {
        List<JudgeResultDto> details = submission.getDetails();
        if (details == null) {
            return null;
        }
        Map<String, SampleTestCase> samples = problemCatalog.sampleCases(submission.getProblemId());
        return details.stream().map(d -> toRow(d, samples)).toList();
    }

    private TestCaseResultDto toRow(JudgeResultDto d, Map<String, SampleTestCase> samples) {
        // Fail closed: a name that is not a sample — hidden, edited away by an admin after this
        // submission was judged, or problems unreachable right now — is shown as hidden.
        SampleTestCase sample = samples.get(d.getTestCase());

        TestCaseResultDto.TestCaseResultDtoBuilder row = TestCaseResultDto.builder()
                .name(d.getTestCase())
                .result(d.getResult())
                .cpuTime(d.getCpuTime())
                .realTime(d.getRealTime())
                .memory(d.getMemory())
                .sample(sample != null);

        if (sample != null) {
            row.input(sample.input())
               .expectedOutput(sample.expectedOutput())
               .actualOutput(TextCaps.cap(d.getOutput(), TextCaps.SAMPLE_MAX_CHARS));
        }
        // Hidden: input/expectedOutput/actualOutput are left unset. `output_md5`, `signal`,
        // `exit_code` and `error` are never exposed to users at all.
        return row.build();
    }
}
