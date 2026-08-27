package com.follarce.domain.port;

import java.util.UUID;

/**
 * Runs one tenant-scoped transaction without exposing a database implementation to callers.
 *
 * <p>The implementation may establish PostgreSQL RLS state, but that is an adapter concern;
 * application services only depend on the user and isolation semantics expressed here.
 */
public interface UserTransactionRunner {
    <T> T inUserTransaction(UUID userId, Isolation isolation, TransactionWork<T> work);
}
