package vn.thanhtuanle.submission;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.language.LanguageRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VerdictPush maps a submission on a pool thread, outside any transaction: the language the view shows must already
 * be loaded, which a plain findById (a lazy proxy) does not do.
 */
@SpringBootTest
@ActiveProfiles("test")
class SubmissionRepositoryLanguageGraphTest {

    @Autowired SubmissionRepository submissionRepository;
    @Autowired LanguageRepository languageRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private UUID submissionId;
    private UUID languageId;

    @BeforeEach
    void createSubmission() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            Language language = languageRepository.save(Language.builder()
                    .name("graph-lang").identifier("graph-" + UUID.randomUUID()).build());
            Submission submission = submissionRepository.save(Submission.builder()
                    .sourceCode("print(1)").status(SubmissionResult.ACCEPTED.getValue()).time(0).memory(0L)
                    .problemId(UUID.randomUUID()).problemSlug("graph-problem")
                    .language(language).userId(UUID.randomUUID())
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
    void theLanguageIsLoadedWithTheSubmission() {
        Submission loaded = submissionRepository.findWithLanguageById(submissionId).orElseThrow();

        assertThat(Hibernate.isInitialized(loaded.getLanguage())).isTrue();
        assertThat(loaded.getLanguage().getName()).isEqualTo("graph-lang");
        // The contrast that makes the method necessary: findById leaves a proxy that fails outside a transaction.
        assertThat(Hibernate.isInitialized(submissionRepository.findById(submissionId).orElseThrow().getLanguage())).isFalse();
    }
}
