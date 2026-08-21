package com.follarce.version;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The product release identity embedded by Maven from the one repository version setting.
 *
 * <p>During the 0.0.x line, the patch component is deliberately also the persisted-format and
 * Flyway schema number: release {@code 0.0.3} therefore writes format {@code 3} and requires
 * schema {@code V003}. Historic migration class names remain immutable identities.
 */
public final class ReleaseVersion {
    private static final String RESOURCE = "/build-info.properties";

    private ReleaseVersion() {}

    public static String current() {
        try (InputStream input = ReleaseVersion.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing " + RESOURCE);
            Properties properties = new Properties();
            properties.load(input);
            String version = properties.getProperty("application.version");
            if (version == null || version.isBlank() || version.contains("${")) {
                throw new IllegalStateException("Invalid build property: application.version");
            }
            schemaNumber(version.trim());
            return version.trim();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + RESOURCE, exception);
        }
    }

    /** Converts the release's {@code 0.0.N} identity into its schema/runtime format number. */
    public static int schemaNumber(String value) {
        if (value == null) throw new IllegalArgumentException("version must not be null");
        String[] parts = value.trim().split("\\.", -1);
        if (parts.length != 3 || !"0".equals(parts[0]) || !"0".equals(parts[1])
                || !parts[2].matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException(
                    "CilExec release version must use the 0.0.N form: " + value);
        }
        try {
            return Integer.parseInt(parts[2]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("CilExec release number is too large: " + value,
                    exception);
        }
    }
}
