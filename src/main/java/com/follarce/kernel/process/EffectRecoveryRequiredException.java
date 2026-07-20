package com.follarce.kernel.process;

import com.follarce.kernel.exception.ProcessException;

/** Raised when an interrupted effect cannot be resolved automatically. */
public final class EffectRecoveryRequiredException extends ProcessException {
    private final String effectId;

    public EffectRecoveryRequiredException(String effectId, String operation) {
        super("Effect outcome is unknown: " + operation + " (" + effectId + ")");
        this.effectId = effectId;
    }

    public String getEffectId() {
        return effectId;
    }
}
