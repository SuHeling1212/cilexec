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
    /**
     * Linked package functions carry the 64-character logical package hash in
     * {@code packageIdentity}; user-defined and validation-only functions carry
     * {@code null}.
     */
    public record Function(String name, List<String> parameters, int entryPoint,
                           int endPoint, String packageIdentity) {
        public Function {
            Objects.requireNonNull(name, "name");
            parameters = List.copyOf(parameters);
        }

        public Function(String name, List<String> parameters, int entryPoint, int endPoint) {
            this(name, parameters, entryPoint, endPoint, null);
        }
    }

    private final List<FclInstruction> instructions;
    private final Map<String, Function> functions;
    private final String source;
    private final String sourceHash;

    FclProgram(List<FclInstruction> instructions, Map<String, Function> functions,
               String source) {
        this.instructions = List.copyOf(instructions);
        this.functions = Collections.unmodifiableMap(new LinkedHashMap<>(functions));
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

    public String sourceHash() {
        return sourceHash;
    }

    /** Canonical persistence input. Restore by recompiling and checking {@link #sourceHash()}. */
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
