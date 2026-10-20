package vn.thanhtuanle.submission;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.thanhtuanle.common.constant.Routes;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.submission.dto.RejudgeQueued;

/** The per-problem rejudge: the one {@code /problems} path the gateway routes to this service. */
@RestController
@RequestMapping(Routes.PROBLEMS)
@RequiredArgsConstructor
@Slf4j
public class ProblemRejudgeController {

    private final RejudgeService rejudgeService;

    @PostMapping("/{slug}/rejudge")
    @PreAuthorize("hasAuthority('submission:rejudge')")
    @Operation(summary = "Rejudge the newest 500 finished submissions of a problem, optionally of one verdict "
            + "(admin only)")
    public ApiResponse<RejudgeQueued> rejudge(@PathVariable String slug,
                                              @RequestParam(required = false) Integer status) {
        log.info("Rejudge requested for problem {} (status {})", slug, status);
        return ApiResponse.success(rejudgeService.rejudgeProblem(slug, status));
    }
}
