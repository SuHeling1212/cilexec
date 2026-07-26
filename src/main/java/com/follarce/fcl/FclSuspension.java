package com.follarce.fcl;

/** Internal control signal used when a host function has durably declared a wait. */
public final class FclSuspension extends RuntimeException {
    private static final FclSuspension INSTANCE = new FclSuspension();

    private FclSuspension() {
        super(null, null, false, false);
    }

    public static FclSuspension suspend() {
        return INSTANCE;
    }
}
