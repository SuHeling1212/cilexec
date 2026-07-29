package com.follarce.terminal;

import java.io.PrintWriter;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Delivers durable FCL output only to the terminal session that submitted the process. */
public final class TerminalOutputRouter {
    private static final ConcurrentHashMap<UUID, Set<PrintWriter>> OUTPUTS =
            new ConcurrentHashMap<>();

    private TerminalOutputRouter() {}

    public static void attach(UUID routeId, PrintWriter output) {
        OUTPUTS.computeIfAbsent(routeId, ignored -> ConcurrentHashMap.newKeySet()).add(output);
    }

    public static void detachAll(PrintWriter output) {
        OUTPUTS.forEach((ownerId, ignored) -> OUTPUTS.computeIfPresent(ownerId,
                (unused, outputs) -> {
                    outputs.remove(output);
                    return outputs.isEmpty() ? null : outputs;
                }));
        TerminalOutputTracker.discard(output);
    }

    /** Returns whether at least one authenticated terminal received the output. */
    public static boolean publish(UUID routeId, String text, boolean newline) {
        Set<PrintWriter> outputs = OUTPUTS.get(routeId);
        if (outputs == null || outputs.isEmpty()) return false;
        for (PrintWriter output : outputs) {
            synchronized (output) {
                if (newline) output.println(text);
                else output.print(text);
                output.flush();
                TerminalOutputTracker.printed(output, text, newline);
            }
        }
        return true;
    }
}
