package com.follarce.auth;

import com.follarce.domain.auth.Capability;
import com.follarce.domain.port.TransactionContext;

import java.util.UUID;

/** Central application-level capability gate; PostgreSQL RLS remains the row-level backstop. */
public final class Authorization {
    private Authorization() {
    }

    public static void require(TransactionContext transaction, UUID userId, Capability capability) {
        if (!transaction.auth().capabilities(userId).contains(capability)) {
            throw new SecurityException("Missing CilExec capability: " + capability.name());
        }
    }
}
