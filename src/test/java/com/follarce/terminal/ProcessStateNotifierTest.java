package com.follarce.terminal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessStateNotifierTest {
    @Test
    void signalsForProcessesWithoutTerminalWaitersDoNotAccumulateState() {
        ProcessStateNotifier notifier = new ProcessStateNotifier();

        for (int index = 0; index < 10_000; index++) {
            notifier.signal(UUID.randomUUID(), UUID.randomUUID());
        }

        assertFalse(notifier.trackedProcesses() > 0);
    }

    @Test
    void wakesEveryWaiterAfterCommittedStateSignal() throws Exception {
        ProcessStateNotifier notifier = new ProcessStateNotifier();
        UUID owner = UUID.randomUUID();
        UUID processUid = UUID.randomUUID();
        long observed = notifier.version(owner, processUid);
        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() ->
                notifier.awaitChange(owner, processUid, observed, Duration.ofSeconds(5)));
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() ->
                notifier.awaitChange(owner, processUid, observed, Duration.ofSeconds(5)));

        notifier.signal(owner, processUid);

        assertTrue(first.get(1, TimeUnit.SECONDS));
        assertTrue(second.get(1, TimeUnit.SECONDS));
    }

    @Test
    void versionCheckPreventsLostWakeBetweenDatabaseReadAndWait() {
        ProcessStateNotifier notifier = new ProcessStateNotifier();
        UUID owner = UUID.randomUUID();
        UUID processUid = UUID.randomUUID();
        long observed = notifier.version(owner, processUid);
        notifier.signal(owner, processUid);

        assertTrue(notifier.awaitChange(owner, processUid, observed, Duration.ofSeconds(1)));
        notifier.forget(owner, processUid);
        assertFalse(notifier.trackedProcesses() > 0);
    }
}
