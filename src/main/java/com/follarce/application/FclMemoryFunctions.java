package com.follarce.application;

import com.follarce.domain.process.ProcessInbox;
import com.follarce.extension.JavaExtensionCatalog;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclValues;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Process-local symbol inspection and deletion for value-semantic FCL state. */
final class FclMemoryFunctions {
    private FclMemoryFunctions() { }

    static void install(FclFunctionRegistry registry, JavaExtensionCatalog extensions) {
        registry.registerContextual("memory", "list", (args, invocation) -> {
            if (args.size() > 1) throw new FclRuntimeException(
                    "memory.list expects an optional boolean or options map");
            MemoryListOptions options = memoryListOptions(args);
            return Map.of("variables", memoryVariables(invocation.continuation(), options.includeParents()),
                    "functions", memoryFunctions(invocation, options.includeRuntime(), registry, extensions));
        });
        registry.registerContextual("memory", "destroy", (args, invocation) -> destroy(args, invocation));
    }

    private static boolean destroy(List<Object> args, FclFunctionRegistry.Invocation invocation) {
        if (args.size() != 2 || !(args.get(0) instanceof String rootName)
                || !(args.get(1) instanceof List<?> rawIndices)) {
            throw new FclRuntimeException("memory.destroy requires a symbol target");
        }
        if (reservedScopeKey(rootName)) throw new FclRuntimeException(
                "memory.destroy cannot remove runtime state: " + rootName);
        @SuppressWarnings("unchecked") List<Object> indices = (List<Object>) rawIndices;
        FclContinuation continuation = invocation.continuation();
        if (!continuation.scope().contains(rootName)) return false;
        if (indices.isEmpty()) {
            continuation.scope().destroy(rootName);
            return true;
        }
        Object removed = FclValues.removeIndexed(continuation.scope().get(rootName), indices);
        return removed != FclValues.NO_ENTRY;
    }

    private static List<Map<String, Object>> memoryFunctions(FclFunctionRegistry.Invocation invocation,
                                                               boolean includeRuntime,
                                                               FclFunctionRegistry registry,
                                                               JavaExtensionCatalog extensions) {
        Map<String, Map<String, Object>> functions = new LinkedHashMap<>();
        FclProgram program = invocation.program();
        if (program != null) program.functions().forEach((name, function) -> {
            if (name.startsWith("__pkg_")) return;
            String origin = function.packageIdentity();
            functions.put(name, Map.of("name", name, "kind", origin == null ? "defined" : "imported",
                    "origin", origin == null ? "" : origin, "mutable", false));
        });
        if (includeRuntime) registry.qualifiedNames().forEach(name -> {
            String namespace = name.substring(0, name.indexOf('.'));
            functions.putIfAbsent(name, Map.of("name", name,
                    "kind", extensions.namespaces().contains(namespace) ? "extension" : "builtin",
                    "origin", "", "mutable", false));
        });
        return List.copyOf(functions.values());
    }

    private static Map<String, Object> memoryVariables(FclContinuation continuation,
                                                        boolean includeParents) {
        Map<String, Object> variables = new LinkedHashMap<>();
        if (includeParents && continuation.globalScope() != continuation.scope()) {
            continuation.globalScope().values().forEach((name, value) -> {
                if (visibleMemoryVariable(name)) variables.put(name, value);
            });
        }
        continuation.scope().values().forEach((name, value) -> {
            if (visibleMemoryVariable(name)) variables.put(name, value);
        });
        return variables;
    }

    private static MemoryListOptions memoryListOptions(List<Object> args) {
        if (args.isEmpty()) return new MemoryListOptions(false, false);
        Object option = args.getFirst();
        if (option instanceof Boolean includeParents) return new MemoryListOptions(includeParents, false);
        if (!(option instanceof Map<?, ?> map)) throw new FclRuntimeException(
                "memory.list option must be a boolean or map");
        return new MemoryListOptions(mapBoolean(map, "includeParents"), mapBoolean(map, "includeRuntime"));
    }

    private static boolean mapBoolean(Map<?, ?> values, String name) {
        Object value = values.get(name);
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        throw new FclRuntimeException("memory.list " + name + " must be boolean");
    }

    private static boolean visibleMemoryVariable(String name) {
        return !name.startsWith("cilexec.") && !ProcessInbox.keys().contains(name)
                && !name.equals("path.aliases");
    }

    private static boolean reservedScopeKey(String name) {
        return name.startsWith("cilexec.") || ProcessInbox.keys().contains(name);
    }

    private record MemoryListOptions(boolean includeParents, boolean includeRuntime) { }
}
