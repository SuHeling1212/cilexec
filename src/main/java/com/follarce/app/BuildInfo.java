package com.follarce.app;

import com.follarce.version.ReleaseVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/** Filtered build identity embedded in the application artifact. */
public record BuildInfo(
        String applicationName,
        String applicationVersion,
        String revision,
        int fclRuntimeFormat,
        int minimumSchema,
        int maximumSchema
) {
    private static final String RESOURCE = "/build-info.properties";

    public BuildInfo {
        applicationName = text(applicationName, "applicationName");
        applicationVersion = text(applicationVersion, "applicationVersion");
        revision = text(revision, "revision");
        if (fclRuntimeFormat < 1) {
            throw new IllegalArgumentException("fclRuntimeFormat must be positive");
        }
        if (minimumSchema < 1 || maximumSchema < minimumSchema) {
            throw new IllegalArgumentException("invalid database schema compatibility range");
        }
    }

    public static BuildInfo load() {
        try (InputStream input = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            return load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + RESOURCE, exception);
        }
    }

    static BuildInfo load(InputStream input) {
        Objects.requireNonNull(input, "input");
        Properties values = new Properties();
        try {
            values.load(input);
            return new BuildInfo(
                    property(values, "application.name"),
                    property(values, "application.version"),
                    property(values, "build.revision"),
                    number(values, "fcl.runtime.format"),
                    number(values, "database.schema.minimum"),
                    number(values, "database.schema.maximum"));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot parse build information", exception);
        }
    }

    private static String property(Properties values, String name) {
        String value = values.getProperty(name);
        if (value == null || value.isBlank() || value.contains("${")) {
            throw new IllegalStateException("Invalid build property: " + name);
        }
        return value.trim();
    }

    private static int number(Properties values, String name) {
        String value = property(values, name);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            try {
                return ReleaseVersion.schemaNumber(value);
            } catch (IllegalArgumentException unsupported) {
                throw new IllegalStateException(
                        "Build property must be a positive integer or CilExec 0.0.N version: "
                                + name, exception);
            }
        }
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
