package com.follarce.audit;

import com.follarce.domain.Invariant;
import com.follarce.domain.audit.AuditRetentionPolicy;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.TransactionExecutor;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Configures exact-action retention and invokes bounded database-side cleanup. */
public final class AuditRetentionService {
    public static final int MAX_PURGE_BATCH = 10_000;

    private final TransactionExecutor transactions;
    private final Clock clock;

    public AuditRetentionService(TransactionExecutor transactions, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AuditRetentionPolicy configure(String eventType, Duration retainFor,
                                           boolean enabled) {
        long seconds = wholePositiveSeconds(retainFor);
        AuditRetentionPolicy policy = new AuditRetentionPolicy(eventType, seconds, enabled,
                clock.instant());
        return transactions.inTransaction(Isolation.SERIALIZABLE, transaction -> {
            transaction.audit().saveRetentionPolicy(policy);
            return policy;
        });
    }

    public Optional<AuditRetentionPolicy> find(String eventType) {
        String checkedEventType = Invariant.text(eventType, "eventType");
        return transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.audit().findRetentionPolicy(checkedEventType));
    }

    public int purgeExpired(int limit) {
        if (limit < 1 || limit > MAX_PURGE_BATCH) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PURGE_BATCH);
        }
        return transactions.inTransaction(Isolation.READ_COMMITTED,
                transaction -> transaction.audit().purgeExpired(limit));
    }

    private static long wholePositiveSeconds(Duration retainFor) {
        Objects.requireNonNull(retainFor, "retainFor");
        if (retainFor.isZero() || retainFor.isNegative() || retainFor.getNano() != 0) {
            throw new IllegalArgumentException(
                    "retainFor must be a positive whole-second duration");
        }
        return retainFor.getSeconds();
    }
}
