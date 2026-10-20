package vn.thanhtuanle.submission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.messaging.outbox.OutboxRepository;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;
import vn.thanhtuanle.support.PostgresTest;
import vn.thanhtuanle.submission.dto.SubmissionRequestDto;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.submission.problem.ProblemCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A submit held its database transaction (and a pooled connection) across the gRPC call to the problem service and
 * the Redis cooldown call. When either stalled, the pool drained. Only the row and its outbox message are a
 * transaction now.
 */
class SubmitTransactionBoundaryTest extends PostgresTest {

    @Autowired SubmissionService submissionService;
    @Autowired SubmissionRepository submissionRepository;
    @Autowired OutboxRepository outboxRepository;
    @Autowired LanguageRepository languageRepository;

    @MockitoBean ProblemCatalog problemCatalog;
    @MockitoBean SubmissionRateLimiter submissionRateLimiter;

    private final UUID userId = UUID.randomUUID();
    private final String identifier = "boundary-" + UUID.randomUUID();

    @BeforeEach
    void signIn() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .claim(OjJwtDecoders.UID_CLAIM, userId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        Language language = new Language();
        language.setIdentifier(identifier);
        language.setName("boundary");
        languageRepository.save(language);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noTransactionIsOpenDuringTheGrpcAndRedisCalls() {
        List<Boolean> seen = new ArrayList<>();
        when(problemCatalog.judgeSpec("a-plus-b")).thenAnswer(inv -> {
            seen.add(TransactionSynchronizationManager.isActualTransactionActive());
            return new JudgeSpec(UUID.randomUUID(), "a-plus-b", 1000, 256L, "abc123def456");
        });
        doAnswer(inv -> {
            seen.add(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(submissionRateLimiter).acquire(any());
        long outboxBefore = outboxRepository.count();

        var dto = submissionService.submit(SubmissionRequestDto.builder()
                .sourceCode("int main(){}").languageIdentifier(identifier)
                .problemSlug("a-plus-b").shareSubmission(false).build());

        assertThat(seen).as("the gRPC call and the Redis call, each outside a transaction")
                .containsExactly(false, false);
        assertThat(submissionRepository.findById(dto.getId())).isPresent();
        assertThat(outboxRepository.count()).isEqualTo(outboxBefore + 1);
    }
}
