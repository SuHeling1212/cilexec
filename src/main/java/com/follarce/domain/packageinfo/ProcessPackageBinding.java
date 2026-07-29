package com.follarce.domain.packageinfo;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.UUID;

/** Exact package resolution pinned to a running process. */
public record ProcessPackageBinding(
        UUID processUid,
        String importName,
        UUID environmentId,
        PackageRelease.Hash packageHash,
        Instant resolvedAt
) {
    public ProcessPackageBinding {
        Invariant.required(processUid, "processUid");
        importName = Invariant.text(importName, "importName");
        Invariant.check(importName.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                        || importName.matches("[0-9a-f]{64}"),
                "importName must be an FCL identifier or lowercase SHA-256");
        Invariant.required(environmentId, "environmentId");
        Invariant.required(packageHash, "packageHash");
        Invariant.required(resolvedAt, "resolvedAt");
    }
}
