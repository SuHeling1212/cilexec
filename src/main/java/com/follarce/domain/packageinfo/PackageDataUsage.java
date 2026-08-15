package com.follarce.domain.packageinfo;

import com.follarce.domain.Invariant;
import com.follarce.domain.vfs.ObjectHash;

import java.time.Instant;

/** Effective usage and quota of one user's private package data space. */
public record PackageDataUsage(
        String spaceId,
        java.util.UUID ownerId,
        ObjectHash packageHash,
        ObjectHash databaseFileHash,
        long logicalBytes,
        long quota,
        long files,
        Instant updatedAt
) {
    public PackageDataUsage {
        spaceId = Invariant.required(java.util.UUID.fromString(
                Invariant.text(spaceId, "spaceId")), "spaceId").toString();
        Invariant.required(ownerId, "ownerId");
        Invariant.required(packageHash, "packageHash");
        Invariant.required(databaseFileHash, "databaseFileHash");
        Invariant.check(logicalBytes >= 0, "logicalBytes must not be negative");
        Invariant.check(quota >= 0, "quota must not be negative");
        Invariant.check(files >= 0, "files must not be negative");
        Invariant.required(updatedAt, "updatedAt");
    }
}
