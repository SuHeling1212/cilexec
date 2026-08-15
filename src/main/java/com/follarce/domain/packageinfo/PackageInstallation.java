package com.follarce.domain.packageinfo;

import com.follarce.domain.Invariant;
import com.follarce.domain.vfs.ObjectHash;

import java.time.Instant;
import java.util.List;

/** One user's explicit acquisition of an exact immutable package release. */
public record PackageInstallation(
        String installationId,
        java.util.UUID ownerId,
        PackageRelease.Coordinate rootCoordinate,
        ObjectHash rootFileHash,
        String source,
        Instant installedAt,
        List<Member> members
) {
    public PackageInstallation {
        installationId = Invariant.required(java.util.UUID.fromString(
                Invariant.text(installationId, "installationId")), "installationId").toString();
        Invariant.required(ownerId, "ownerId");
        Invariant.required(rootCoordinate, "rootCoordinate");
        Invariant.required(rootFileHash, "rootFileHash");
        source = Invariant.text(source, "source");
        Invariant.check(List.of("LOCAL", "MARKET", "LEGACY").contains(source),
                "installation source must be LOCAL, MARKET, or LEGACY");
        Invariant.required(installedAt, "installedAt");
        members = Invariant.list(members, "members");
    }

    /** One exact-hash dependency recorded in an installation closure. */
    public record Member(
            PackageRelease.Coordinate coordinate,
            ObjectHash packageHash,
            ObjectHash fileHash,
            int dependencyDepth,
            boolean optional
    ) {
        public Member {
            Invariant.required(coordinate, "coordinate");
            Invariant.required(packageHash, "packageHash");
            Invariant.required(fileHash, "fileHash");
            Invariant.check(dependencyDepth >= 0, "dependencyDepth must not be negative");
        }
    }
}
