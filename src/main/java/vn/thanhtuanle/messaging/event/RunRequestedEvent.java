package vn.thanhtuanle.messaging.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.thanhtuanle.judge.dto.JudgeCompileConfigDto;
import vn.thanhtuanle.judge.dto.JudgeLanguageConfigDto;
import vn.thanhtuanle.judge.dto.JudgeSpjConfigDto;

import java.util.List;

/**
 * A run on {@code run.requested} (Run code design §7): {@link SubmissionRequestedEvent}'s judge fields with the cases
 * inline ({@code test_case}) in place of a bundle ({@code test_case_id}), named by {@code runId} and carrying the
 * instant after which nobody waits for it. Nothing of a submission: no submissionId, no generation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunRequestedEvent {
    private String runId;

    /** Epoch milliseconds: publish time plus the wait. judge-worker skips a run it picks up after this. */
    private Long expiresAt;

    private String src;

    @JsonProperty("language_config")
    private JudgeLanguageConfigDto languageConfig;

    @JsonProperty("max_cpu_time")
    private Integer maxCpuTime;

    @JsonProperty("max_memory")
    private Long maxMemory;

    @JsonProperty("test_case")
    private List<RunTestCase> testCase;

    private Boolean output;

    @JsonProperty("spj_version")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String spjVersion;

    @JsonProperty("spj_config")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JudgeSpjConfigDto spjConfig;

    @JsonProperty("spj_compile_config")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JudgeCompileConfigDto spjCompileConfig;

    @JsonProperty("spj_src")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String spjSrc;

    /**
     * The run's request from a submission request built for the same source, problem and language
     * ({@code JudgeService.buildRequestedEvent}): the judge fields are copied, the bundle and the submission's identity
     * are not.
     */
    public static RunRequestedEvent from(SubmissionRequestedEvent template, String runId, long expiresAt,
            List<RunTestCase> cases) {
        return RunRequestedEvent.builder()
                .runId(runId)
                .expiresAt(expiresAt)
                .src(template.getSrc())
                .languageConfig(template.getLanguageConfig())
                .maxCpuTime(template.getMaxCpuTime())
                .maxMemory(template.getMaxMemory())
                .testCase(cases)
                .output(template.getOutput())
                .spjVersion(template.getSpjVersion())
                .spjConfig(template.getSpjConfig())
                .spjCompileConfig(template.getSpjCompileConfig())
                .spjSrc(template.getSpjSrc())
                .build();
    }
}
