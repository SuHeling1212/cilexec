package com.follarce.domain.port;

import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.vfs.ObjectHash;

import java.util.Optional;
import java.util.List;
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

    enum ReleaseWriteResult {
        REGISTERED,
        ALREADY_PRESENT,
        COORDINATE_CONFLICT
    }
}
