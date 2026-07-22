package com.follarce.fcl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A persisted variable scope. Function calls receive a fresh scope. */
public final class FclScope {
    private final Map<String, Object> values;

    public FclScope() {
        this.values = new LinkedHashMap<>();
    }

    public FclScope(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        this.values = new LinkedHashMap<>();
        values.forEach((key, value) -> this.values.put(key, FclValues.deepCopy(value)));
    }

    public boolean contains(String name) {
        return values.containsKey(name);
    }

    public Object get(String name) {
        if (!values.containsKey(name)) {
            throw new FclRuntimeException("Undefined variable: " + name);
        }
        return values.get(name);
    }

    public void put(String name, Object value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Variable name cannot be blank");
        }
        values.put(name, FclValues.deepCopy(value));
    }

    public Map<String, Object> values() {
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, FclValues.deepCopy(value)));
        return Collections.unmodifiableMap(copy);
    }

    Map<String, Object> mutableValues() {
        return values;
    }

    public FclScope copy() {
        return new FclScope(values);
    }
}
