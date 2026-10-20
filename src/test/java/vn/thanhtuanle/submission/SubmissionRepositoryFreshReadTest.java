package vn.thanhtuanle.submission;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.language.LanguageRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Hibernate first-level-cache semantics that {@code SubmissionService.streamVerdict}'s
 * replay relies on — semantics a Mockito test cannot express:
 *
 * Within one open persistence context, a repeated {@code findById} does NOT re-read the
 * database: it returns the already-managed instance, so a status committed by another
 * transaction in the meantime is invisible to it. A JPQL scalar select, by contrast,
 * always hits the database and returns the committed value directly, bypassing the
 * persistence context, which is why streamVerdict decides on
 * {@link SubmissionRepository#findStatusById}. Outside a transaction, though, every
 * repository read has a persistence context of its own, so an entity read made after
 * another transaction's commit sees it: streamVerdict, which no transaction spans, replays
 * such a read.
 */
@SpringBootTest
@ActiveProfiles("test")
class SubmissionRepositoryFreshReadTest {

    @Autowired SubmissionRepository submissionRepository;
    @Autowired LanguageRepository languageRepository;
    @Autowired PlatformTransactionManager transactionManager;
    // Shared transactional proxy: inside a TransactionTemplate callback it delegates to the
    // persistence context bound to that transaction.
    @Autowired EntityManager entityManager;

    private UUID submissionId;
    private UUID languageId;
    private UUID userId;

    @BeforeEach
    void createPendingSubmission() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            userId = UUID.randomUUID();
            Language language = languageRepository.save(Language.builder()
                    .name("fresh-read-lang").identifier("fresh-read-" + UUID.randomUUID()).build());
            Submission submission = submissionRepository.save(Submission.builder()
                    .sourceCode("print(1)")
                    .status(SubmissionResult.PENDING.getValue())
                    .time(0).memory(0L)
                    .problemId(UUID.randomUUID()).problemSlug("fresh-read-problem")
                    .language(language).userId(userId)
                    .build());
            languageId = language.getId();
            submissionId = submission.getId();
        });
    }

    @AfterEach
    void cleanUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            submissionRepository.deleteById(submissionId);
            languageRepository.deleteById(languageId);
        });
    }

    @Test
    void scalarStatusQuerySeesCommittedUpdate_whileFindByIdServesStaleManagedInstance() {
        // One reader transaction: what streamVerdict would see if a transaction spanned it.
        TransactionTemplate readerTx = new TransactionTemplate(transactionManager);
        readerTx.setReadOnly(true);

        readerTx.executeWithoutResult(tx -> {
            // tx1: load the submission — it becomes managed in tx1's persistence context.
            Submission managed = submissionRepository.findById(submissionId).orElseThrow();
            assertThat(managed.getStatus()).isEqualTo(SubmissionResult.PENDING.getValue());

            // tx2 (separate, committed while tx1 is still open): the verdict lands.
            TransactionTemplate writerTx = new TransactionTemplate(transactionManager);
            writerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            writerTx.executeWithoutResult(tx2 -> {
                Submission other = submissionRepository.findById(submissionId).orElseThrow();
                other.setStatus(SubmissionResult.ACCEPTED.getValue());
                submissionRepository.save(other);
            });

            // Back in tx1: findById is a no-op re-read — it serves the SAME stale managed
            // instance from the first-level cache without touching the database. This is why
            // streamVerdict's old "fresh" re-read could never see the verdict.
            Submission reread = submissionRepository.findById(submissionId).orElseThrow();
            assertThat(reread).isSameAs(managed);
            assertThat(reread.getStatus())
                    .as("findById within the same persistence context must serve the stale L1 copy")
                    .isEqualTo(SubmissionResult.PENDING.getValue());

            // The scalar projection bypasses the persistence context and sees tx2's commit.
            assertThat(submissionRepository.findStatusById(submissionId))
                    .as("scalar JPQL select must hit the database and see the committed value")
                    .isEqualTo(SubmissionResult.ACCEPTED.getValue());

            // refresh() forces a DB re-read into the managed instance — the replay payload
            // streamVerdict maps after the scalar check is therefore the committed state.
            entityManager.refresh(managed);
            assertThat(managed.getStatus()).isEqualTo(SubmissionResult.ACCEPTED.getValue());
        });
    }

    @Test
    void outsideATransactionEachEntityReadSeesTheCommittedRow() {
        Submission loaded = submissionRepository.findById(submissionId).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(SubmissionResult.PENDING.getValue());

        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                submissionRepository.findById(submissionId).orElseThrow()
                        .setStatus(SubmissionResult.ACCEPTED.getValue()));

        Submission replay = submissionRepository.findWithLanguageById(submissionId).orElseThrow();
        assertThat(replay).isNotSameAs(loaded);
        assertThat(replay.getStatus()).isEqualTo(SubmissionResult.ACCEPTED.getValue());
        assertThat(replay.getLanguage().getName()).as("loaded with the row, so it maps outside a transaction")
                .isEqualTo("fresh-read-lang");
    }
}
