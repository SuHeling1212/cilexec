package com.follarce.domain.packageinfo;

import com.google.gson.annotations.SerializedName;

import java.util.Locale;

/** Whether a package is directly runnable software or an import-only dependency library. */
public enum PackageKind {
    @SerializedName("application")
    APPLICATION("application"),
    @SerializedName("library")
    LIBRARY("library");

    private final String wireName;

    PackageKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static PackageKind parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("package kind is required");
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "application" -> APPLICATION;
            case "library" -> LIBRARY;
            default -> throw new IllegalArgumentException("Unsupported package kind: " + value);
        };
    }
}
