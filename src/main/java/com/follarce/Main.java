package com.follarce;

import com.follarce.app.CilExecApplication;

/** Stable executable entry point retained across the database-driven rewrite. */
public final class Main {
    private Main() {
    }

    public static void main(String[] arguments) {
        int exitCode = CilExecApplication.run(arguments);
        if (exitCode != 0) System.exit(exitCode);
    }
}
