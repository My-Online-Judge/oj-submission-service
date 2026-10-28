package vn.thanhtuanle.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.context.request.async.DeferredResult;
import vn.thanhtuanle.common.exception.ErrorCode;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.judge.CheckerLanguages;
import vn.thanhtuanle.judge.JudgeService;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.messaging.event.RunRequestedEvent;
import vn.thanhtuanle.messaging.event.RunTestCase;
import vn.thanhtuanle.messaging.event.SubmissionRequestedEvent;
import vn.thanhtuanle.oj.common.security.CurrentUser;
import vn.thanhtuanle.oj.common.web.error.AppException;
import vn.thanhtuanle.oj.common.web.error.RateLimitedException;
import vn.thanhtuanle.oj.common.web.payload.ApiResponse;
import vn.thanhtuanle.run.dto.RunCaseDto;
import vn.thanhtuanle.run.dto.RunRequestDto;
import vn.thanhtuanle.run.dto.RunResponseDto;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.submission.problem.ProblemNotFoundException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The request path of Run code design §5–§6: the order of the gates, what is published, and what frees a slot. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final UUID PROBLEM_ID = UUID.randomUUID();

    @Mock JudgeService judgeService;
    @Mock ProblemCatalog problemCatalog;
    @Mock LanguageRepository languageRepository;
    @Mock CheckerLanguages checkerLanguages;
    @Mock CurrentUser currentUser;
    @Mock RunRateLimiter rateLimiter;
    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    RunProperties props;
    SimpleMeterRegistry meters;
    RunMetrics metrics;
    RunRegistry registry;
    RunService service;

    private final UUID userId = UUID.randomUUID();
    private RunRequestDto req;

    @BeforeEach
    void setUp() {
        props = new RunProperties();
        meters = new SimpleMeterRegistry();
        metrics = new RunMetrics(meters);
        registry = new RunRegistry(props, metrics);
        service = new RunService(judgeService, problemCatalog, languageRepository, checkerLanguages, currentUser,
                new RunRequestValidator(props), rateLimiter, registry, props, metrics, kafkaTemplate,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        req = RunRequestDto.builder().problemSlug("a-plus-b").languageIdentifier("cpp").sourceCode("int main(){}")
                .cases(List.of(new RunCaseDto("4\n", "1\n"), new RunCaseDto("2\n", null))).build();

        when(problemCatalog.judgeSpec(eq("a-plus-b"), eq(false), isNull(), any()))
                .thenReturn(new JudgeSpec(PROBLEM_ID, "a-plus-b", 1000, 256L, "v1"));
        when(languageRepository.findByIdentifier("cpp")).thenReturn(Optional.of(new Language()));
        when(currentUser.id()).thenReturn(userId);
        when(judgeService.buildRequestedEvent(anyString(), anyString(), any(), any(), eq(0), any()))
                .thenReturn(SubmissionRequestedEvent.builder().src("int main(){}").output(true).maxCpuTime(1000)
                        .maxMemory(256L * 1024 * 1024).build());
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenAnswer(inv -> CompletableFuture.completedFuture((SendResult<String, Object>) null));
    }

    private RunRequestedEvent published() {
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("run.requested"), key.capture(), value.capture());
        RunRequestedEvent event = (RunRequestedEvent) value.getValue();
        assertThat(event.getRunId()).isEqualTo(key.getValue());
        return event;
    }

    @Test
    void aRunIsPublishedWithItsDeadlineAndInlineCases() {
        service.run(req);

        RunRequestedEvent event = published();
        assertThat(event.getExpiresAt()).isEqualTo(NOW.toEpochMilli() + 45_000);
        assertThat(event.getTestCase()).containsExactly(new RunTestCase("4\n", "1\n"), new RunTestCase("2\n", ""));
        assertThat(event.getOutput()).isTrue();
        assertThat(registry.inflight()).isEqualTo(1);
    }

    @Test
    void theRequestNamesTheInstanceThatAskedSoOnlyItCompletesTheRun() {
        props.setInstanceId("inst-a");

        service.run(req);

        assertThat(published().getReplyTo()).isEqualTo("inst-a");
    }

    @Test
    void aCheckerProblemSendsCasesWithoutOutput() {
        when(problemCatalog.judgeSpec(eq("a-plus-b"), eq(false), isNull(), any())).thenReturn(
                new JudgeSpec(PROBLEM_ID, "a-plus-b", 1000, 256L, "v1", null, "int main(){return 0;}", "ab12"));
        when(checkerLanguages.forSpec(any())).thenReturn(new Language());

        service.run(req);

        assertThat(published().getTestCase()).extracting(RunTestCase::output).containsOnlyNulls();
    }

    @Test
    void anUnknownProblemBurnsNoCooldownAndRegistersNothing() {
        when(problemCatalog.judgeSpec(eq("a-plus-b"), eq(false), isNull(), any())).thenThrow(new ProblemNotFoundException());

        assertThatThrownBy(() -> service.run(req)).isInstanceOf(ProblemNotFoundException.class);

        verify(rateLimiter, never()).acquire(any());
        assertThat(registry.inflight()).isZero();
    }

    @Test
    void aCooldownRefusalRegistersNothing() {
        doThrow(new RateLimitedException(ErrorCode.RUN_RATE_LIMITED, 2)).when(rateLimiter).acquire(userId);

        assertThatThrownBy(() -> service.run(req)).isInstanceOf(RateLimitedException.class);

        assertThat(registry.inflight()).isZero();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void aBusyRegistryReleasesTheCooldown() {
        for (int i = 0; i < 20; i++) {
            registry.register(UUID.randomUUID(), List.of(true), false, new DeferredResult<>());
        }

        assertThatThrownBy(() -> service.run(req)).isInstanceOf(RateLimitedException.class)
                .satisfies(ex -> assertThat(((RateLimitedException) ex).getErrorCode()).isEqualTo(ErrorCode.RUN_BUSY));

        verify(rateLimiter).release(userId);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void aPublishFailureFreesTheSlotAndAnswers500() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenAnswer(inv -> CompletableFuture.failedFuture(new KafkaException("broker down")));

        DeferredResult<ApiResponse<RunResponseDto>> result = service.run(req);

        assertThat(result.getResult()).isInstanceOf(KafkaException.class);
        assertThat(registry.inflight()).isZero();
        verify(rateLimiter).release(userId);
    }

    @Test
    void aTimeoutAnswers504AndFreesTheSlot() {
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenAnswer(inv -> new CompletableFuture<>());
        DeferredResult<ApiResponse<RunResponseDto>> result = service.run(req);
        UUID runId = UUID.fromString(published().getRunId());

        service.expire(runId, result);

        assertThat(result.getResult()).isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.RUN_TIMED_OUT));
        assertThat(registry.inflight()).isZero();
        assertThat(meters.counter("oj.run.timed_out").count()).isEqualTo(1.0);

        service.expire(runId, result);   // the registry no longer knows it: nothing happens
        assertThat(meters.counter("oj.run.timed_out").count()).isEqualTo(1.0);
    }

    @Test
    void aRequestOverTheEventCapIs400AndLeavesNothingBehind() {
        props.setMaxEventBytes(100);

        assertThatThrownBy(() -> service.run(req)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Run request is too large");

        assertThat(registry.inflight()).isZero();
        verify(rateLimiter).release(userId);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void theSubmitterIsNamedOnTheJudgeSpecCall() {
        service.run(req);

        verify(problemCatalog).judgeSpec("a-plus-b", false, null, userId);
    }
}
