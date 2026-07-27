package com.follarce.util;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Lightweight, intentionally visible timing trace for one terminal FCL submission. */
public final class CommandTiming {
    public static final String SCOPE_KEY = "cilexec.debug.commandTrace";
    private static final ConcurrentHashMap<String, Long> STARTS = new ConcurrentHashMap<>();
    private static final boolean ENABLED = !"false".equalsIgnoreCase(
            System.getenv().getOrDefault("CILEXEC_COMMAND_TIMING", "true"));

    private CommandTiming() {
    }

    public static String begin(String stage) {
        if (!ENABLED) return "";
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        STARTS.put(traceId, System.nanoTime());
        print(traceId, stage);
        return traceId;
    }

    public static void point(String traceId, String stage) {
        if (!ENABLED || traceId == null || traceId.isBlank()) return;
        print(traceId, stage);
    }

    public static void finish(String traceId, String stage) {
        point(traceId, stage);
        if (traceId != null) STARTS.remove(traceId);
    }

    private static void print(String traceId, String stage) {
        long started = STARTS.getOrDefault(traceId, System.nanoTime());
        double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
        System.out.printf(Locale.ROOT, "[CILEXEC-TIMING] trace=%s elapsed=%.3fms stage=%s%n",
                traceId, elapsedMs, stage);
        System.out.flush();
    }
}
