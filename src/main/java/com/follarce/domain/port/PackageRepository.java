package com.follarce.domain.port;

import com.follarce.domain.packageinfo.PackageBinding;
import com.follarce.domain.packageinfo.PackageEnvironment;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.ProcessPackageBinding;

import java.util.Optional;
import java.util.UUID;

public interface PackageRepository {
    ReleaseWriteResult registerRelease(PackageIndex packageIndex);

    Optional<PackageRelease> findRelease(PackageRelease.Hash packageHash);

    Optional<PackageRelease> findRelease(PackageRelease.Coordinate coordinate);

    void saveEnvironment(PackageEnvironment environment);

    void saveBinding(PackageBinding binding);

    Optional<PackageBinding> findBinding(UUID environmentId, String binding);

    void saveProcessBinding(ProcessPackageBinding binding);

    Optional<ProcessPackageBinding> findProcessBinding(UUID processUid, String importName);

    enum ReleaseWriteResult {
        REGISTERED,
        ALREADY_PRESENT,
        COORDINATE_CONFLICT
    }
}
