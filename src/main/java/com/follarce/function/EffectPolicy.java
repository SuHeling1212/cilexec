package com.follarce.function;

/** Recovery contract for an FCL built-in function. */
public enum EffectPolicy {
    /** Deterministic and free of observable side effects. */
    PURE,
    /** Read-only or nondeterministic result that is recorded and replayed. */
    RECORDED_RESULT,
    /** CilExec-owned operation that accepts a stable effect ID and is safe to retry. */
    LOCAL_TRANSACTIONAL,
    /** External operation whose peer accepts the stable effect ID as an idempotency key. */
    IDEMPOTENT_EXTERNAL,
    /** Retried after a crash; duplicate effects are part of the contract. */
    AT_LEAST_ONCE,
    /** Never retried automatically after dispatch. */
    AT_MOST_ONCE,
    /** An interrupted invocation requires an explicit recovery decision. */
    MANUAL_RECOVERY,
    /** Returns an engine command; ProcessRunner journals the actual operation. */
    CONTROL
}
