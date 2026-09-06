package com.steven.assets.externalmaterials.client;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Version-owned failure de-duplication for a cancellable stream lifecycle.
 * A stale version can never replace or reopen a later lifecycle's interval.
 */
final class LifecycleFailureInterval {
    private record State(long version, boolean open) { }

    private final AtomicReference<State> state = new AtomicReference<>(new State(Long.MIN_VALUE, false));

    /** Claims this lifecycle's first currently-open failure. */
    boolean claim(long version) {
        while (true) {
            State current = state.get();
            if (current.version() > version || (current.version() == version && current.open())) return false;
            if (state.compareAndSet(current, new State(version, true))) return true;
        }
    }

    /** A valid event lets only the owning lifecycle record a later new failure. */
    void reset(long version) {
        while (true) {
            State current = state.get();
            if (current.version() != version || !current.open()) return;
            if (state.compareAndSet(current, new State(version, false))) return;
        }
    }
}
