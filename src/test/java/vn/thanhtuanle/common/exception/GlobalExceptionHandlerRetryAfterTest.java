package vn.thanhtuanle.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerRetryAfterTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void rateLimitedException_carriesDynamicRetryAfter() {
        RateLimitedException ex = new RateLimitedException(ErrorCode.SUBMISSION_RATE_LIMITED, 7);

        ResponseEntity<ApiResponse<Object>> res = handler.handleRateLimitedException(ex);

        assertThat(res.getStatusCode().value()).isEqualTo(429);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("7");
        assertThat(res.getBody().getMessage()).contains("submitting too fast");
    }

    @Test
    void unavailableProblems_say503AndWhenToRetry() {
        ResponseEntity<ApiResponse<Object>> res = handler.handleRateLimitedException(
                new vn.thanhtuanle.submission.problem.ProblemCatalogUnavailableException());

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("5");
    }
}
