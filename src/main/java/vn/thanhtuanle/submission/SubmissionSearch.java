package vn.thanhtuanle.submission;

import java.time.LocalDate;

/**
 * The admin search's filters; a null filter is not applied. {@code result} is a status code, {@code from} and
 * {@code to} are both inclusive dates.
 */
public record SubmissionSearch(String problemSlug, String username, Integer result, LocalDate from, LocalDate to,
                               int page, int size) {
}
