package vn.thanhtuanle.submission.problem;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.retry.RetryRegistry;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelBuilderCustomizer;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.util.StringUtils;
import vn.thanhtuanle.oj.common.grpc.ServiceTokenClientInterceptor;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;

import java.time.Duration;

/**
 * Wires {@link ProblemCatalog} to problem-service: the channel {@code problem-service}
 * ({@code spring.grpc.client.channels.problem-service.*}; mutual TLS under the tls profile), the service token when
 * one is set, the deadline per attempt, and the retries and circuit breakers ({@link ProblemServiceResilience}), whose
 * states are exported as {@code resilience4j_circuitbreaker_state}.
 */
@Configuration
public class ProblemServiceClientConfig {

    @Bean
    public CircuitBreakerRegistry problemServiceCircuitBreakers(MeterRegistry meters) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(ProblemServiceResilience.circuitBreakerConfig());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry;
    }

    /**
     * A connect to problem-service gives up after {@code oj.problems.connect-timeout} (sub-project 7 §3.1 F2). Netty's
     * default is 30 s, and a connect to a stopped container's address on the Docker bridge gets no answer at all, so
     * a call waited out its 2 s deadline (DEADLINE_EXCEEDED, not retried) instead of failing UNAVAILABLE.
     */
    @Bean
    public GrpcChannelBuilderCustomizer<NettyChannelBuilder> problemServiceConnectTimeout(
            @Value("${oj.problems.connect-timeout:500ms}") Duration connectTimeout) {
        return (authority, builder) -> builder.withOption(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                (int) connectTimeout.toMillis());
    }

    /** The one channel to problem-service: the catalog calls it and {@link ChannelRecovery} watches it. */
    @Bean
    public ManagedChannel problemServiceChannel(GrpcChannelFactory channels) {
        return channels.createChannel(ProblemServiceResilience.NAME);
    }

    @Bean
    public ChannelRecovery problemServiceRecovery(ManagedChannel problemServiceChannel) {
        return new ChannelRecovery(problemServiceChannel);
    }

    @Bean
    public ProblemCatalog problemCatalog(ManagedChannel problemServiceChannel, ChannelRecovery problemServiceRecovery,
            CircuitBreakerRegistry circuitBreakers,
            @Value("${oj.problems.rpc-token:}") String token,
            @Value("${oj.problems.deadline:2s}") Duration deadline) {
        ProblemInternalGrpc.ProblemInternalBlockingStub stub = ProblemInternalGrpc.newBlockingStub(problemServiceChannel);
        if (StringUtils.hasText(token)) {
            stub = stub.withInterceptors(new ServiceTokenClientInterceptor(token));
        }
        RetryRegistry retries = RetryRegistry.of(ProblemServiceResilience.retryConfig());
        return new GrpcProblemCatalog(stub,
                circuitBreakers.circuitBreaker(ProblemServiceResilience.NAME),
                retries.retry(ProblemServiceResilience.NAME),
                circuitBreakers.circuitBreaker(ProblemServiceResilience.VISIBILITY_NAME),
                retries.retry(ProblemServiceResilience.VISIBILITY_NAME),
                deadline, problemServiceRecovery);
    }

    /** Warms the channel in the background once the app is ready (see {@link ProblemChannelWarmUp}). */
    @Bean
    public ProblemChannelWarmUp problemChannelWarmUp(ManagedChannel problemServiceChannel,
            @Value("${oj.problems.rpc-token:}") String token,
            @Value("${oj.problems.warm-up-deadline:10s}") Duration deadline) {
        return new ProblemChannelWarmUp(problemServiceChannel, token, deadline, task -> {
            Thread thread = new Thread(task, "problem-channel-warm-up");
            thread.setDaemon(true);
            thread.start();
        });
    }
}
