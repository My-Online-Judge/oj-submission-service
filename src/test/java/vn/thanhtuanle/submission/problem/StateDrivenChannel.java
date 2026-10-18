package vn.thanhtuanle.submission.problem;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ManagedChannel} whose connectivity state the test sets. Like grpc-java, it fires each
 * {@link #notifyWhenStateChanged} callback once, on the next change; it counts {@code getState(true)} calls. It
 * carries no calls.
 */
final class StateDrivenChannel extends ManagedChannel {

    private ConnectivityState state;
    private final List<Runnable> callbacks = new ArrayList<>();
    int connectionRequests;

    StateDrivenChannel(ConnectivityState initial) {
        this.state = initial;
    }

    void moveTo(ConnectivityState next) {
        state = next;
        List<Runnable> due = new ArrayList<>(callbacks);
        callbacks.clear();
        due.forEach(Runnable::run);
    }

    @Override
    public ConnectivityState getState(boolean requestConnection) {
        if (requestConnection) {
            connectionRequests++;
        }
        return state;
    }

    @Override
    public void notifyWhenStateChanged(ConnectivityState source, Runnable callback) {
        if (source != state) {
            callback.run();
        } else {
            callbacks.add(callback);
        }
    }

    @Override
    public ManagedChannel shutdown() {
        return this;
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public ManagedChannel shutdownNow() {
        return this;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }

    @Override
    public <Q, R> ClientCall<Q, R> newCall(MethodDescriptor<Q, R> method, CallOptions options) {
        throw new UnsupportedOperationException("StateDrivenChannel carries no calls");
    }

    @Override
    public String authority() {
        return "problem-service";
    }
}
