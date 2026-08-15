package com.follarce.fcl;

import java.util.LinkedHashMap;
import java.util.Map;

/** Persists terminal private modes requested by routed FCL output for reconnect replay. */
public final class TerminalModeState {
    public static final String SCOPE_KEY = "cilexec.terminal.modes";

    private static final Map<String, String> MODES = Map.of(
            "alternate", "1049", "mouseTracking", "1002", "mouseEncoding", "1006",
            "paste", "2004", "focus", "1004", "cursorVisible", "25");

    private TerminalModeState() { }

    /** Applies the last requested state of each known terminal private mode in one output frame. */
    public static void capture(FclScope scope, String output) {
        Map<String, Object> modes = existing(scope);
        boolean changed = false;
        for (Map.Entry<String, String> mode : MODES.entrySet()) {
            int enabled = output.lastIndexOf("\u001b[?" + mode.getValue() + "h");
            int disabled = output.lastIndexOf("\u001b[?" + mode.getValue() + "l");
            if (enabled < 0 && disabled < 0) continue;
            boolean active = enabled > disabled;
            if (!Boolean.valueOf(active).equals(modes.put(mode.getKey(), active))) changed = true;
        }
        if (changed) scope.put(SCOPE_KEY, Map.copyOf(modes));
    }

    /** Reconstructs the active modes in a safe order after a client reconnect. */
    public static String replay(FclScope scope) {
        Map<String, Object> modes = existing(scope);
        StringBuilder output = new StringBuilder();
        append(output, modes, "alternate", "1049");
        append(output, modes, "mouseTracking", "1002");
        append(output, modes, "mouseEncoding", "1006");
        append(output, modes, "paste", "2004");
        append(output, modes, "focus", "1004");
        if (Boolean.FALSE.equals(modes.get("cursorVisible"))) output.append("\u001b[?25l");
        return output.toString();
    }

    private static void append(StringBuilder output, Map<String, Object> modes,
                               String name, String code) {
        if (Boolean.TRUE.equals(modes.get(name))) output.append("\u001b[?").append(code).append('h');
    }

    private static Map<String, Object> existing(FclScope scope) {
        if (!scope.contains(SCOPE_KEY) || !(scope.get(SCOPE_KEY) instanceof Map<?, ?> values)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> modes = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key instanceof String name && value instanceof Boolean) modes.put(name, value);
        });
        return modes;
    }
}
