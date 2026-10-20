package vn.thanhtuanle.submission;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;
import vn.thanhtuanle.entity.Submission;

/** The admin search's filters, each a no-op when its value is null. */
final class SubmissionSpecifications {

    private SubmissionSpecifications() {
    }

    static Specification<Submission> matching(String problemSlug, UUID userId, Integer status, LocalDate from,
                                              LocalDate to) {
        return Specification.allOf(
                problemSlug == null ? null : (Specification<Submission>) (r, q, cb) -> cb.equal(r.get("problemSlug"), problemSlug),
                userId == null ? null : (Specification<Submission>) (r, q, cb) -> cb.equal(r.get("userId"), userId),
                status == null ? null : (Specification<Submission>) (r, q, cb) -> cb.equal(r.get("status"), status),
                from == null ? null : (Specification<Submission>) (r, q, cb) ->
                        cb.greaterThanOrEqualTo(r.get("createdAt"), from.atStartOfDay()),
                to == null ? null : (Specification<Submission>) (r, q, cb) ->
                        cb.lessThan(r.get("createdAt"), to.plusDays(1).atStartOfDay()));
    }
}
