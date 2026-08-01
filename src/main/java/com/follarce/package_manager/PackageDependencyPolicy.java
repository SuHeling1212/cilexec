package com.follarce.package_manager;

import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.port.PackageRepository;

import java.util.List;
import java.util.Objects;

/** Enforces exact distributed-file dependencies at every package installation boundary. */
public final class PackageDependencyPolicy {
    private PackageDependencyPolicy() {
    }

    public static void requireInstalled(PackageRepository packages,
                                        List<PackageIndex.Dependency> dependencies) {
        Objects.requireNonNull(packages, "packages");
        for (PackageIndex.Dependency dependency : List.copyOf(dependencies)) {
            if (!dependency.optional() && packages
                    .findReleaseByDatabaseFileHash(dependency.databaseFileHash()).isEmpty()) {
                throw new IllegalStateException("Required package dependency is not installed: "
                        + dependency.databaseFileHash().value());
            }
        }
    }
}
