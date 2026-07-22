package com.follarce.domain.port;

@FunctionalInterface
public interface TransactionWork<T> {
    T execute(TransactionContext transaction);
}
