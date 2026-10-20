package vn.thanhtuanle.submission;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import vn.thanhtuanle.common.constant.Routes;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.submission.dto.SubmissionListItemDto;
import vn.thanhtuanle.submission.dto.SubmissionRequestDto;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;

@RestController
@RequestMapping(Routes.SUBMISSIONS)
@RequiredArgsConstructor
@Slf4j
public class SubmissionController {

    private final SubmissionService submissionService;
    private final RejudgeService rejudgeService;

    @PostMapping
    @Operation(summary = "Submit a solution for a problem")
    public ApiResponse<SubmissionResponseDto> submit(@Valid @RequestBody SubmissionRequestDto submissionDto) {
        log.info("Received submission request for problem: {}", submissionDto.getProblemSlug());
        return ApiResponse.success(submissionService.submit(submissionDto));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('submission:read_any')")
    @Operation(summary = "Search every submission by problem, user, verdict and date (admin only)")
    public ApiResponse<List<SubmissionListItemDto>> search(
            @RequestParam(required = false) String problemSlug,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer result,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(submissionService.search(
                new SubmissionSearch(problemSlug, username, result, from, to, page, size)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single submission by id")
    public ApiResponse<SubmissionResponseDto> getSubmissionById(@PathVariable String id) {
        log.info("Fetching submission by id: {}", id);
        return ApiResponse.success(submissionService.getSubmissionById(id));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get submissions by user ID")
    public ApiResponse<List<SubmissionResponseDto>> getSubmissionsByUserId(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("Fetching submissions for user ID: {}", userId);
        return ApiResponse.success(submissionService.getSubmissionsByUser(userId, page, size));
    }

    @GetMapping("/user/{userId}/problem/{problemSlug}")
    @Operation(summary = "Get submissions by user ID and problem slug")
    public ApiResponse<List<SubmissionResponseDto>> getSubmissionsByUserAndProblem(
            @PathVariable String userId,
            @PathVariable String problemSlug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("Fetching submissions for user ID: {} and problem slug: {}", userId, problemSlug);
        return ApiResponse.success(submissionService.getSubmissionsByUserAndProblem(userId, problemSlug, page, size));
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream the verdict for a submission via SSE")
    public SseEmitter stream(@PathVariable String id) {
        log.info("SSE subscribe for submission {}", id);
        return submissionService.streamVerdict(id);
    }

    @PostMapping("/{id}/rejudge")
    @PreAuthorize("hasAuthority('submission:rejudge')")
    @Operation(summary = "Judge a finished submission again against the problem's current test data (admin only)")
    public ApiResponse<SubmissionResponseDto> rejudge(@PathVariable String id) {
        log.info("Rejudge requested for submission {}", id);
        return ApiResponse.success(rejudgeService.rejudge(UUID.fromString(id)));
    }
}
