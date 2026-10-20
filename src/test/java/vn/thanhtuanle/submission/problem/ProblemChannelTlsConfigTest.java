package vn.thanhtuanle.submission.problem;

import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.autoconfigure.client.GrpcClientProperties;
import org.springframework.grpc.client.ChannelBuilderOptions;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.grpc.client.NegotiationType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.thanhtuanle.oj.common.grpc.problem.v1.GetJudgeSpecRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Under the tls profile the channel to problem-service speaks TLS with the {@code oj} bundle: it presents this service's
 * certificate and verifies problem-service's against the CA and the channel's authority (in compose,
 * {@code static://problem-service:9090}, so the SAN must name problem-service). The service token is retired: as
 * deployed, {@code oj.problems.rpc-token} is unset and no {@code x-oj-service-token} is sent. SP8's pick_first stays.
 * problem-service is a stand-in here, with the fixture {@code server} leaf (SAN localhost) and a client certificate
 * required. Test-only fixtures: src/test/resources/tls.
 */
@SpringBootTest(properties = {
        "oj.problems.rpc-token=",
        // the tls profile configures management SSL, which needs a separate management port
        "management.server.port=0"})
@ActiveProfiles({"test", "tls"})
class ProblemChannelTlsConfigTest {

    private static final Metadata.Key<String> TOKEN_HEADER =
            Metadata.Key.of("x-oj-service-token", Metadata.ASCII_STRING_MARSHALLER);

    private static Path tlsDir;
    private static Server problemService;
    /** One entry per call the stand-in received: the client certificate's subject and whether a token came along. */
    private static final List<String> calls = new CopyOnWriteArrayList<>();

    @Autowired
    GrpcClientProperties properties;
    @Autowired
    ProblemCatalog problemCatalog;
    @Autowired
    GrpcChannelFactory channels;

    @DynamicPropertySource
    static void problemServiceOverTls(DynamicPropertyRegistry registry) throws IOException {
        tlsDir = Files.createTempDirectory("oj-tls");
        for (Map.Entry<String, String> f : Map.of("submission-service.crt", "tls.crt", "submission-service.key",
                "tls.key", "ca.crt", "ca.crt").entrySet()) {
            try (InputStream in = fixture(f.getKey())) {
                Files.copy(in, tlsDir.resolve(f.getValue()));
            }
        }
        TlsServerCredentials.Builder credentials = TlsServerCredentials.newBuilder()
                .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE);
        try (InputStream crt = fixture("server.crt"); InputStream key = fixture("server.key");
                InputStream ca = fixture("ca.crt")) {
            credentials.keyManager(crt, key).trustManager(ca);
        }
        problemService = Grpc.newServerBuilderForPort(0, credentials.build())
                .addService(ServerInterceptors.intercept(new StandInProblemService(), new RecordCaller()))
                .build()
                .start();
        registry.add("OJ_TLS_DIR", tlsDir::toString);
        registry.add("spring.grpc.client.channels.problem-service.address",
                () -> "static://localhost:" + problemService.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        problemService.shutdownNow();
        try (var files = Files.walk(tlsDir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @BeforeEach
    void forgetCalls() {
        calls.clear();
    }

    @Test
    void theProblemServiceChannelUsesTheOjBundleOverTls() {
        GrpcClientProperties.ChannelConfig channel = properties.getChannels().get("problem-service");

        assertThat(channel.getSsl().getBundle()).isEqualTo("oj");
        assertThat(channel.getSsl().isEnabled()).isTrue();
        assertThat(channel.getNegotiationType()).isEqualTo(NegotiationType.TLS);
        assertThat(channel.getDefaultLoadBalancingPolicy()).isEqualTo("pick_first");
    }

    @Test
    void callsPresentTheServiceCertificateAndNoToken() {
        JudgeSpec spec = problemCatalog.judgeSpec("two-sum", false);

        assertThat(spec.problemSlug()).isEqualTo("two-sum");
        assertThat(calls).containsExactly("CN=submission-service token=false");
    }

    @Test
    void aServerCertificateThatDoesNotNameTheAuthorityIsRefused() {
        // The stand-in's certificate names localhost and 127.0.0.1, not problem-service.
        ManagedChannel channel = channels.createChannel("problem-service", ChannelBuilderOptions.defaults()
                .withCustomizer((target, builder) -> builder.overrideAuthority("problem-service")));
        try {
            StatusRuntimeException refused = catchThrowableOfType(StatusRuntimeException.class,
                    () -> ProblemInternalGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                            .getJudgeSpec(GetJudgeSpecRequest.newBuilder().setProblemSlug("two-sum").build()));

            assertThat(refused).isNotNull();
            assertThat(refused.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
            assertThat(causeChain(refused)).contains("No subject alternative DNS name matching problem-service");
            assertThat(calls).isEmpty();
        } finally {
            channel.shutdownNow();
        }
    }

    private static String causeChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(t).append('\n');
        }
        return chain.toString();
    }

    private static InputStream fixture(String name) {
        InputStream in = ProblemChannelTlsConfigTest.class.getResourceAsStream("/tls/" + name);
        if (in == null) {
            throw new IllegalStateException("test fixture missing from the classpath: /tls/" + name
                    + " (regenerate with judge-deployment/pki/pki.sh fixtures)");
        }
        return in;
    }

    private static final class StandInProblemService extends ProblemInternalGrpc.ProblemInternalImplBase {
        @Override
        public void getJudgeSpec(GetJudgeSpecRequest request,
                StreamObserver<vn.thanhtuanle.oj.common.grpc.problem.v1.JudgeSpec> responseObserver) {
            responseObserver.onNext(vn.thanhtuanle.oj.common.grpc.problem.v1.JudgeSpec.newBuilder()
                    .setProblemId(UUID.randomUUID().toString())
                    .setProblemSlug(request.getProblemSlug())
                    .setTimeLimitMs(1000)
                    .setMemoryLimitMb(256)
                    .setTestCaseVersion("f7837fd99ca5")
                    .build());
            responseObserver.onCompleted();
        }
    }

    private static final class RecordCaller implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            calls.add(subject(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION)) + " token="
                    + headers.containsKey(TOKEN_HEADER));
            return next.startCall(call, headers);
        }

        private static String subject(SSLSession session) {
            try {
                return ((X509Certificate) session.getPeerCertificates()[0]).getSubjectX500Principal().getName();
            } catch (SSLPeerUnverifiedException | RuntimeException e) {
                return "<none>";
            }
        }
    }
}
