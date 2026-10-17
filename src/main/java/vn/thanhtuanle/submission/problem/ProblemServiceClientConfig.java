package vn.thanhtuanle.submission.problem;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.retry.RetryRegistry;
import io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelBuilderCustomizer;
import org.springframework.grpc.client.GrpcChannelFactory;
import vn.thanhtuanle.oj.common.grpc.ServiceTokenClientInterceptor;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;

import java.time.Duration;

/**
 * Wires {@link ProblemCatalog} to problem-service: the channel {@code problem-service}
 * ({@code spring.grpc.client.channels.problem-service.*}), the service token, the deadline per attempt and
 * the shared retry and circuit breaker, whose state is exported as {@code resilience4j_circuitbreaker_state}.
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

    @Bean
    public ProblemCatalog problemCatalog(GrpcChannelFactory channels, CircuitBreakerRegistry circuitBreakers,
            @Value("${oj.problems.rpc-token:}") String token,
            @Value("${oj.problems.deadline:2s}") Duration deadline) {
        ProblemInternalGrpc.ProblemInternalBlockingStub stub = ProblemInternalGrpc
                .newBlockingStub(channels.createChannel(ProblemServiceResilience.NAME))
                .withInterceptors(new ServiceTokenClientInterceptor(token));
        return new GrpcProblemCatalog(stub, circuitBreakers.circuitBreaker(ProblemServiceResilience.NAME),
                RetryRegistry.of(ProblemServiceResilience.retryConfig()).retry(ProblemServiceResilience.NAME), deadline);
    }
}
