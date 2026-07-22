package com.follarce.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared constructor checks for immutable domain values. */
public final class Invariant {
    private Invariant() {
    }

    public static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    public static String text(String value, String name) {
        required(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    public static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public static int nonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    public static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public static <T> List<T> list(List<T> values, String name) {
        required(values, name);
        try {
            return List.copyOf(values);
        } catch (NullPointerException exception) {
            throw new IllegalArgumentException(name + " must not contain null", exception);
        }
    }

    public static <K, V> Map<K, V> map(Map<K, V> values, String name) {
        required(values, name);
        try {
            return Map.copyOf(values);
        } catch (NullPointerException exception) {
            throw new IllegalArgumentException(name + " must not contain null", exception);
        }
    }
}
