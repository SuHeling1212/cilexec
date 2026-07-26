package com.follarce.package_manager;

import com.follarce.domain.packageinfo.PackageEnvironment;
import com.follarce.domain.port.PackageRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Stable environment identities used by both FCL installation and import resolution. */
public final class PackageEnvironments {
    public static final String DEFAULT_NAME = "default";

    private PackageEnvironments() {
    }

    public static UUID defaultId(UUID ownerId) {
        return UUID.nameUUIDFromBytes(("cilexec:package-environment:" + ownerId + ":"
                + DEFAULT_NAME).getBytes(StandardCharsets.UTF_8));
    }

    public static PackageEnvironment ensureDefault(PackageRepository packages, UUID ownerId,
                                                   Instant now) {
        UUID environmentId = defaultId(ownerId);
        Optional<PackageEnvironment> existing = packages.findEnvironment(environmentId);
        if (existing.isPresent()) return existing.orElseThrow();
        PackageEnvironment environment = new PackageEnvironment(environmentId, ownerId,
                DEFAULT_NAME, Optional.empty(), PackageEnvironment.Status.ACTIVE, now);
        packages.saveEnvironment(environment);
        return packages.findEnvironment(environmentId).orElse(environment);
    }
}
