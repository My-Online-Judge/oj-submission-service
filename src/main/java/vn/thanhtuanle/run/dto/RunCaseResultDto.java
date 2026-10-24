package vn.thanhtuanle.run.dto;

/**
 * One case's outcome: the sandbox's verdict code ({@code SubmissionResult} values), what the program printed (null if
 * the sandbox kept none), CPU time in ms and memory in bytes. {@code errorMessage} is reserved for a per-case message;
 * the sandbox merges stderr into {@code output}, so it is null today. Nulls are serialised: the portal expects the keys.
 */
public record RunCaseResultDto(int result, String output, Integer cpuTime, Long memory, String errorMessage) {
}
