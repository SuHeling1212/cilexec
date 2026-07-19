package com.follarce.function;

import java.util.Map;
import java.util.Set;

/** Explicit support and recovery policy catalog for built-in functions. */
public final class BuiltinFunctionCatalog {
    private static final Set<String> PURE_MATH = Set.of(
            "sin", "cos", "sqrt", "abs", "round", "floor", "ceil",
            "pow", "max", "min", "pi", "e");
    private static final Set<String> PURE_TERM = Set.of(
            "color", "paint", "bold", "dim", "reset", "clear", "eraseLine",
            "cursorUp", "cursorDown", "cursorForward", "cursorBack", "red",
            "green", "blue", "yellow", "cyan", "magenta", "white");
    private static final Set<String> PURE_UTIL = Set.of(
            "toJson", "fromJson", "typeOf", "isArray", "isMap", "isNumber",
            "isString", "isBool", "toString");
    private static final Set<String> PROCESS_CONTROL = Set.of(
            "fork", "exec", "kill", "wait", "waitPID", "pause", "continue");
    private static final Set<String> FILE_READS = Set.of("read", "listdir", "exists", "readMetaData");
    private static final Set<String> FILE_RETRYABLE_WRITES = Set.of("write", "append", "createFile");
    private static final Set<String> FILE_MANUAL_WRITES = Set.of(
            "removeFile", "createDir", "removeDir", "rename", "link", "lock", "renewLock", "unlock");
    private static final Set<String> SWAP_WRITES = Set.of(
            "create", "remove", "add", "get", "removeVar", "update", "lock",
            "renewLock", "unlock", "clear", "waitFor", "signal");

    private BuiltinFunctionCatalog() {}

    public static EffectPolicy policy(String namespace, String name) {
        if (namespace == null || name == null) return null;
        return switch (namespace) {
            case "math" -> "random".equals(name) ? EffectPolicy.RECORDED_RESULT
                    : PURE_MATH.contains(name) ? EffectPolicy.PURE : null;
            case "term" -> PURE_TERM.contains(name) ? EffectPolicy.PURE : null;
            case "util" -> utilPolicy(name);
            case "path" -> pathPolicy(name);
            case "process" -> PROCESS_CONTROL.contains(name) ? EffectPolicy.CONTROL
                    : "getPID".equals(name) ? EffectPolicy.PURE
                    : "getPPID".equals(name) ? EffectPolicy.RECORDED_RESULT
                    : "getListOfChildProcess".equals(name) ? EffectPolicy.RECORDED_RESULT : null;
            case "file" -> FILE_READS.contains(name) ? EffectPolicy.RECORDED_RESULT
                    : FILE_RETRYABLE_WRITES.contains(name) ? EffectPolicy.LOCAL_TRANSACTIONAL
                    : FILE_MANUAL_WRITES.contains(name) ? EffectPolicy.MANUAL_RECOVERY : null;
            case "io" -> ioPolicy(name);
            case "user" -> userPolicy(name);
            case "swapPool" -> SWAP_WRITES.contains(name) ? EffectPolicy.MANUAL_RECOVERY
                    : Set.of("ls", "exists", "list").contains(name)
                    ? EffectPolicy.RECORDED_RESULT : null;
            case "network" -> Set.of("httpGet", "webget").contains(name)
                    ? EffectPolicy.RECORDED_RESULT
                    : Set.of("httpPost", "webpost").contains(name)
                    ? EffectPolicy.MANUAL_RECOVERY : null;
            case "socket" -> Set.of("connect", "send", "receive", "close", "bind", "accept").contains(name)
                    ? EffectPolicy.MANUAL_RECOVERY : null;
            case "package" -> packagePolicy(name);
            case "system" -> systemPolicy(name);
            default -> null;
        };
    }

    private static EffectPolicy utilPolicy(String name) {
        if (PURE_UTIL.contains(name)) return EffectPolicy.PURE;
        if ("getTime".equals(name)) return EffectPolicy.RECORDED_RESULT;
        if (Set.of("print", "println").contains(name)) return EffectPolicy.AT_LEAST_ONCE;
        if (Set.of("input").contains(name)) return EffectPolicy.MANUAL_RECOVERY;
        if ("exit".equals(name)) return EffectPolicy.CONTROL;
        if ("sleep".equals(name)) return EffectPolicy.MANUAL_RECOVERY;
        return null;
    }

    private static EffectPolicy pathPolicy(String name) {
        if (Set.of("normalize", "getFileName", "getParentPath").contains(name)) return EffectPolicy.PURE;
        if (Set.of("resolve", "getEnvVar", "getAlias", "listAliases").contains(name)) {
            return EffectPolicy.RECORDED_RESULT;
        }
        if (Set.of("setAlias", "removeAlias").contains(name)) return EffectPolicy.LOCAL_TRANSACTIONAL;
        return null;
    }

    private static EffectPolicy ioPolicy(String name) {
        if (Set.of("print", "println").contains(name)) return EffectPolicy.AT_LEAST_ONCE;
        if ("readFile".equals(name)) return EffectPolicy.RECORDED_RESULT;
        if ("writeFile".equals(name)) return EffectPolicy.LOCAL_TRANSACTIONAL;
        if (Set.of("input", "readChar").contains(name)) return EffectPolicy.MANUAL_RECOVERY;
        return null;
    }

    private static EffectPolicy userPolicy(String name) {
        if (Set.of("validateUser", "getCurrentUser", "isLocal", "getListOfUsers").contains(name)) {
            return EffectPolicy.RECORDED_RESULT;
        }
        if (Set.of("createUser", "removeUser", "switchUser").contains(name)) {
            return EffectPolicy.LOCAL_TRANSACTIONAL;
        }
        return null;
    }

    private static EffectPolicy systemPolicy(String name) {
        if ("ls".equals(name)) return EffectPolicy.RECORDED_RESULT;
        if (Set.of("kill", "resolveEffect").contains(name)) return EffectPolicy.MANUAL_RECOVERY;
        if (Set.of("invoke", "forceRemove", "reset", "exec").contains(name)) {
            return EffectPolicy.MANUAL_RECOVERY;
        }
        return null;
    }

    private static EffectPolicy packagePolicy(String name) {
        if (Set.of("list", "info", "verify", "resource").contains(name)) {
            return EffectPolicy.RECORDED_RESULT;
        }
        if (Set.of("install", "remove", "gc", "pin", "unpin", "recover").contains(name)) {
            return EffectPolicy.LOCAL_TRANSACTIONAL;
        }
        if ("build".equals(name)) return EffectPolicy.MANUAL_RECOVERY;
        return null;
    }
}
