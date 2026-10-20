package vn.thanhtuanle.stats.dto;

import java.util.List;

/** A user's public statistics. The four counts use finished submissions only; there is never any source code. */
public record UserStatsDto(long solvedCount, long attemptedCount, long submissionCount, long acceptedCount,
                           List<String> solvedProblems, List<RecentSubmissionDto> recent) {
}
