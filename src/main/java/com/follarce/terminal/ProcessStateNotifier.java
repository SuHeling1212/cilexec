package com.follarce.terminal;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory wake-up hint for host terminals waiting on a durably committed process state.
 *
 * <p>The database remains authoritative. Missing a signal can delay only the next fallback
 * read; it can never change process state or recovery semantics.
 */
public final class ProcessStateNotifier {
    private final ConcurrentHashMap<Key, State> states = new ConcurrentHashMap<>();

    /** Returns a monotonic version used to close the query/wait lost-wake race. */
    public long version(UUID ownerId, UUID processUid) {
        State state = states.computeIfAbsent(new Key(ownerId, processUid), ignored -> new State());
        state.lock.lock();
        try {
            return state.version;
        } finally {
            state.lock.unlock();
        }
    }

    /** Wakes every terminal currently waiting for this process after a successful commit. */
    public void signal(UUID ownerId, UUID processUid) {
        // Most processes have no attached host waiter. A post-commit signal must not turn
        // every process ever executed into an entry in this disposable optimization map.
        State state = states.get(new Key(ownerId, processUid));
        if (state == null) return;
        state.lock.lock();
        try {
            state.version++;
            state.changed.signalAll();
        } finally {
            state.lock.unlock();
        }
    }

    /**
     * Waits until the observed version changes or the fallback deadline expires.
     * Returns false when interrupted or timed out.
     */
    public boolean awaitChange(UUID ownerId, UUID processUid, long observed, Duration fallback) {
        if (fallback.isNegative() || fallback.isZero()) {
            throw new IllegalArgumentException("fallback must be positive");
        }
        State state = states.computeIfAbsent(new Key(ownerId, processUid), ignored -> new State());
        long remaining = fallback.toNanos();
        state.lock.lock();
        try {
            while (state.version == observed && remaining > 0) {
                try {
                    remaining = state.changed.awaitNanos(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return state.version != observed;
        } finally {
            state.lock.unlock();
        }
    }

    /** Removes a terminal process hint after its durable terminal state has been observed. */
    public void forget(UUID ownerId, UUID processUid) {
        states.remove(new Key(ownerId, processUid));
    }

    int trackedProcesses() {
        return states.size();
    }

    private record Key(UUID ownerId, UUID processUid) {
        private Key {
            java.util.Objects.requireNonNull(ownerId, "ownerId");
            java.util.Objects.requireNonNull(processUid, "processUid");
        }
    }

    private static final class State {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private long version;
    }
}
