package vn.thanhtuanle.judge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * qduoj's {@code spj_config}: how a compiled checker is run. qduoj formats the braces in {@code exeName} and
 * {@code command} itself.
 */
public record JudgeSpjConfigDto(@JsonProperty("exe_name") String exeName, String command,
        @JsonProperty("seccomp_rule") String seccompRule) {
}
