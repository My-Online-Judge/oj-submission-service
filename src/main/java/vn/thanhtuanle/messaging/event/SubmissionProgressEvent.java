package vn.thanhtuanle.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * judge-worker's report on {@code submission.progress} that one more case of a submission is judged (the sandbox's
 * {@code JudgeStream}): the case's name, its result code (the verdict's {@code details[].result}), the judger error
 * code and its measurements, plus {@code total}, the bundle's case count (null when the worker could not read it) and
 * {@code rejudgeCount}, the request's generation (null from an old worker). It never carries the program's output;
 * that travels once, in the verdict.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubmissionProgressEvent(String submissionId, String worker, String testCase, Integer result,
                                      Integer error, Long cpuTime, Long realTime, Long memory, Integer total,
                                      Integer rejudgeCount) {
}
