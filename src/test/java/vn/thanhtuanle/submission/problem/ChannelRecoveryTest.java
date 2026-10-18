package vn.thanhtuanle.submission.problem;

import io.grpc.ConnectivityState;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Calls are refused at once while the channel recovers from a failure (sub-project 8 G2). */
class ChannelRecoveryTest {

    @Test
    void aChannelThatNeverFailedAdmitsCallsWhileItConnects() {
        StateDrivenChannel channel = new StateDrivenChannel(ConnectivityState.IDLE);
        ChannelRecovery recovery = new ChannelRecovery(channel);
        channel.moveTo(ConnectivityState.CONNECTING);

        assertThatCode(recovery::admit).doesNotThrowAnyException();
    }

    @Test
    void afterAFailureACallIsRefusedAtOnceWhileTheChannelReconnects() {
        StateDrivenChannel channel = new StateDrivenChannel(ConnectivityState.READY);
        ChannelRecovery recovery = new ChannelRecovery(channel);
        channel.moveTo(ConnectivityState.TRANSIENT_FAILURE);
        channel.moveTo(ConnectivityState.CONNECTING);

        assertThatThrownBy(recovery::admit).isInstanceOfSatisfying(StatusRuntimeException.class,
                e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
        assertThat(channel.connectionRequests).isPositive();
    }

    @Test
    void afterAFailureAnIdleChannelIsStillRefusedAndAskedToConnect() {
        StateDrivenChannel channel = new StateDrivenChannel(ConnectivityState.READY);
        ChannelRecovery recovery = new ChannelRecovery(channel);
        channel.moveTo(ConnectivityState.TRANSIENT_FAILURE);
        channel.moveTo(ConnectivityState.IDLE);

        assertThatThrownBy(recovery::admit).isInstanceOf(StatusRuntimeException.class);
        assertThat(channel.connectionRequests).isPositive();
    }

    @Test
    void onceReadyAgainCallsAreAdmitted() {
        StateDrivenChannel channel = new StateDrivenChannel(ConnectivityState.READY);
        ChannelRecovery recovery = new ChannelRecovery(channel);
        channel.moveTo(ConnectivityState.TRANSIENT_FAILURE);
        channel.moveTo(ConnectivityState.CONNECTING);
        channel.moveTo(ConnectivityState.READY);

        assertThatCode(recovery::admit).doesNotThrowAnyException();
    }
}
