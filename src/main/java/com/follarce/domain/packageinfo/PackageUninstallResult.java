package com.follarce.domain.packageinfo;

import com.follarce.domain.Invariant;

/** Summary of one atomic per-user package uninstall transaction. */
public record PackageUninstallResult(
        boolean removed,
        int packagesRemoved,
        int dependenciesRemoved,
        int processesRemoved,
        int bindingsRemoved,
        int cacheFilesRemoved,
        int dataNodesRemoved,
        int releasesPurged,
        int objectsPurged
) {
    public PackageUninstallResult {
        Invariant.check(packagesRemoved >= 0, "packagesRemoved must not be negative");
        Invariant.check(dependenciesRemoved >= 0, "dependenciesRemoved must not be negative");
        Invariant.check(processesRemoved >= 0, "processesRemoved must not be negative");
        Invariant.check(bindingsRemoved >= 0, "bindingsRemoved must not be negative");
        Invariant.check(cacheFilesRemoved >= 0, "cacheFilesRemoved must not be negative");
        Invariant.check(dataNodesRemoved >= 0, "dataNodesRemoved must not be negative");
        Invariant.check(releasesPurged >= 0, "releasesPurged must not be negative");
        Invariant.check(objectsPurged >= 0, "objectsPurged must not be negative");
    }
}
