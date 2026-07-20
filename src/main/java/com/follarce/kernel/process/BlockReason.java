package com.follarce.kernel.process;

/** Why a process cannot currently be scheduled. */
public enum BlockReason {
    NONE,
    WAIT_ANY,
    WAIT_PID,
    IO,
    SWAP,
    EFFECT_RECOVERY
}
