package com.follarce.persistence.postgres.transaction;

import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.TransactionWork;
import java.util.UUID;

/** Runs work after setting the user's PostgreSQL tenant role and RLS session identity. */
public interface UserTransactionExecutor {
    <T> T inUserTransaction(UUID userId, Isolation isolation, TransactionWork<T> work);
}
