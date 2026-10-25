package com.follarce;

import com.follarce.app.CilExecApplication;

/** Stable executable entry point retained across the database-driven rewrite. */
public final class Main {
    private Main() {
    }

    public static void main(String[] arguments) {
        boolean localTerminal = (arguments.length == 0
                || (arguments.length == 1 && "terminal".equals(arguments[0])))
                && !System.getenv().containsKey("CILEXEC_DATABASE_URL");
        if (localTerminal) {
            System.out.println("Starting CilExec; first launch may take a minute...");
            System.out.flush();
        }
        // A directly launched JAR owns an interactive terminal. Keep routine structured
        // startup logs from flooding it; operators can opt back in with CILEXEC_LOG_LEVEL.
        if (!System.getenv().containsKey("CILEXEC_DATABASE_URL")
                && !System.getenv().containsKey("CILEXEC_LOG_LEVEL")
                && System.getProperty("CILEXEC_LOG_LEVEL") == null) {
            System.setProperty("CILEXEC_LOG_LEVEL", "WARN");
        }
        int exitCode = CilExecApplication.run(arguments);
        if (exitCode != 0) System.exit(exitCode);
    }
}
