package vn.thanhtuanle.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import vn.thanhtuanle.judge.dto.JudgeResultDto;

import java.util.List;

/**
 * judge-worker's reply on {@code run.judged} (Run code design §7): the verdict envelope of {@code submission.judged},
 * named by {@code runId}. {@code status} is the whole run's verdict ({@code SubmissionResult} codes), {@code details}
 * one entry per case, empty when the run ended before the cases (a compile error, a system error). Unknown fields
 * (a generation, anything a newer worker adds) are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunJudgedEvent(
        String runId,
        Integer status,
        Integer result,
        @JsonProperty("cpu_time") Integer cpuTime,
        @JsonProperty("real_time") Integer realTime,
        Long memory,
        @JsonProperty("error_message") String errorMessage,
        List<JudgeResultDto> details) {
}
