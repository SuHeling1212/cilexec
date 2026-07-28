package com.follarce.extension.api;

import java.util.Objects;
import java.util.Optional;

/** Crash-recovery declaration for an external operation. */
public record ExtensionEffectPolicy(
        boolean idempotent,
        Optional<String> idempotencyKey,
        boolean remotelyQueryable,
        boolean retryable,
        Recovery recovery
) {
    public ExtensionEffectPolicy {
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey")
                .map(String::trim)
                .filter(value -> !value.isEmpty());
        Objects.requireNonNull(recovery, "recovery");
        if (idempotent != idempotencyKey.isPresent()) {
            throw new IllegalArgumentException(
                    "An idempotent effect requires exactly one idempotency key");
        }
        if (retryable && !idempotent) {
            throw new IllegalArgumentException("Only idempotent effects may be retried");
        }
        if (recovery == Recovery.QUERY_REMOTE && !remotelyQueryable) {
            throw new IllegalArgumentException("Remote recovery requires a queryable effect");
        }
        if (recovery == Recovery.RETRY_IDEMPOTENT && (!idempotent || !retryable)) {
            throw new IllegalArgumentException(
                    "Retry recovery requires a retryable idempotent effect");
        }
    }

    /** Never automatically repeats an operation whose outcome became unknown. */
    public static ExtensionEffectPolicy manual() {
        return new ExtensionEffectPolicy(false, Optional.empty(), false, false,
                Recovery.MANUAL);
    }

    /** Automatically retries; the handler must propagate and enforce this key remotely. */
    public static ExtensionEffectPolicy retryIdempotent(String idempotencyKey) {
        return new ExtensionEffectPolicy(true, Optional.of(idempotencyKey), false, true,
                Recovery.RETRY_IDEMPOTENT);
    }

    /** Queries the remote system after a crash instead of repeating the operation. */
    public static ExtensionEffectPolicy queryRemote() {
        return new ExtensionEffectPolicy(false, Optional.empty(), true, false,
                Recovery.QUERY_REMOTE);
    }

    public enum Recovery {
        MANUAL,
        RETRY_IDEMPOTENT,
        QUERY_REMOTE
    }
}
