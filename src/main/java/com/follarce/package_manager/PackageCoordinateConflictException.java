package com.follarce.package_manager;

import com.follarce.domain.packageinfo.PackageRelease;

/** The same immutable coordinate was already registered with a different logical hash. */
public final class PackageCoordinateConflictException extends RuntimeException {
    private final PackageRelease.Coordinate coordinate;

    public PackageCoordinateConflictException(PackageRelease.Coordinate coordinate) {
        super("Package coordinate is already bound to different content: " + coordinate.key());
        this.coordinate = coordinate;
    }

    public PackageRelease.Coordinate coordinate() {
        return coordinate;
    }
}
