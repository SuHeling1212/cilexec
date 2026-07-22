package com.follarce.domain.packageinfo;

import com.follarce.domain.Invariant;
import com.follarce.domain.vfs.ObjectHash;

import java.time.Instant;

/** Immutable SQLite package release stored as one object-store object. */
public record PackageRelease(
        Coordinate coordinate,
        Hash packageHash,
        ObjectHash databaseObjectHash,
        ObjectHash databaseFileHash,
        SignatureStatus signatureStatus,
        Instant importedAt
) {
    public PackageRelease {
        Invariant.required(coordinate, "coordinate");
        Invariant.required(packageHash, "packageHash");
        Invariant.required(databaseObjectHash, "databaseObjectHash");
        Invariant.required(databaseFileHash, "databaseFileHash");
        Invariant.check(databaseObjectHash.equals(databaseFileHash),
                "database object hash must identify the original database bytes");
        Invariant.required(signatureStatus, "signatureStatus");
        Invariant.required(importedAt, "importedAt");
    }

    public record Coordinate(String namespace, String name, String version) {
        public Coordinate {
            namespace = component(namespace, "namespace");
            name = component(name, "name");
            version = Invariant.text(version, "version");
            Invariant.check(version.length() <= 128, "version is too long");
        }

        private static String component(String value, String name) {
            value = Invariant.text(value, name);
            Invariant.check(value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"),
                    name + " contains unsupported characters");
            return value;
        }

        public String key() {
            return namespace + "/" + name + "/" + version;
        }
    }

    public record Hash(ObjectHash value) {
        public Hash {
            Invariant.required(value, "value");
        }
    }

    public enum SignatureStatus {
        UNSIGNED,
        VALID_TRUSTED,
        VALID_UNTRUSTED,
        INVALID,
        REVOKED
    }
}
