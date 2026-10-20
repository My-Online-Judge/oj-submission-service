package vn.thanhtuanle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.thanhtuanle.judge.dto.JudgeResultDto;

import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "t_submissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class Submission extends BaseEntity {
    @Column(columnDefinition = "TEXT")
    private String sourceCode;

    private int status;

    private Integer result;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Integer cpuTime;

    private Integer time;

    private Long memory;

    private Boolean shareSubmission;

    // The problem this was submitted to. Problems move to problem-service (sub-project 2b), so this is a
    // plain id, not a relationship (V16 dropped the foreign key). The slug is copied at submit time — a
    // slug never changes and is never reused — so lists by problem need nothing from problem-service.
    @Column(name = "problem_id", nullable = false)
    private UUID problemId;

    @Column(name = "problem_slug", nullable = false)
    private String problemSlug;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "language_id", referencedColumnName = "id", nullable = false)
    private Language language;

    // The submitter. Users live in identity-service's database, so this is a plain id, not a
    // relationship (V15 dropped the foreign key to the old t_users).
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // Raw per-testcase results from the judge, stored verbatim. Read ONLY through
    // SubmissionDetailAssembler — it carries hidden test data that must be filtered.
    // No columnDefinition: the test profile is H2, which has no `jsonb`.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    private List<JudgeResultDto> details;

    // When a worker announced it began judging (submission.started), in the service's zone like createdAt.
    @Column(name = "judging_started_at")
    private LocalDateTime judgingStartedAt;

    @Column(name = "rejudge_count", nullable = false)
    private int rejudgeCount;

    // The contest this was submitted in (problem-service's id), null for practice. Kept through rejudges.
    @Column(name = "contest_id")
    private UUID contestId;
}
