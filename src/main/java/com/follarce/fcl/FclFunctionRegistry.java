package com.follarce.fcl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Instance-owned built-in function catalog with deterministic qualified lookup. */
public final class FclFunctionRegistry {
    @FunctionalInterface
    public interface Function {
        Object invoke(List<Object> arguments);
    }

    /** Host-aware function that may use the current durable continuation. */
    @FunctionalInterface
    public interface ContextFunction {
        Object invoke(List<Object> arguments, Invocation invocation);
    }

    /** Per-call interpreter context; it contains no JDBC or host resource. */
    public record Invocation(long expressionId, FclContinuation continuation,
                             String packageIdentity, FclProgram program) {
        public Invocation {
            Objects.requireNonNull(continuation, "continuation");
        }

        public Invocation(long expressionId, FclContinuation continuation) {
            this(expressionId, continuation, null, null);
        }

        public Invocation(long expressionId, FclContinuation continuation,
                          String packageIdentity) {
            this(expressionId, continuation, packageIdentity, null);
        }
    }

    public record Definition(String namespace, String name, Set<String> aliases,
                             ContextFunction function) {
        public Definition {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(name, "name");
            aliases = Collections.unmodifiableSet(new LinkedHashSet<>(aliases));
            Objects.requireNonNull(function, "function");
        }

        public String qualifiedName() {
            return namespace + "." + name;
        }
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Map<String, Definition> qualified = new LinkedHashMap<>();
    private final Map<String, Map<String, Definition>> bare = new LinkedHashMap<>();
    private final Map<String, ContextFunction> methods = new LinkedHashMap<>();
    private final Set<Definition> internal = new LinkedHashSet<>();

    public FclFunctionRegistry registerMethod(String kind, String method, ContextFunction function) {
        validateName(kind, "handle kind");
        validateName(method, "method");
        if (methods.putIfAbsent(kind + "." + method, Objects.requireNonNull(function)) != null) {
            throw new IllegalArgumentException("Method already registered: " + kind + "." + method);
        }
        return this;
    }

    /** Remove a legacy spelling from FCL without removing its implementation adapter. */
    public FclFunctionRegistry internalize(String identifier) {
        internal.add(resolveRaw(identifier));
        return this;
    }

    /**
     * Invokes a method registered for a system-handle kind. The receiver is passed first and is
     * never deep-copied (a handle copy keeps identity). Error semantics match
     * {@link #invokeDefinition}: suspension propagates unchanged, an FCL failure propagates,
     * and any other RuntimeException is a kernel fault wrapped in
     * {@link FclKernelFailure} so FCL catch blocks cannot observe it.
     */
    public Object invokeMethod(FclSystemHandle receiver, String name, List<Object> arguments,
                               Invocation invocation) {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(name, "name");
        ContextFunction function = methods.get(receiver.kind() + "." + name);
        if (function == null) throw new FclRuntimeException("UndefinedMethod",
                "Undefined method " + receiver.kind() + "." + name);
        List<Object> safe = new ArrayList<>(arguments.size() + 1);
        safe.add(receiver);
        arguments.forEach(value -> safe.add(FclValues.deepCopy(value)));
        try {
            return FclValues.deepCopy(function.invoke(Collections.unmodifiableList(safe), invocation));
        } catch (FclSuspension suspension) {
            throw suspension;
        } catch (FclRuntimeException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new FclKernelFailure("Method " + receiver.kind() + "." + name
                    + " failed: " + failure.getMessage(), failure);
        }
    }

    public FclFunctionRegistry register(String namespace, String name, Function function,
                                        String... aliases) {
        Objects.requireNonNull(function, "function");
        return registerContextual(namespace, name,
                (arguments, ignored) -> function.invoke(arguments), aliases);
    }

    public FclFunctionRegistry registerContextual(String namespace, String name,
                                                  ContextFunction function,
                                                  String... aliases) {
        validateName(namespace, "namespace");
        validateName(name, "function");
        Objects.requireNonNull(function, "function");
        Set<String> aliasSet = new LinkedHashSet<>();
        if (aliases != null) {
            for (String alias : aliases) {
                validateName(alias, "alias");
                aliasSet.add(alias);
            }
        }
        Definition definition = new Definition(namespace, name, aliasSet, function);
        bindQualified(namespace + "." + name, definition);
        bindBare(name, definition);
        for (String alias : aliasSet) {
            bindQualified(namespace + "." + alias, definition);
            bindBare(alias, definition);
        }
        return this;
    }

    /** Publishes a second qualified spelling for the same implementation without bare ambiguity. */
    public FclFunctionRegistry aliasQualified(String existingIdentifier, String namespace,
                                              String name) {
        validateName(namespace, "namespace");
        validateName(name, "function");
        Definition definition = resolveRaw(existingIdentifier);
        bindQualified(namespace + "." + name, definition);
        return this;
    }

    public boolean hasQualified(String identifier) {
        return qualified.containsKey(identifier);
    }

    public Definition resolve(String identifier) {
        Definition definition = resolveRaw(identifier);
        if (internal.contains(definition)) {
            throw new FclRuntimeException("UndefinedFunction", "Undefined function: " + identifier);
        }
        return definition;
    }

    private Definition resolveRaw(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.contains(".")) {
            Definition definition = qualified.get(identifier);
            if (definition == null) {
                throw new FclRuntimeException("UndefinedFunction", "Undefined function: " + identifier);
            }
            return definition;
        }
        Map<String, Definition> matches = bare.get(identifier);
        if (matches == null || matches.isEmpty()) {
            throw new FclRuntimeException("UndefinedFunction", "Undefined function: " + identifier);
        }
        if (matches.size() > 1) {
            throw new FclRuntimeException("Ambiguous bare function '" + identifier
                    + "'; use one of " + String.join(", ", matches.keySet()));
        }
        return matches.values().iterator().next();
    }

