package com.follarce.fcl;

/** Observable result of exactly one runtime step. */
public record FclStepResult(Status status, int pointerBefore, int pointerAfter,
                            int sourceLine, Object value,
                            FclContinuation.WaitState waitState) {
    public enum Status {
        ADVANCED,
        CALL_ENTERED,
        RETURNED,
        DIRECTIVE,
        WAITING,
        COMPLETED,
        FAILED
    }

    public FclStepResult {
        value = FclValues.deepCopy(value);
    }
}
