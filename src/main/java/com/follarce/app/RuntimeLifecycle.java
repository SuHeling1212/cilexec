package com.follarce.app;

import com.follarce.health.HealthState;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Enforces startup ordering, fencing, and bounded shutdown independently of adapters. */
public final class RuntimeLifecycle implements AutoCloseable {
    private final Hooks hooks;
    private final Duration shutdownGrace;
    private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);
    private final AtomicBoolean controlAcquired = new AtomicBoolean();
    private final AtomicBoolean bootStarted = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Object startupGate = new Object();

    public RuntimeLifecycle(Hooks hooks, Duration shutdownGrace) {
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.shutdownGrace = Objects.requireNonNull(shutdownGrace, "shutdownGrace");
        if (shutdownGrace.isNegative() || shutdownGrace.isZero()) {
            throw new IllegalArgumentException("shutdownGrace must be positive");
        }
    }

    public void start() {
        if (!state.compareAndSet(State.CREATED, State.STARTING)) {
            throw new IllegalStateException("Runtime can only be started once");
        }
        hooks.phase(HealthState.RuntimePhase.STARTING);
        try {
            int schemaVersion = startupStep(hooks::verifySchema);
            startupStep(() -> {
                hooks.acquireControl(this::fence);
                controlAcquired.set(true);
            });
            startupStep(() -> {
                hooks.beginBoot(schemaVersion);
                bootStarted.set(true);
            });
            startupStep(hooks::markRecovering);
            startupStep(() -> hooks.phase(HealthState.RuntimePhase.RECOVERING));
            startupStep(hooks::recover);
            startupStep(hooks::startHealth);
            startupStep(hooks::startScheduler);
            startupStep(hooks::startEffectWorkers);
            startupStep(hooks::startTimerLoop);
            synchronized (startupGate) {
                ensureStarting();
                hooks.markReady();
                ensureStarting();
                hooks.phase(HealthState.RuntimePhase.READY);
                if (!state.compareAndSet(State.STARTING, State.READY)) {
                    throw new IllegalStateException("Runtime was fenced while becoming ready");
                }
            }
        } catch (Throwable failure) {
            State failedState = state.get();
            if (failedState != State.FENCED && failedState != State.DRAINING
                    && failedState != State.STOPPED) {
                failStartup(failure);
            }
            throw failure;
        }
    }

    public State state() {
        return state.get();
    }

    public void awaitStop() throws InterruptedException {
        stopped.await();
    }

    public void shutdown(String reason) {
        Objects.requireNonNull(reason, "reason");
        State current = state.get();
        if (current == State.STOPPED || current == State.FENCED) return;
        if (!state.compareAndSet(current, State.DRAINING)) return;
        hooks.phase(HealthState.RuntimePhase.DRAINING);
        synchronized (startupGate) {
            runBounded(() -> orderlyStop(reason, false));
        }
    }

    public void fence(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        State current;
        do {
            current = state.get();
            if (current == State.FENCED || current == State.STOPPED) return;
        } while (!state.compareAndSet(current, State.FENCED));
        hooks.phase(HealthState.RuntimePhase.FENCED);
        synchronized (startupGate) {
            runBounded(() -> orderlyStop(message(cause), true));
        }
    }

    private void failStartup(Throwable failure) {
        if (controlAcquired.get()) {
            state.set(State.FENCED);
            hooks.phase(HealthState.RuntimePhase.FENCED);
            runBounded(() -> orderlyStop("startup failed: " + message(failure), true));
        } else {
            state.set(State.STOPPED);
            hooks.phase(HealthState.RuntimePhase.STOPPED);
            safe(hooks::closeResources);
            stopped.countDown();
        }
    }

    private void orderlyStop(String reason, boolean fenced) {
        try {
            safe(hooks::stopScheduler);
            safe(hooks::stopEffectWorkers);
            safe(hooks::stopTimerLoop);
            safe(hooks::stopHealth);
            if (bootStarted.get()) {
                if (fenced) safe(() -> hooks.markFenced(reason));
                else safe(() -> hooks.markClean(reason));
            }
            safe(hooks::releaseControl);
            safe(hooks::closeResources);
        } finally {
            if (!fenced) {
                state.set(State.STOPPED);
                safe(() -> hooks.phase(HealthState.RuntimePhase.STOPPED));
            }
            stopped.countDown();
        }
    }

    private void runBounded(Runnable shutdown) {
        Thread worker = Thread.ofPlatform().daemon(true).name("cilexec-shutdown").start(shutdown);
        try {
            if (!worker.join(shutdownGrace)) {
                worker.interrupt();
                safe(hooks::forceClose);
                if (state.get() != State.FENCED) {
                    state.set(State.STOPPED);
                    safe(() -> hooks.phase(HealthState.RuntimePhase.STOPPED));
                }
                stopped.countDown();
            }
        } catch (InterruptedException interrupted) {
            worker.interrupt();
            safe(hooks::forceClose);
            stopped.countDown();
            Thread.currentThread().interrupt();
        }
    }

    private void startupStep(Runnable action) {
        startupStep(() -> {
            action.run();
            return null;
        });
    }

    private <T> T startupStep(Supplier<T> action) {
        synchronized (startupGate) {
            ensureStarting();
            T result = action.get();
            ensureStarting();
            return result;
        }
    }

    private void ensureStarting() {
        if (state.get() != State.STARTING) {
            throw new IllegalStateException("Runtime startup was cancelled by " + state.get());
        }
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (Throwable ignored) {
            // Shutdown continues so control ownership and pools are always released.
        }
    }

    @Override
    public void close() {
        shutdown("runtime close requested");
    }

    public enum State {
        CREATED,
        STARTING,
        READY,
        DRAINING,
        FENCED,
        STOPPED
    }

    public interface Hooks {
        void phase(HealthState.RuntimePhase phase);

        int verifySchema();

        void acquireControl(Consumer<Throwable> onFence);

        void beginBoot(int schemaVersion);

        void markRecovering();

        void recover();

        void startHealth();

        void startScheduler();

        void startEffectWorkers();

        void startTimerLoop();

        void markReady();

        void stopScheduler();

        void stopEffectWorkers();

        void stopTimerLoop();

        void stopHealth();

        void markClean(String reason);

        void markFenced(String reason);

        void releaseControl();

        void closeResources();

        void forceClose();
    }
}
