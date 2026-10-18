package vn.thanhtuanle.submission.problem;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;

/**
 * Refuses a call at once while the channel to problem-service recovers from a failure (sub-project 8 G2). From the
 * moment the channel is seen in TRANSIENT_FAILURE until it is seen READY, it is "recovering": grpc-java would otherwise
 * hold a call in the channel while it reconnects, until the call's deadline — which is what the breaker's half-open
 * trials met while problem-service was down (REPORT §11). A refusal is UNAVAILABLE, so the breaker counts it. Each
 * refusal also asks the channel to connect, or an IDLE channel would never reconnect and nothing would close the
 * breaker. A channel that has never failed (start-up) is not recovering: its first calls wait as before. Every state
 * change is logged.
 */
@Slf4j
public final class ChannelRecovery {

    private final ManagedChannel channel;
    private volatile boolean recovering;

    public ChannelRecovery(ManagedChannel channel) {
        this.channel = channel;
        ConnectivityState now = channel.getState(false);
        recovering = now == ConnectivityState.TRANSIENT_FAILURE;
        watch(now);
    }

    /** Returns when a call may go out; throws UNAVAILABLE while the channel recovers from a failure. */
    public void admit() {
        if (recovering && channel.getState(true) != ConnectivityState.READY) {
            throw Status.UNAVAILABLE.withDescription("problem-service is reconnecting").asRuntimeException();
        }
    }

    ManagedChannel channel() {
        return channel;
    }

    private void watch(ConnectivityState seen) {
        channel.notifyWhenStateChanged(seen, () -> {
            ConnectivityState now = channel.getState(false);
            if (now == ConnectivityState.TRANSIENT_FAILURE) {
                recovering = true;
            } else if (now == ConnectivityState.READY) {
                recovering = false;
            }
            log.info("problem-service channel {} -> {}", seen, now);
            if (now != ConnectivityState.SHUTDOWN) {
                watch(now);
            }
        });
    }
}
