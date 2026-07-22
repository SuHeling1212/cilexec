package com.follarce.app;

import com.follarce.health.HealthState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeLifecycleTest {
    @Test
    void startsInDependencyOrderAndShutsDownInReverseWorkerOrder() {
        RecordingHooks hooks = new RecordingHooks();
        RuntimeLifecycle lifecycle = new RuntimeLifecycle(hooks, Duration.ofSeconds(1));

        lifecycle.start();

        assertEquals(RuntimeLifecycle.State.READY, lifecycle.state());
        assertEquals(List.of(
                "phase:STARTING", "schema", "control:acquire", "boot:15",
                "boot:recovering", "phase:RECOVERING", "recover",
                "health:start", "scheduler:start", "effects:start", "timer:start",
                "boot:ready", "phase:READY"), hooks.events);

        lifecycle.shutdown("SIGTERM");

        assertEquals(RuntimeLifecycle.State.STOPPED, lifecycle.state());
        assertEquals(List.of(
                "phase:DRAINING", "scheduler:stop", "effects:stop", "timer:stop",
                "health:stop", "boot:clean:SIGTERM", "control:release",
                "resources:close", "phase:STOPPED"),
                hooks.events.subList(13, hooks.events.size()));
    }

    @Test
    void controlLossFencesImmediatelyAndNeverMarksBootClean() {
        RecordingHooks hooks = new RecordingHooks();
        RuntimeLifecycle lifecycle = new RuntimeLifecycle(hooks, Duration.ofSeconds(1));
        lifecycle.start();

        hooks.fence.accept(new IllegalStateException("control session lost"));

        assertEquals(RuntimeLifecycle.State.FENCED, lifecycle.state());
        assertTrue(hooks.events.contains("phase:FENCED"));
        assertTrue(hooks.events.contains("boot:fenced:control session lost"));
        assertTrue(hooks.events.stream().noneMatch(event -> event.startsWith("boot:clean:")));
        assertTrue(hooks.events.indexOf("scheduler:stop")
                < hooks.events.indexOf("control:release"));
    }

    @Test
    void schemaFailureClosesResourcesWithoutCreatingBootMetadata() {
        RecordingHooks hooks = new RecordingHooks();
        hooks.schemaFailure = new IllegalStateException("unsupported schema");
        RuntimeLifecycle lifecycle = new RuntimeLifecycle(hooks, Duration.ofSeconds(1));

        assertThrows(IllegalStateException.class, lifecycle::start);

        assertEquals(RuntimeLifecycle.State.STOPPED, lifecycle.state());
        assertEquals(List.of("phase:STARTING", "schema", "phase:STOPPED",
                "resources:close"), hooks.events);
        assertTrue(hooks.events.stream().noneMatch(event -> event.startsWith("boot:")));
    }

    @Test
    void recoveryFailureAfterLockAcquisitionMarksBootFenced() {
        RecordingHooks hooks = new RecordingHooks();
        hooks.recoveryFailure = new IllegalStateException("recovery failed");
        RuntimeLifecycle lifecycle = new RuntimeLifecycle(hooks, Duration.ofSeconds(1));

        assertThrows(IllegalStateException.class, lifecycle::start);

        assertEquals(RuntimeLifecycle.State.FENCED, lifecycle.state());
        assertTrue(hooks.events.contains("boot:fenced:startup failed: recovery failed"));
        assertTrue(hooks.events.contains("control:release"));
        assertTrue(hooks.events.contains("resources:close"));
    }

    @Test
    void aLateFenceSignalCannotRewriteAStoppedRuntime() {
        RecordingHooks hooks = new RecordingHooks();
        RuntimeLifecycle lifecycle = new RuntimeLifecycle(hooks, Duration.ofSeconds(1));
        lifecycle.start();
        lifecycle.shutdown("SIGTERM");
        int eventCount = hooks.events.size();

        lifecycle.fence(new IllegalStateException("late monitor callback"));

        assertEquals(RuntimeLifecycle.State.STOPPED, lifecycle.state());
        assertEquals(eventCount, hooks.events.size());
    }

    private static final class RecordingHooks implements RuntimeLifecycle.Hooks {
        private final List<String> events = new ArrayList<>();
        private Consumer<Throwable> fence;
        private RuntimeException schemaFailure;
        private RuntimeException recoveryFailure;

        @Override
        public void phase(HealthState.RuntimePhase phase) {
            events.add("phase:" + phase);
        }

        @Override
        public int verifySchema() {
            events.add("schema");
            if (schemaFailure != null) throw schemaFailure;
            return 15;
        }

        @Override
        public void acquireControl(Consumer<Throwable> onFence) {
            events.add("control:acquire");
            fence = onFence;
        }

        @Override
        public void beginBoot(int schemaVersion) {
            events.add("boot:" + schemaVersion);
        }

        @Override
        public void markRecovering() {
            events.add("boot:recovering");
        }

        @Override
        public void recover() {
            events.add("recover");
            if (recoveryFailure != null) throw recoveryFailure;
        }

        @Override
        public void startHealth() {
            events.add("health:start");
        }

        @Override
        public void startScheduler() {
            events.add("scheduler:start");
        }

        @Override
        public void startEffectWorkers() {
            events.add("effects:start");
        }

        @Override
        public void startTimerLoop() {
            events.add("timer:start");
        }

        @Override
        public void markReady() {
            events.add("boot:ready");
        }

        @Override
        public void stopScheduler() {
            events.add("scheduler:stop");
        }

        @Override
        public void stopEffectWorkers() {
            events.add("effects:stop");
        }

        @Override
        public void stopTimerLoop() {
            events.add("timer:stop");
        }

        @Override
        public void stopHealth() {
            events.add("health:stop");
        }

        @Override
        public void markClean(String reason) {
            events.add("boot:clean:" + reason);
        }

        @Override
        public void markFenced(String reason) {
            events.add("boot:fenced:" + reason);
        }

        @Override
        public void releaseControl() {
            events.add("control:release");
        }

        @Override
        public void closeResources() {
            events.add("resources:close");
        }

        @Override
        public void forceClose() {
            events.add("resources:force-close");
        }
    }
}
