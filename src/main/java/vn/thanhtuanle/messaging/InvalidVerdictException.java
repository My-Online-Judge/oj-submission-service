package vn.thanhtuanle.messaging;

/**
 * A verdict no retry can ever apply (a record without a value, or a {@code submissionId} that is missing or not a
 * UUID): the record goes to the dead-letter topic at once instead of being retried.
 */
public class InvalidVerdictException extends RuntimeException {

    public InvalidVerdictException(String message, Throwable cause) {
        super(message, cause);
    }
}
