package com.follarce.domain.port;

import com.follarce.domain.packageinfo.PackageDataEntry;
import com.follarce.domain.packageinfo.PackageDataUsage;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageInstallation;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.PackageUninstallResult;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.vfs.ObjectHash;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PackageRepository {
    ReleaseWriteResult registerRelease(PackageIndex packageIndex);

    Optional<PackageRelease> findRelease(PackageRelease.Hash packageHash);

    Optional<PackageRelease> findRelease(PackageRelease.Coordinate coordinate);

    /** Resolves the SHA-256 of the exact distributed .db file. */
    default Optional<PackageRelease> findReleaseByDatabaseFileHash(ObjectHash databaseFileHash) {
        return findReleases().stream()
                .filter(release -> release.databaseFileHash().equals(databaseFileHash))
                .findFirst();
    }

    default List<PackageRelease> findReleases() {
        throw new UnsupportedOperationException("Package release listing is not implemented");
    }

    void saveProcessBinding(ProcessPackageBinding binding);

    Optional<ProcessPackageBinding> findProcessBinding(UUID processUid, String importName);

    default List<ProcessPackageBinding> findProcessBindings(UUID processUid) {
        return List.of();
    }

    /** Drops only the imported-package pins of a process whose FCL image is being replaced. */
    default void removeProcessBindings(UUID processUid) {
        throw new UnsupportedOperationException("Process package binding removal is not implemented");
    }

    // ------------------------------------------------------------------
    // Per-user installation ledger
    // ------------------------------------------------------------------

    /**
     * Atomically publishes one user installation root with its complete exact-hash
     * dependency closure. The root file hash identifies the acquired package; each
     * member references an already registered immutable release. Private data
     * spaces are created for every member on first reference.
     */
    default boolean publishInstallation(UUID installationId, UUID ownerId,
                                         ObjectHash rootFileHash, String source,
                                         List<PackageInstallation.Member> members,
                                         Instant at) {
        throw new UnsupportedOperationException("Installation publication is not implemented");
    }

    /** Lists the current user's installation roots with their member closures. */
    default List<PackageInstallation> findInstallations(UUID ownerId) {
        throw new UnsupportedOperationException("Installation listing is not implemented");
    }

    /** Returns a release only when the current user effectively installed it. */
    default Optional<PackageRelease> findInstalledReleaseByDatabaseFileHash(
            UUID ownerId, ObjectHash databaseFileHash) {
        throw new UnsupportedOperationException("Installed release lookup is not implemented");
    }

    /** Lists releases effectively installed for the current user. */
    default List<PackageRelease> findInstalledReleases(UUID ownerId) {
        throw new UnsupportedOperationException("Installed release listing is not implemented");
    }

    /**
     * Atomically uninstalls one package for the current user. The operation
     * purges dependent roots and affected processes only when forced, removes
     * private data and installation state, and garbage-collects globally
     * unreferenced release payloads.
     */
    default PackageUninstallResult uninstall(UUID ownerId, ObjectHash databaseFileHash,
                                             boolean force, UUID callerProcessUid) {
        throw new UnsupportedOperationException("Package uninstallation is not implemented");
    }

    // ------------------------------------------------------------------
    // Per-user per-package private data
    // ------------------------------------------------------------------

    default PackageDataUsage findDataUsage(UUID ownerId, ObjectHash databaseFileHash) {
        throw new UnsupportedOperationException("Package data usage is not implemented");
    }

    /** Returns null when the file does not exist in the user's package data space. */
    default byte[] readDataEntry(UUID ownerId, ObjectHash databaseFileHash, String path) {
        throw new UnsupportedOperationException("Package data reads are not implemented");
    }

    /** Creates or CAS-replaces one package data file, enforcing the effective quota. */
    default PackageDataEntry writeDataEntry(UUID ownerId, ObjectHash databaseFileHash,
                                            String path, byte[] content, String mediaType,
                                            long expectedVersion) {
        throw new UnsupportedOperationException("Package data writes are not implemented");
    }

    /** CAS-appends bytes to one package data file, enforcing the effective quota. */
    default PackageDataEntry appendDataEntry(UUID ownerId, ObjectHash databaseFileHash,
                                             String path, byte[] content, long expectedVersion) {
        throw new UnsupportedOperationException("Package data appends are not implemented");
    }

    default List<PackageDataEntry> listDataEntries(UUID ownerId, ObjectHash databaseFileHash,
                                                   String path) {
        throw new UnsupportedOperationException("Package data listing is not implemented");
    }

    default boolean removeDataEntry(UUID ownerId, ObjectHash databaseFileHash, String path) {
        throw new UnsupportedOperationException("Package data removal is not implemented");
    }

    default PackageDataEntry renameDataEntry(UUID ownerId, ObjectHash databaseFileHash,
                                             String from, String to) {
        throw new UnsupportedOperationException("Package data rename is not implemented");
    }

    default void mkdirDataEntry(UUID ownerId, ObjectHash databaseFileHash, String path) {
        throw new UnsupportedOperationException("Package data mkdir is not implemented");
    }

    default long clearDataEntries(UUID ownerId, ObjectHash databaseFileHash) {
        throw new UnsupportedOperationException("Package data clearing is not implemented");
    }

    /** Clears the descendants of one package-private directory while retaining the directory. */
    default long clearDataDirectory(UUID ownerId, ObjectHash databaseFileHash, String path) {
        throw new UnsupportedOperationException("Package data directory clearing is not implemented");
    }

    default long findDataQuota(UUID ownerId, ObjectHash databaseFileHash) {
        throw new UnsupportedOperationException("Package data quota is not implemented");
    }

    /** Administrator-only quota override for another user's package data space. */
    default void setDataQuota(UUID administratorId, UUID ownerId, ObjectHash databaseFileHash,
                              long quotaBytes) {
        throw new UnsupportedOperationException("Package data quota override is not implemented");
    }

    /** Administrator-only removal of a quota override. */
    default void clearDataQuota(UUID administratorId, UUID ownerId, ObjectHash databaseFileHash) {
        throw new UnsupportedOperationException("Package data quota override is not implemented");
    }

    /**
     * Registers one VFS node as a package-owned artifact (market cache or
     * package data) so uninstallation can delete it and count it as a removed
     * cache file. Ordinary user files are never registered.
     */
    default void registerManagedNode(UUID ownerId, UUID nodeId, ObjectHash databaseFileHash,
                                     String purpose) {
        throw new UnsupportedOperationException("Managed node registration is not implemented");
    }

    /**
     * Administrator consistency report over package lifecycle state. Returns
     * {@code {ok: boolean, issues: [...]}}; healthy databases report an empty
     * issue list.
     */
    default Map<String, Object> recoverReport(UUID administratorId) {
        throw new UnsupportedOperationException("Package recovery report is not implemented");
    }

    enum ReleaseWriteResult {
        REGISTERED,
        ALREADY_PRESENT,
        COORDINATE_CONFLICT
    }
}
