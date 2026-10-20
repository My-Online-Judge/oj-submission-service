package vn.thanhtuanle.messaging.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.thanhtuanle.judge.dto.JudgeLanguageConfigDto;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionRequestedEvent {
    private String submissionId;
    private String src;

    @JsonProperty("language_config")
    private JudgeLanguageConfigDto languageConfig;

    @JsonProperty("max_cpu_time")
    private Integer maxCpuTime;

    @JsonProperty("max_memory")
    private Long maxMemory;

    @JsonProperty("test_case_id")
    private String testCaseId;

    private Boolean output;

    /**
     * The submission's {@code rejudge_count} when this request was queued: 0 for a submit, n after the n-th rejudge.
     * judge-worker echoes it on {@code submission.started} and {@code submission.judged}, so a start or verdict of an
     * earlier generation is not applied to a rejudged row.
     */
    private Integer rejudgeCount;
}
