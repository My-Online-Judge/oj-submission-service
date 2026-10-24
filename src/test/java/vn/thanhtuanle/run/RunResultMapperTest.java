package vn.thanhtuanle.run;

import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.DeferredResult;
import vn.thanhtuanle.judge.dto.JudgeResultDto;
import vn.thanhtuanle.messaging.event.RunJudgedEvent;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.run.dto.RunResponseDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The worker's reply becomes the portal's answer (Run code design §5): order, the no-expected-output rule, codes. */
class RunResultMapperTest {

    private static PendingRun run(boolean checker, Boolean... hasExpected) {
        return new PendingRun(UUID.randomUUID(), UUID.randomUUID(), List.of(hasExpected), checker, Instant.now(),
                new DeferredResult<ApiResponse<RunResponseDto>>());
    }

    private static JudgeResultDto detail(String testCase, int result) {
        return JudgeResultDto.builder().testCase(testCase).result(result).error(0).cpuTime(2).memory(1839104L)
                .output(testCase + "\n").build();
    }

    private static RunJudgedEvent reply(int status, String errorMessage, List<JudgeResultDto> details) {
        return new RunJudgedEvent("r", status, null, 2, 3, 1839104L, errorMessage, details);
    }

    @Test
    void aCompileErrorIsAWholeRunOutcomeWithNoCases() {
        RunResponseDto out = RunResultMapper.toResponse(run(false, true), reply(-2, "CompileError: x", List.of()));

        assertThat(out.result()).isEqualTo(-2);
        assertThat(out.errorMessage()).isEqualTo("CompileError: x");
        assertThat(out.cases()).isEmpty();
    }

    @Test
    void aReplyWithoutDetailsAndWithoutAStatusIsASystemError() {
        RunResponseDto out = RunResultMapper.toResponse(run(false, true), reply(5, "boom", null));

        assertThat(out.result()).isEqualTo(5);
        assertThat(out.errorMessage()).isEqualTo("boom");
        assertThat(out.cases()).isEmpty();
    }

    @Test
    void casesComeBackInRequestOrderEvenIfTheSandboxListsThemOtherwise() {
        RunResponseDto out = RunResultMapper.toResponse(run(false, true, true),
                reply(0, null, List.of(detail("2", 0), detail("1", -1))));

        assertThat(out.result()).isNull();
        assertThat(out.errorMessage()).isNull();
        assertThat(out.cases()).extracting("output").containsExactly("1\n", "2\n");
        assertThat(out.cases().get(0).result()).isEqualTo(-1);
    }

    @Test
    void aWrongAnswerOnACaseWithoutExpectedOutputMeansItRan() {
        RunResponseDto out = RunResultMapper.toResponse(run(false, false, true),
                reply(-1, null, List.of(detail("1", -1), detail("2", -1))));

        assertThat(out.cases().get(0).result()).isZero();
        assertThat(out.cases().get(1).result()).isEqualTo(-1);
    }

    @Test
    void aCrashOnACaseWithoutExpectedOutputStaysARuntimeError() {
        RunResponseDto out = RunResultMapper.toResponse(run(false, false, false),
                reply(4, null, List.of(detail("1", 4), detail("2", 1))));

        assertThat(out.cases().get(0).result()).isEqualTo(4);
        assertThat(out.cases().get(1).result()).isEqualTo(1);
    }

    @Test
    void onACheckerProblemTheCheckersVerdictStands() {
        RunResponseDto out = RunResultMapper.toResponse(run(true, false), reply(-1, null, List.of(detail("1", -1))));

        assertThat(out.cases().get(0).result()).isEqualTo(-1);
    }

    @Test
    void aSandboxErrorOnOneCaseIsASystemErrorForThatCase() {
        JudgeResultDto broken = JudgeResultDto.builder().testCase("1").result(0).error(-10).build();

        RunResponseDto out = RunResultMapper.toResponse(run(false, false), reply(5, null, List.of(broken)));

        assertThat(out.cases().get(0).result()).isEqualTo(5);
    }

    @Test
    void outputTimeAndMemoryAreCopied() {
        RunResponseDto out = RunResultMapper.toResponse(run(false, true), reply(0, null, List.of(detail("1", 0))));

        assertThat(out.cases()).singleElement().satisfies(c -> {
            assertThat(c.output()).isEqualTo("1\n");
            assertThat(c.cpuTime()).isEqualTo(2);
            assertThat(c.memory()).isEqualTo(1839104L);
            assertThat(c.errorMessage()).isNull();
        });
    }
}
