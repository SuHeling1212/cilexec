package com.follarce.extension.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable identity embedded in a CilExec build for one source extension. */
public record ExtensionDescriptor(String id, String version, String description) {
    private static final Pattern ID = Pattern.compile(
            "[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+");

    public ExtensionDescriptor {
        if (id == null || id.length() > 128 || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Extension id must be a lower-case dotted identifier: " + id);
        }
        version = required(version, "version", 128);
        description = required(description, "description", 4096);
    }

    private static String required(String value, String field, int maximum) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Extension " + field + " must not be blank");
        }
        return normalized;
    }
}
