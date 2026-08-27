package com.follarce.persistence.postgres.transaction;

import com.follarce.domain.port.UserTransactionRunner;

/** Runs work after setting the user's PostgreSQL tenant role and RLS session identity. */
public interface UserTransactionExecutor extends UserTransactionRunner {}
