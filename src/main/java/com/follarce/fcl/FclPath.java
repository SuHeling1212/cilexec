package com.follarce.fcl;

import java.util.ArrayDeque;
import java.util.Deque;

/** Process-local, durable POSIX-style paths for the CilExec VFS. */
public final class FclPath {
    public static final String SCOPE_KEY = "cilexec.path.cwd";

    private FclPath() {
    }

    public static String current(FclContinuation continuation) {
        // The working directory belongs to the process, not to a function's local scope.
        // During a user-function call the Runtime replaces scope() with a fresh parameter
        // scope, while globalScope() keeps the durable process state in the outer call frame.
        FclScope processScope = continuation.globalScope();
        if (!processScope.contains(SCOPE_KEY)) return "/";
        Object value = processScope.get(SCOPE_KEY);
        if (value == null) return "/";
        if (!(value instanceof String path) || !path.startsWith("/")) return "/";
        return normalizeAbsolute(path);
    }

    public static String resolve(FclContinuation continuation, String source) {
        return resolve(current(continuation), source);
    }

    public static String resolve(String workingDirectory, String source) {
        if (source == null || source.isBlank()) {
            throw new FclRuntimeException("Path cannot be empty");
        }
        String normalized = source.replace('\\', '/');
        String candidate = normalized.startsWith("/")
                ? normalized : normalizeAbsolute(workingDirectory) + "/" + normalized;
        return normalizeAbsolute(candidate);
    }

    public static String normalizeAbsolute(String source) {
        String path = source.replace('\\', '/');
        if (!path.startsWith("/")) {
            throw new FclRuntimeException("Absolute VFS path required: " + source);
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String part : path.split("/+")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
    }
}
