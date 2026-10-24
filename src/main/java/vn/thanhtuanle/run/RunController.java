package vn.thanhtuanle.run;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import vn.thanhtuanle.common.constant.Routes;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.run.dto.RunRequestDto;
import vn.thanhtuanle.run.dto.RunResponseDto;

/** {@code POST /api/v1/submissions/run} (Run code design §5): held open until the worker replies or the wait is over. */
@RestController
@RequestMapping(Routes.SUBMISSIONS)
@RequiredArgsConstructor
@Slf4j
public class RunController {

    private final RunService runService;

    @PostMapping("/run")
    @Operation(summary = "Run code on the given cases; nothing is stored and nothing counts")
    public DeferredResult<ApiResponse<RunResponseDto>> run(@Valid @RequestBody RunRequestDto req) {
        log.info("Received run request for problem: {}", req.getProblemSlug());
        return runService.run(req);
    }
}
