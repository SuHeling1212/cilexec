package com.follarce.domain.packageinfo;

import com.follarce.domain.Invariant;
import com.follarce.domain.vfs.ObjectHash;

import java.time.Instant;
import java.util.Optional;

/** A file or directory inside one user's private package data space. */
public record PackageDataEntry(
        String relativePath,
        String entryType,
        Optional<ObjectHash> objectHash,
        long byteSize,
        long stateVersion,
        Optional<Instant> updatedAt
) {
    private static final java.util.Set<String> ENTRY_TYPES = java.util.Set.of("FILE", "DIRECTORY");

    public PackageDataEntry {
        relativePath = Invariant.text(relativePath, "relativePath");
        entryType = Invariant.text(entryType, "entryType");
        Invariant.check(ENTRY_TYPES.contains(entryType),
                "package data entry type must be FILE or DIRECTORY");
        objectHash = Invariant.required(objectHash, "objectHash");
        Invariant.check(byteSize >= 0, "byteSize must not be negative");
        Invariant.check(stateVersion >= 0, "stateVersion must not be negative");
        updatedAt = Invariant.required(updatedAt, "updatedAt");
    }

    public boolean isDirectory() {
        return entryType.equals("DIRECTORY");
    }
}
