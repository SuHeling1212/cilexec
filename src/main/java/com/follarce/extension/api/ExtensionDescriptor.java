package com.follarce.extension.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable identity embedded in a CilExec build for one source extension. */
public record ExtensionDescriptor(String id, String version, String description) {
    private static final Pattern ID = Pattern.compile(
            "[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+");

    public ExtensionDescriptor {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Extension id must be a lower-case dotted identifier: " + id);
        }
        version = required(version, "version");
        description = required(description, "description");
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Extension " + field + " must not be blank");
        }
        return normalized;
    }
}
