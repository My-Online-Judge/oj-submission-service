package vn.thanhtuanle.judge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeCompileConfigDto {
    @JsonProperty("src_name")
    private String srcName;

    @JsonProperty("exe_name")
    private String exeName;

    @JsonProperty("max_cpu_time")
    private Integer maxCpuTime;

    @JsonProperty("max_real_time")
    private Integer maxRealTime;

    @JsonProperty("max_memory")
    private Long maxMemory;

    @JsonProperty("compile_command")
    private String compileCommand;

    /** The compiler's environment; judge-server appends PATH, and sets nothing else (no HOME). */
    private List<String> env;
}
