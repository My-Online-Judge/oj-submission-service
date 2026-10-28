package vn.thanhtuanle.messaging;

public final class KafkaTopics {
    private KafkaTopics() {}

    public static final String SUBMISSION_REQUESTED = "submission.requested";
    public static final String SUBMISSION_JUDGED = "submission.judged";
    // Dead-letter topic: verdicts that repeatedly fail to process land here instead of being dropped.
    public static final String SUBMISSION_JUDGED_DLQ = "submission.judged.dlq";
    // judge-worker announces each judging start here, keyed by submissionId.
    public static final String SUBMISSION_STARTED = "submission.started";
    public static final String SUBMISSION_STARTED_GROUP = "submission-started";
    // The Run lane (Run code design §7): requests with inline test cases, keyed by runId, judged by the run-workers;
    // their replies, read by this service's run-results group. Nothing on either topic names a submission.
    public static final String RUN_REQUESTED = "run.requested";
    public static final String RUN_JUDGED = "run.judged";
    /** Each submission-service instance reads run.judged in {@code run-results-<instance>} (RunProperties). */
    public static final String RUN_RESULTS_GROUP_PREFIX = "run-results-";
}
