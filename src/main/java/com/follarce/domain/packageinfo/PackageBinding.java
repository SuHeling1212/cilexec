package com.follarce.domain.packageinfo;

import com.follarce.domain.Invariant;

import java.time.Instant;
import java.util.UUID;

/** Declarative environment binding to an exact immutable package hash. */
public record PackageBinding(
        UUID environmentId,
        String binding,
        PackageRelease.Hash packageHash,
        Instant createdAt
) {
    public PackageBinding {
        Invariant.required(environmentId, "environmentId");
        binding = Invariant.text(binding, "binding");
        Invariant.check(binding.matches("[A-Za-z_][A-Za-z0-9_]*"),
                "binding must be an FCL identifier");
        Invariant.required(packageHash, "packageHash");
        Invariant.required(createdAt, "createdAt");
    }
}
