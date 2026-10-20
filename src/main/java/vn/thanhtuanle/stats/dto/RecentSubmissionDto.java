package vn.thanhtuanle.stats.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** One recent submission of any status; {@code result} is its verdict code and {@code language} its display name. */
public record RecentSubmissionDto(UUID id, String problemSlug, int result, String language, LocalDateTime createdAt) {
}
