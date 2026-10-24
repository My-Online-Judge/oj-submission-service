package vn.thanhtuanle.run;

import org.springframework.web.context.request.async.DeferredResult;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.run.dto.RunResponseDto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A run this instance is waiting for (Run code design §6): who asked, what the reply must be read against
 * ({@code hasExpected} per case in request order, whether the problem has a checker), when it was queued, and the
 * HTTP response to complete.
 */
public record PendingRun(UUID runId, UUID userId, List<Boolean> hasExpected, boolean checker, Instant startedAt,
        DeferredResult<ApiResponse<RunResponseDto>> result) {
}
