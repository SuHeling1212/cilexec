package com.follarce.domain.port;

/** Opens the outer transaction boundary around one application operation. */
public interface TransactionExecutor {
    <T> T inTransaction(Isolation isolation, TransactionWork<T> work);
}
