package vn.thanhtuanle.messaging.event;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One inline test case of a run, as qduoj's {@code JudgeServer.judge} takes it in {@code test_case}: the input, and
 * the expected {@code output} it compares with — required on a problem without a checker (an empty string for a case
 * the user gave no expected output for), left out on a checker problem, where the checker judges the case.
 */
public record RunTestCase(String input, @JsonInclude(JsonInclude.Include.NON_NULL) String output) {
}
