package com.follarce.fcl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

/**
 * A complete FCL object value.
 *
 * <p>An object carries its class and field values directly. It deliberately has no identity:
 * copying an FCL value copies the complete object tree, so ordinary assignment never creates an
 * observable alias.
 */
public final class FclObjectValue {
    private final String className;
    private Storage storage;

    public FclObjectValue(String className, Map<String, ?> fields) {
        this.className = requireName(className, "class name");
        Map<String, Object> copied = new LinkedHashMap<>();
        Objects.requireNonNull(fields, "fields").forEach((name, value) ->
                copied.put(requireName(name, "field name"), FclValues.deepCopy(value)));
        this.storage = new Storage(copied);
    }

    private FclObjectValue(String className, Storage storage) {
        this.className = className;
        this.storage = storage;
        storage.owners++;
    }

    public String className() {
        return className;
    }

    public Object field(String name) {
        if (!storage.fields.containsKey(name)) {
            throw new FclRuntimeException("UndefinedField", "Undefined field " + className + "." + name);
        }
        return FclValues.deepCopy(storage.fields.get(name));
    }

    /** Package-private mutable access for the interpreter after it has performed access checks. */
    Object mutableField(String name) {
        if (!storage.fields.containsKey(name)) {
            throw new FclRuntimeException("UndefinedField", "Undefined field " + className + "." + name);
        }
        return storage.fields.get(name);
    }

    /** Ensures this value owns an independent field map before a nested mutation. */
    void prepareForMutation() {
        detachForWrite();
    }

    void field(String name, Object value) {
        if (!storage.fields.containsKey(name)) {
            throw new FclRuntimeException("UndefinedField", "Undefined field " + className + "." + name);
        }
        detachForWrite();
        storage.fields.put(requireName(name, "field name"), FclValues.deepCopy(value));
    }

    public Map<String, Object> fields() {
        Map<String, Object> copy = new LinkedHashMap<>();
        storage.fields.forEach((name, value) -> copy.put(name, FclValues.deepCopy(value)));
        return Collections.unmodifiableMap(copy);
    }

    FclObjectValue copy() {
        return new FclObjectValue(className, storage);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FclObjectValue value
                && className.equals(value.className)
                && storage.fields.equals(value.storage.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, storage.fields);
    }

    @Override
    public String toString() {
        return className + "{...}";
    }

    private static String requireName(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " cannot be blank");
        return value;
    }

    private void detachForWrite() {
        if (storage.owners == 1) return;
        storage.owners--;
        Map<String, Object> copied = new LinkedHashMap<>();
        storage.fields.forEach((name, value) -> copied.put(name, FclValues.deepCopy(value)));
        storage = new Storage(copied);
    }

    /** Runtime-only sharing metadata. It is never part of FCL values or persistence. */
    private static final class Storage {
        private final Map<String, Object> fields;
        private int owners = 1;

        private Storage(Map<String, Object> fields) {
            this.fields = fields;
        }
    }
}
