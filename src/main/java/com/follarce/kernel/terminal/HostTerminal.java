package com.follarce.kernel.terminal;

import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates ownership of the host standard input stream. */
public final class HostTerminal {
    private static final AtomicBoolean SHELL_OWNS_INPUT = new AtomicBoolean();

    private HostTerminal() {}

    public static void claimForShell() {
        if (!SHELL_OWNS_INPUT.compareAndSet(false, true)) {
            throw new IllegalStateException("Host standard input is already owned by the shell");
        }
    }

    public static void releaseFromShell() {
        SHELL_OWNS_INPUT.set(false);
    }

    public static void requireProcessInputAvailable() {
        if (SHELL_OWNS_INPUT.get()) {
            throw new IllegalStateException(
                    "Host shell owns standard input; direct FCL input is unavailable until attach is implemented");
        }
    }
}
