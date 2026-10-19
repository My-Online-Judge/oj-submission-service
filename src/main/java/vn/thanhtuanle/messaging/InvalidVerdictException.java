package vn.thanhtuanle.messaging;

/**
 * A verdict no retry can ever apply (a {@code submissionId} that is not a UUID): the record goes to the dead-letter
 * topic at once instead of being retried.
 */
public class InvalidVerdictException extends RuntimeException {

    public InvalidVerdictException(String message, Throwable cause) {
        super(message, cause);
    }
}
