package com.follarce.fcl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable compiled FCL program. */
public final class FclProgram {
    public enum Access { PUBLIC, PRIVATE }

    public record Field(String name, Access access, FclExpression defaultValue) {
        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(access, "access");
            Objects.requireNonNull(defaultValue, "defaultValue");
        }
    }

    /** Method bodies are ordinary compiled functions; this metadata owns their dispatch key. */
    public record Method(String name, int arity, Access access, String functionKey,
                         boolean constructor) {
        public Method {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(access, "access");
            Objects.requireNonNull(functionKey, "functionKey");
            if (arity < 0) throw new IllegalArgumentException("Method arity cannot be negative");
        }

        public String signature() { return name + "/" + arity; }
    }

    public record ClassDefinition(String name, Access access, String parent,
                                  Map<String, Field> fields, Map<String, Method> methods) {
        public ClassDefinition {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(access, "access");
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            methods = Collections.unmodifiableMap(new LinkedHashMap<>(methods));
        }
    }
    /**
     * Functions linked from an imported module carry that module's origin in
     * {@code packageIdentity}; functions compiled from the base program carry {@code null}.
     * {@code publicBinding} preserves whether a source function is callable outside its module.
     * A non-null {@code moduleBindings} map is the imported module's isolated global namespace.
     */
    public record Function(String name, List<String> parameters, int entryPoint,
                            int endPoint, String packageIdentity, boolean publicBinding,
                            Map<String, Object> moduleBindings) {
        public Function {
            Objects.requireNonNull(name, "name");
            parameters = List.copyOf(parameters);
            if (moduleBindings != null) {
                Map<String, Object> copied = new LinkedHashMap<>();
                moduleBindings.forEach((key, value) -> copied.put(key, FclValues.deepCopy(value)));
                moduleBindings = Collections.unmodifiableMap(copied);
            }
        }

        public Function(String name, List<String> parameters, int entryPoint, int endPoint) {
            this(name, parameters, entryPoint, endPoint, null, true, null);
        }
    }

    private final List<FclInstruction> instructions;
    private final Map<String, Function> functions;
    private final Map<String, ClassDefinition> classes;
    private final String source;
    private final String sourceHash;

    FclProgram(List<FclInstruction> instructions, Map<String, Function> functions,
               String source) {
        this(instructions, functions, Map.of(), source);
    }

    FclProgram(List<FclInstruction> instructions, Map<String, Function> functions,
               Map<String, ClassDefinition> classes, String source) {
        this.instructions = List.copyOf(instructions);
        this.functions = Collections.unmodifiableMap(new LinkedHashMap<>(functions));
        this.classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        this.source = source;
        this.sourceHash = sha256(source);
    }

    public List<FclInstruction> instructions() {
        return instructions;
    }

    public Map<String, Function> functions() {
        return functions;
    }

    public Function function(String name) {
        return functions.get(name);
    }

    public Map<String, ClassDefinition> classes() { return classes; }

    public ClassDefinition classDefinition(String name) { return classes.get(name); }

    /** Returns a resolver view with explicitly disabled function names omitted. */
    public FclProgram withoutFunctions(java.util.Set<String> disabled) {
        if (disabled == null || disabled.isEmpty()) return this;
        Map<String, Function> remaining = new LinkedHashMap<>(functions);
        disabled.forEach(remaining::remove);
        return new FclProgram(instructions, remaining, classes, source);
    }

    public String sourceHash() {
        return sourceHash;
    }

    /** Source text used to compute {@link #sourceHash()} for persisted base programs. */
    public String source() {
        return source;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
