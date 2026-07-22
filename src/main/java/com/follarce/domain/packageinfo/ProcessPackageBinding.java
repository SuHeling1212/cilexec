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
        Invariant.required(environmentId, "environmentId");
        Invariant.required(packageHash, "packageHash");
        Invariant.required(resolvedAt, "resolvedAt");
    }
}
