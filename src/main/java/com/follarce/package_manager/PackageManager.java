package com.follarce.package_manager;

import com.follarce.auth.Authorization;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.PackageRepository;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.domain.port.UserTransactionRunner;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Imports immutable package databases; package identity is the SHA-256, never a name. */
public final class PackageManager {
    private final UserTransactionRunner transactions;
    private final SqlitePackageReader packageReader;
    private final Clock clock;

    public PackageManager(UserTransactionRunner transactions,
                          SqlitePackageReader packageReader,
                          Clock clock) {
        this.transactions = java.util.Objects.requireNonNull(transactions, "transactions");
        this.packageReader = java.util.Objects.requireNonNull(packageReader, "packageReader");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /** SQLite inspection is deliberately outside the PostgreSQL transaction. */
    public PackageRelease importDatabase(
            UUID ownerId,
            byte[] databaseBytes
    ) {
        PackageDescriptor descriptor = packageReader.inspect(databaseBytes);
        Instant now = clock.instant();
        ObjectHash fileHash = new ObjectHash(descriptor.databaseFileHash());
        StoredObject object = new StoredObject(fileHash, databaseBytes.length,
                "application/vnd.sqlite3", new BinaryContent(databaseBytes), now);
        PackageRelease release = new PackageRelease(
                new PackageRelease.Coordinate(descriptor.namespace(), descriptor.name(),
                        descriptor.version()),
                new PackageRelease.Hash(new ObjectHash(descriptor.packageHash())),
                fileHash,
                fileHash,
                now
        );
        PackageIndex packageIndex = new PackageIndex(release, descriptor.moduleIndex(),
                descriptor.dependencyIndex(), descriptor.entrypoints(), descriptor.exports(),
                descriptor.capabilityIndex());
        return transactions.inUserTransaction(ownerId, Isolation.SERIALIZABLE, transaction -> {
            Authorization.require(transaction, ownerId, Capability.PACKAGE_IMPORT);
            PackageCapabilityPolicy.inspect(databaseBytes, descriptor)
                    .requireUserCapabilities(transaction.auth().capabilities(ownerId));
            PackageDependencyPolicy.requireInstalled(transaction.packages(),
                    packageIndex.dependencies());
            transaction.vfs().saveObject(object);
            PackageRepository.ReleaseWriteResult result =
                    transaction.packages().registerRelease(packageIndex);
            if (result == PackageRepository.ReleaseWriteResult.COORDINATE_CONFLICT) {
                throw new PackageCoordinateConflictException(release.coordinate());
            }
            PackageRelease persisted = transaction.packages().findRelease(release.coordinate())
                    .orElseThrow(() -> new IllegalStateException("Registered package is missing"));
            transaction.audit().append(audit(ownerId, "package.import", persisted, now,
                    Map.of("writeResult", result.name(),
                            "databaseFileHash", fileHash.value(),
                            "modules", Integer.toString(packageIndex.modules().size()),
                            "dependencies", Integer.toString(packageIndex.dependencies().size()),
                            "capabilities", Integer.toString(packageIndex.capabilities().size()))));
            return persisted;
        });
    }

    private static AuditEvent audit(UUID ownerId, String action, PackageRelease release,
                                    Instant at, Map<String, String> details) {
        return new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER,
                ownerId.toString(), action, "package.release",
                release.packageHash().value().value(), AuditEvent.Result.SUCCEEDED,
                details, at);
    }
}
