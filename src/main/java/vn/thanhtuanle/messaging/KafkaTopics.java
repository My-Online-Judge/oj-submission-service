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
}
