package vn.thanhtuanle.messaging.outbox;

import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;

/**
 * Which send failures will never heal by waiting. A timeout, a broker that is down or a lost leader may be gone on the
 * next pass: the relay stops and tries again, uncounted. A record that is too large, a topic the identity may not
 * write, an invalid topic name or an unserialisable value is the record's own problem: the same send fails every time,
 * so it is counted, and after the limit the row is given up on instead of standing at the head of the queue.
 */
final class OutboxFailures {

    private OutboxFailures() {
    }

    static boolean isPermanent(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof RecordTooLargeException || cause instanceof AuthorizationException
                    || cause instanceof InvalidTopicException || cause instanceof SerializationException) {
                return true;
            }
        }
        return false;
    }
}
