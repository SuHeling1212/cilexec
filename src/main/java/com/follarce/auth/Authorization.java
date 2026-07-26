package com.follarce.auth;

import com.follarce.domain.auth.Capability;
import com.follarce.domain.port.TransactionContext;

import java.util.UUID;

/** Central application-level capability gate; PostgreSQL RLS remains the row-level backstop. */
public final class Authorization {
    private Authorization() {
    }

    public static void require(TransactionContext transaction, UUID userId, Capability capability) {
        java.util.Set<Capability> capabilities = transaction.auth().capabilities(userId);
        if (!capabilities.contains(Capability.SYSTEM_ADMIN)
                && !capabilities.contains(capability)) {
            throw new SecurityException("Missing CilExec capability: " + capability.name());
        }
    }

    /** Gates operations that intentionally cross user and PostgreSQL RLS boundaries. */
    public static void requireAdministrator(TransactionContext transaction, UUID userId) {
        require(transaction, userId, Capability.SYSTEM_ADMIN);
    }
}