    public Object invokeInternal(String identifier, List<Object> arguments, Invocation invocation) {
        return invokeDefinition(resolveRaw(identifier), arguments, invocation);
    }

    public Object invoke(String identifier, List<Object> arguments) {
        return invoke(identifier, arguments, new Invocation(-1, new FclContinuation()));
    }

    public Object invoke(String identifier, List<Object> arguments, Invocation invocation) {
        return invokeDefinition(resolve(identifier), arguments, invocation);
    }

    private Object invokeDefinition(Definition definition, List<Object> arguments,
                                    Invocation invocation) {
        List<Object> safeArguments = new ArrayList<>(arguments.size());
        arguments.forEach(value -> safeArguments.add(FclValues.deepCopy(value)));
        try {
            return FclValues.deepCopy(definition.function().invoke(
                    Collections.unmodifiableList(safeArguments), invocation));
        } catch (FclSuspension suspension) {
            throw suspension;
        } catch (FclRuntimeException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new FclKernelFailure("Function " + definition.qualifiedName()
                    + " failed: " + failure.getMessage(), failure);
        }
    }

    public Set<String> qualifiedNames() {
        Set<String> visible = new LinkedHashSet<>();
        qualified.forEach((name, definition) -> {
            if (!internal.contains(definition)) visible.add(name);
        });
        return Collections.unmodifiableSet(visible);
    }

    private void bindQualified(String name, Definition definition) {
        Definition previous = qualified.putIfAbsent(name, definition);
        if (previous != null) {
            throw new IllegalArgumentException("Function already registered: " + name);
        }
    }

    private void bindBare(String name, Definition definition) {
        bare.computeIfAbsent(name, ignored -> new LinkedHashMap<>())
                .put(definition.qualifiedName(), definition);
    }

    private static void validateName(String value, String kind) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + kind + " name: " + value);
        }
    }
}
