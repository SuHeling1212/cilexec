package com.follarce.fcl;

/** Internal Runtime/extension failure that must not be converted into catchable FCL control flow. */
final class FclKernelFailure extends RuntimeException {
    FclKernelFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
