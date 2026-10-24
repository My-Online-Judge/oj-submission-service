package vn.thanhtuanle.common.exception;

import lombok.Getter;
import vn.thanhtuanle.oj.common.web.error.ErrorCodeSpec;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode implements ErrorCodeSpec {
    USER_NOT_EXISTED("Invalid credentials", HttpStatus.UNAUTHORIZED),
    USER_EXISTED("User existed", HttpStatus.BAD_REQUEST),
    ACCESS_DENIED("Access denied", HttpStatus.FORBIDDEN),
    USER_BLOCKED("User is blocked", HttpStatus.FORBIDDEN),
    INVALID_CREDENTIALS("Invalid credentials", HttpStatus.UNAUTHORIZED),
    ROLE_NOT_EXISTED("Role not found", HttpStatus.NOT_FOUND),
    PERMISSION_NOT_EXISTED("One or more permissions do not exist", HttpStatus.BAD_REQUEST),
    ROLE_IMMUTABLE("This role's permissions cannot be modified", HttpStatus.CONFLICT),
    ROLE_EXISTED("Role name already exists", HttpStatus.CONFLICT),
    ROLE_PROTECTED("This system role cannot be deleted", HttpStatus.CONFLICT),
    ROLE_IN_USE("This role is still assigned to one or more users", HttpStatus.CONFLICT),
    ROLE_NAME_INVALID("Role name must be uppercase letters, digits, or underscore and start with a letter", HttpStatus.BAD_REQUEST),
    USER_NOT_FOUND("User not found", HttpStatus.NOT_FOUND),
    USERNAME_EXISTED("Username already exists", HttpStatus.CONFLICT),
    EMAIL_EXISTED("Email already exists", HttpStatus.CONFLICT),
    USER_PROTECTED("This is a protected system account and cannot be modified", HttpStatus.CONFLICT),
    ROLE_NOT_ASSIGNABLE("This role cannot be assigned to a user", HttpStatus.CONFLICT),
    USER_SELF_MODIFY("You cannot change your own roles, status, or delete your own account", HttpStatus.CONFLICT),
    USERNAME_INVALID("Username must be 3-32 characters: letters, digits, or underscore", HttpStatus.BAD_REQUEST),
    PASSWORD_INVALID("Password must be at least 8 characters and include letters and digits", HttpStatus.BAD_REQUEST),
    EMAIL_INVALID("Email is not valid", HttpStatus.BAD_REQUEST),
    USER_STATUS_INVALID("Status must be ACTIVE or DISABLED", HttpStatus.BAD_REQUEST),
    RATE_LIMITED("Too many failed login attempts. Try again later", HttpStatus.TOO_MANY_REQUESTS),
    ACCESS_BANNED("Access from this address or device is banned", HttpStatus.FORBIDDEN),
    BAN_EXISTED("This value is already banned", HttpStatus.CONFLICT),
    BAN_SELF("You cannot ban your own current IP or device", HttpStatus.CONFLICT),
    BAN_INVALID("Ban type must be IP or DEVICE and value must not be blank", HttpStatus.BAD_REQUEST),
    BAN_NOT_FOUND("Ban not found", HttpStatus.NOT_FOUND),
    SUBMISSION_RATE_LIMITED("You are submitting too fast. Try again in a moment", HttpStatus.TOO_MANY_REQUESTS),
    PROBLEMS_UNAVAILABLE("Problems are temporarily unavailable. Try again in a moment", HttpStatus.SERVICE_UNAVAILABLE),
    IDENTITY_UNAVAILABLE("Users are temporarily unavailable. Try again in a moment", HttpStatus.SERVICE_UNAVAILABLE),
    QUEUE_FULL("The judge is busy, try again in a moment", HttpStatus.SERVICE_UNAVAILABLE),
    STREAMS_FULL("Too many verdict streams are open, try again in a moment", HttpStatus.SERVICE_UNAVAILABLE),
    SUBMISSION_NOT_FINISHED("This submission is still being judged", HttpStatus.CONFLICT),
    CONTEST_NOT_RUNNING("Contest is not running", HttpStatus.BAD_REQUEST),
    // Run (docs/superpowers/specs/2026-10-24-run-code-design.md §5, §6).
    RUN_RATE_LIMITED("You are running code too fast. Try again in a moment", HttpStatus.TOO_MANY_REQUESTS),
    RUN_IN_PROGRESS("You already have a run in progress", HttpStatus.TOO_MANY_REQUESTS),
    RUN_BUSY("The run lane is busy, try again in a moment", HttpStatus.SERVICE_UNAVAILABLE),
    RUN_TIMED_OUT("Your run took too long to answer. Try again", HttpStatus.GATEWAY_TIMEOUT),
    // A problem without a published test-case bundle (a draft): the client's condition, not a server fault.
    PROBLEM_NOT_JUDGEABLE("This problem has no test data yet", HttpStatus.CONFLICT),
    UNCATEGORIZED_EXCEPTION("Uncategorized error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String message;
    private final HttpStatus statusCode;

    ErrorCode(String message, HttpStatus statusCode) {
        this.message = message;
        this.statusCode = statusCode;
    }
}
