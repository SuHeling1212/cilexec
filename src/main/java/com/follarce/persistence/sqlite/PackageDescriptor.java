package com.follarce.persistence.sqlite;

import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageKind;
import com.follarce.domain.packageinfo.PackageRelease;

import java.util.List;
import java.util.Objects;

/** Validated immutable metadata extracted from a package database. */
public record PackageDescriptor(
        String namespace,
        String name,
        String version,
        String languageVersion,
        PackageKind kind,
        String packageHash,
        String databaseFileHash,
        List<PackageIndex.Module> moduleIndex,
        List<PackageIndex.Dependency> dependencyIndex,
        List<PackageIndex.Entrypoint> entrypoints,
        List<PackageIndex.Export> exports,
        List<PackageIndex.CapabilityRequirement> capabilityIndex
) {
    public PackageDescriptor {
        namespace = require(namespace, "namespace");
        name = require(name, "name");
        version = require(version, "version");
        languageVersion = require(languageVersion, "languageVersion");
        try {
            PackageRelease.Coordinate coordinate = new PackageRelease.Coordinate(
                    namespace, name, version);
            namespace = coordinate.namespace();
            name = coordinate.name();
            version = coordinate.version();
        } catch (IllegalArgumentException invalid) {
            throw new PackageDatabaseException("Package coordinate is invalid", invalid);
        }
        if (languageVersion.length() > 128
                || languageVersion.chars().anyMatch(Character::isISOControl)) {
            throw new PackageDatabaseException("languageVersion is invalid");
        }
        kind = Objects.requireNonNull(kind, "kind");
        packageHash = requireHash(packageHash, "packageHash");
        databaseFileHash = requireHash(databaseFileHash, "databaseFileHash");
        moduleIndex = List.copyOf(Objects.requireNonNull(moduleIndex, "moduleIndex"));
        dependencyIndex = List.copyOf(Objects.requireNonNull(dependencyIndex,
                "dependencyIndex"));
        entrypoints = List.copyOf(Objects.requireNonNull(entrypoints, "entrypoints"));
        exports = List.copyOf(Objects.requireNonNull(exports, "exports"));
        capabilityIndex = List.copyOf(Objects.requireNonNull(capabilityIndex,
                "capabilityIndex"));
    }

    public String coordinate() {
        return namespace + "/" + name + "/" + version;
    }

    public List<String> modules() {
        return moduleIndex.stream().map(PackageIndex.Module::name).toList();
    }

    public List<String> dependencies() {
        return dependencyIndex.stream().map(PackageIndex.Dependency::sha256).toList();
    }

    public List<String> capabilities() {
        return capabilityIndex.stream().map(PackageIndex.CapabilityRequirement::key).toList();
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new PackageDatabaseException(name + " must not be blank");
        }
        return value;
    }

    private static String requireHash(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new PackageDatabaseException(name + " must be a lowercase SHA-256 hash");
        }
        return value;
    }
}
