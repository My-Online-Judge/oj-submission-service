package vn.thanhtuanle.submission.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** One row of the admin search: no source and no per-test details. */
public record SubmissionListItemDto(UUID id, LocalDateTime createdAt, UUID userId, String username,
                                    String problemSlug, String language, int status, Integer cpuTime, Long memory) {
}
