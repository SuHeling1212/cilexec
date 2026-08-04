package com.follarce.app;

import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.TransactionExecutor;
import com.follarce.domain.process.Continuation;
import com.follarce.effect.EffectWorkerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Periodic safety net for the durable wait/wake handover. It redelivers completed or
 * failed effects whose process was never woken, reclaims PREPARED effects no worker ever
 * claimed, and re-announces READY queue rows nobody claimed. Every action is idempotent
 * (the wake is guarded by the process state version and the wait target), so it can run
 * concurrently with the workers; a lost notification can therefore never freeze a process
 * for longer than one maintenance cycle.
 */
public final class DeliverySweeper {
    private static final Logger LOG = LoggerFactory.getLogger(DeliverySweeper.class);
    private static final Duration PREPARED_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration DELIVERY_GRACE = Duration.ofSeconds(10);
    private static final Duration QUEUE_STALE_AGE = Duration.ofSeconds(5);
    private static final int BATCH = 50;

    private final TransactionExecutor runtimeTransactions;
    private final Clock clock;

    public DeliverySweeper(TransactionExecutor runtimeTransactions, Clock clock) {
        this.runtimeTransactions = java.util.Objects.requireNonNull(runtimeTransactions,
                "runtimeTransactions");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /** Returns the number of durable rows the sweep repaired or announced. */
    public int sweepOnce() {
        Instant now = clock.instant();
        int repaired = runtimeTransactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> {
                    int count = 0;
                    List<EffectRequest> reclaimed = transaction.effects()
                            .reclaimStalePrepared(now, PREPARED_TIMEOUT.toMillis(), BATCH);
                    for (EffectRequest effect : reclaimed) {
                        count += redeliver(transaction, effect, now) ? 1 : 0;
                    }
                    List<EffectRequest> undelivered = transaction.effects()
                            .completedButUndelivered(now, DELIVERY_GRACE.toMillis(), BATCH);
                    for (EffectRequest effect : undelivered) {
                        count += redeliver(transaction, effect, now) ? 1 : 0;
                    }
                    return count;
                });
        int announced = runtimeTransactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.scheduler()
                        .requeueStale(now, QUEUE_STALE_AGE.toMillis()));
        if (repaired > 0) {
            LOG.info("Delivery sweeper repaired {} pending delivery; announced {} stale queue rows",
                    repaired, announced);
        }
        return repaired + announced;
    }

    private boolean redeliver(com.follarce.domain.port.TransactionContext transaction,
                              EffectRequest effect, Instant now) {
        Continuation.PersistedValue value = switch (effect.status()) {
            case COMPLETED -> effect.result().orElseThrow(() ->
                    new IllegalStateException("Completed effect " + effect.effectId()
                            + " has no delivery value"));
            case FAILED -> new Continuation.PersistedValue("error",
                    effect.failureReason().orElse("Effect failed"));
            default -> null;
        };
        if (value == null) return false;
        boolean woke = EffectWorkerService.wakeProcess(transaction, effect, value, now);
        if (woke) {
            LOG.warn("Redelivered {} effect {} to process {} (lost wake recovered)",
                    effect.status(), effect.effectId(), effect.processUid());
        }
        return woke;
    }
}
