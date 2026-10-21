package vn.thanhtuanle.submission.problem;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vn.thanhtuanle.oj.common.grpc.ServiceTokenServerInterceptor;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ListVisibleSlugsRequest;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ListVisibleSlugsResponse;
import vn.thanhtuanle.oj.common.grpc.problem.v1.ProblemInternalGrpc;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** The first call to problem-service meets a cold channel; the warm-up pays that once, off the request path. */
class ProblemChannelWarmUpTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void stop() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    @Test
    void warmsTheChannelOnceWhenTheAppIsReady() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .addService(ServerInterceptors.intercept(new ProblemInternalGrpc.ProblemInternalImplBase() {
                    @Override
                    public void listVisibleSlugs(ListVisibleSlugsRequest request,
                            StreamObserver<ListVisibleSlugsResponse> observer) {
                        calls.incrementAndGet();
                        observer.onNext(ListVisibleSlugsResponse.getDefaultInstance());
                        observer.onCompleted();
                    }
                }, new ServiceTokenServerInterceptor(TOKEN))).build().start();
        channel = InProcessChannelBuilder.forName(name).build();

        new ProblemChannelWarmUp(channel, TOKEN, Duration.ofSeconds(10), Runnable::run).onReady(null);

        assertThat(calls).hasValue(1);
    }

    @Test
    void neverFailsStartupWhenProblemServiceIsDown() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        server.shutdownNow().awaitTermination();

        assertThatCode(() -> new ProblemChannelWarmUp(channel, TOKEN, Duration.ofSeconds(10), Runnable::run)
                .onReady(null)).doesNotThrowAnyException();
    }

    @Test
    void runsOnABackgroundThreadByDefault() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        java.util.concurrent.atomic.AtomicReference<Thread> ran = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        ProblemChannelWarmUp warmUp = new ProblemChannelWarmUp(channel, TOKEN, Duration.ofSeconds(10), task -> {
            Thread t = new Thread(() -> { ran.set(Thread.currentThread()); task.run(); done.countDown(); });
            t.start();
        });

        warmUp.onReady(null);

        assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get()).isNotSameAs(Thread.currentThread());
    }
}
