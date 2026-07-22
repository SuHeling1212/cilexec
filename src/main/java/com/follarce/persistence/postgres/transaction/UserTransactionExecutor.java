package com.follarce.persistence.postgres.transaction;

import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.TransactionWork;
import java.util.UUID;

/** Runs a transaction under the matching PostgreSQL LOGIN Role and RLS identity. */
public interface UserTransactionExecutor {
    <T> T inUserTransaction(UUID userId, Isolation isolation, TransactionWork<T> work);
}
