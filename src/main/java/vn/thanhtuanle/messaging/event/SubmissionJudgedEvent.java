package vn.thanhtuanle.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.thanhtuanle.judge.dto.JudgeResultDto;

import java.util.List;

/** Unknown fields (anything a newer worker adds) are ignored, like {@link RunJudgedEvent}. */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionJudgedEvent {
    private String submissionId;
    private Integer status;
    private Integer result;

    @JsonProperty("cpu_time")
    private Integer cpuTime;

    @JsonProperty("real_time")
    private Integer realTime;

    private Long memory;

    @JsonProperty("error_message")
    private String errorMessage;

    private List<JudgeResultDto> details;

    /** The request's generation, echoed by the worker; null from an old worker or a request queued before it. */
    private Integer rejudgeCount;

    /** The worker (pod) that judged it, as the worker names itself; null from a worker that does not say. */
    private String judgedBy;
}
