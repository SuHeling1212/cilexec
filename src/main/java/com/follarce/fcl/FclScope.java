package com.follarce.fcl;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A persisted name scope. Function calls receive a fresh scope. */
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
        try {
            resolve(name);
            return true;
        } catch (FclRuntimeException missing) {
            return false;
        }
    }

    public Object get(String name) {
        return values.get(resolve(name));
    }

    public void put(String name, Object value) {
        requireName(name);
        values.put(resolveForWrite(name), FclValues.deepCopy(value));
    }

    /** Removes a name. Removing a source also removes every linked name that depends on it. */
    public Object remove(String name) {
        if (!values.containsKey(name)) {
            throw new FclRuntimeException("UndefinedVariable", "Undefined variable: " + name);
        }
        Object removed = values.remove(name);
        if (!(removed instanceof FclLinkedName)) removeDependentLinks(name);
        return removed;
    }

    /**
     * Destroys the represented language object. For a linked name, this removes its source
     * and every name linked to that source; ordinary names still remove only themselves.
     */
    public Object destroy(String name) {
        String source = resolve(name);
        Object removed = values.remove(source);
        removeDependentLinks(source);
        return removed;
    }

    /** Makes {@code target} explicitly follow {@code source}; ordinary assignment never does this. */
    public void link(String target, String source) {
        requireName(source);
        requireName(target);
        if (source.equals(target)) throw new FclRuntimeException("InvalidLink",
                "A name cannot link to itself: " + source);
        resolve(source);
        values.put(target, new FclLinkedName(source));
    }

    public Map<String, Object> values() {
        Map<String, Object> copy = new LinkedHashMap<>();
        values.keySet().forEach(key -> {
            if (contains(key)) copy.put(key, FclValues.deepCopy(get(key)));
        });
        return Collections.unmodifiableMap(copy);
    }

    /** Raw scope form for continuation persistence; it retains explicit link relationships. */
    public Map<String, Object> persistedValues() {
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

    private String resolveForWrite(String name) {
        return values.containsKey(name) ? resolve(name) : name;
    }

    private String resolve(String name) {
        requireName(name);
        Set<String> seen = new HashSet<>();
        String current = name;
        while (true) {
            if (!seen.add(current) || !values.containsKey(current)) {
                throw new FclRuntimeException("UndefinedVariable", "Undefined variable: " + name);
            }
            Object entry = values.get(current);
            if (!(entry instanceof FclLinkedName link)) return current;
            current = link.source();
        }
    }

    private void removeDependentLinks(String removed) {
        Set<String> missing = new HashSet<>();
        missing.add(removed);
        boolean changed;
        do {
            changed = false;
            for (var iterator = values.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                if (entry.getValue() instanceof FclLinkedName link
                        && missing.contains(link.source())) {
                    missing.add(entry.getKey());
                    iterator.remove();
                    changed = true;
                }
            }
        } while (changed);
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Variable name cannot be blank");
    }
}
