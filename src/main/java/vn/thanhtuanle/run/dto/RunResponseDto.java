package vn.thanhtuanle.run.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The answer to a run (Run code design §5). {@code result} and {@code errorMessage} are set only when the whole run
 * stopped before any case (a compile error, a system error): {@code cases} is then empty. Otherwise both are left out
 * (the portal reads them as undefined) and {@code cases} has one entry per request case, in request order.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunResponseDto(Integer result, String errorMessage, List<RunCaseResultDto> cases) {
}
