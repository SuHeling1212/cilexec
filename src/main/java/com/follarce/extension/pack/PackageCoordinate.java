package com.follarce.extension.pack;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable package identity. Versions are exact SemVer values. */
public record PackageCoordinate(String namespace, String name, String version)
        implements Comparable<PackageCoordinate> {
    private static final Pattern NAMESPACE = Pattern.compile(
            "^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)*$");
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern VERSION = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    public PackageCoordinate {
        namespace = require(namespace, "namespace", NAMESPACE);
        name = require(name, "name", NAME);
        version = require(version, "version", VERSION);
    }

    public String key() {
        return namespace + "/" + name + "@" + version;
    }

    public String displayName() {
        return key();
    }

    @Override
    public int compareTo(PackageCoordinate other) {
        return key().compareTo(other.key());
    }

    private static String require(String value, String field, Pattern pattern) {
        Objects.requireNonNull(value, field + " is required");
        if (!pattern.matcher(value).matches()) {
            throw new PackageException("Invalid package " + field + ": " + value);
        }
        return value;
    }
}
