package vn.thanhtuanle.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.judge.dto.JudgeResultDto;
import vn.thanhtuanle.submission.dto.TestCaseResultDto;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.submission.problem.SampleTestCase;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionDetailAssemblerTest {

    @Mock ProblemCatalog problemCatalog;
    @InjectMocks SubmissionDetailAssembler assembler;

    private Submission submissionWith(List<JudgeResultDto> details) {
        Submission s = Submission.builder().problemId(UUID.randomUUID()).build();
        s.setDetails(details);
        return s;
    }

    private JudgeResultDto judged(String name, int result) {
        return JudgeResultDto.builder()
                .testCase(name).result(result)
                .cpuTime(12).realTime(15).memory(3072L)
                .output("42").outputMd5("deadbeef")
                .build();
    }

    @Test
    void hiddenCase_neverExposesTestData() {
        Submission s = submissionWith(List.of(judged("2", -1)));
        when(problemCatalog.sampleCases(s.getProblemId())).thenReturn(Map.of());  // "2" is not a sample

        TestCaseResultDto row = assembler.assemble(s).get(0);

        assertThat(row.isSample()).isFalse();
        assertThat(row.getInput()).isNull();
        assertThat(row.getExpectedOutput()).isNull();
        assertThat(row.getActualOutput()).isNull();
        // metrics are still exposed
        assertThat(row.getCpuTime()).isEqualTo(12);
        assertThat(row.getMemory()).isEqualTo(3072L);
        assertThat(row.getResult()).isEqualTo(-1);
    }

    @Test
    void sampleCase_exposesInputExpectedAndActual() {
        Submission s = submissionWith(List.of(judged("1", -1)));
        when(problemCatalog.sampleCases(s.getProblemId()))
                .thenReturn(Map.of("1", new SampleTestCase("1", "3 4", "7")));

        TestCaseResultDto row = assembler.assemble(s).get(0);

        assertThat(row.isSample()).isTrue();
        assertThat(row.getInput()).isEqualTo("3 4");
        assertThat(row.getExpectedOutput()).isEqualTo("7");
        assertThat(row.getActualOutput()).isEqualTo("42");
    }

    @Test
    void unknownTestCaseName_failsClosedAsHidden() {
        // Admin edited test cases after this submission was judged.
        Submission s = submissionWith(List.of(judged("99", -1)));
        when(problemCatalog.sampleCases(s.getProblemId())).thenReturn(Map.of());

        TestCaseResultDto row = assembler.assemble(s).get(0);

        assertThat(row.isSample()).isFalse();
        assertThat(row.getInput()).isNull();
        assertThat(row.getExpectedOutput()).isNull();
        assertThat(row.getActualOutput()).isNull();
    }

    @Test
    void nullDetails_returnsNull() {
        Submission s = submissionWith(null);

        assertThat(assembler.assemble(s)).isNull();
    }

    @Test
    void emptyDetails_returnsEmptyList() {
        Submission s = submissionWith(List.of());

        assertThat(assembler.assemble(s)).isEmpty();
    }

    @Test
    void aLongActualOutputIsCapped() {
        JudgeResultDto big = JudgeResultDto.builder().testCase("1").result(-1).cpuTime(1).realTime(1).memory(1L)
                .output("x".repeat(70_000)).outputMd5("m").build();
        Submission s = submissionWith(List.of(big));
        when(problemCatalog.sampleCases(s.getProblemId()))
                .thenReturn(Map.of("1", new SampleTestCase("1", "3 4", "7")));

        String actual = assembler.assemble(s).get(0).getActualOutput();

        assertThat(actual).endsWith(vn.thanhtuanle.oj.common.text.TextCaps.TRUNCATION_MARK);
        assertThat(actual).hasSize(65536 + vn.thanhtuanle.oj.common.text.TextCaps.TRUNCATION_MARK.length());
    }
}
