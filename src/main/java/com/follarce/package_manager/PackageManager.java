package com.follarce.package_manager;

import com.follarce.domain.audit.AuditEvent;
import com.follarce.auth.Authorization;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.packageinfo.PackageBinding;
import com.follarce.domain.packageinfo.PackageEnvironment;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.port.Isolation;
import com.follarce.domain.port.PackageRepository;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.persistence.postgres.transaction.UserTransactionExecutor;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Imports immutable package databases and manages exact-hash bindings. */
public final class PackageManager {
    private final UserTransactionExecutor transactions;
    private final SqlitePackageReader packageReader;
    private final Clock clock;

    public PackageManager(UserTransactionExecutor transactions,
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

    public PackageEnvironment createEnvironment(
            UUID ownerId,
            String name,
            Optional<UUID> parentEnvironmentId
    ) {
        Instant now = clock.instant();
        PackageEnvironment environment = new PackageEnvironment(UUID.randomUUID(), ownerId, name,
                parentEnvironmentId, PackageEnvironment.Status.ACTIVE, now);
        return transactions.inUserTransaction(ownerId, Isolation.READ_COMMITTED, transaction -> {
            Authorization.require(transaction, ownerId, Capability.PACKAGE_BIND);
            parentEnvironmentId.ifPresent(parentId -> requireActiveEnvironment(
                    transaction.packages(), ownerId, parentId));
            transaction.packages().saveEnvironment(environment);
            transaction.audit().append(new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER,
                    ownerId.toString(), "package.environment.create", "package.environment",
                    environment.environmentId().toString(), AuditEvent.Result.SUCCEEDED,
                    Map.of("name", name), now));
            return environment;
        });
    }

    public PackageBinding bind(
            UUID ownerId,
            UUID environmentId,
            String bindingName,
            PackageRelease.Hash packageHash
    ) {
        Instant now = clock.instant();
        PackageBinding binding = new PackageBinding(environmentId, bindingName, packageHash, now);
        return transactions.inUserTransaction(ownerId, Isolation.SERIALIZABLE, transaction -> {
            Authorization.require(transaction, ownerId, Capability.PACKAGE_BIND);
            requireActiveEnvironment(transaction.packages(), ownerId, environmentId);
            transaction.packages().findRelease(packageHash)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown package hash"));
            transaction.packages().saveBinding(binding);
            transaction.audit().append(new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER,
                    ownerId.toString(), "package.bind", "package.environment",
                    environmentId.toString(), AuditEvent.Result.SUCCEEDED,
                    Map.of("binding", bindingName,
                            "packageHash", packageHash.value().value()), now));
            return binding;
        });
    }

    /** Resolves once and persists the exact hash so later environment changes cannot affect a process. */
    public ProcessPackageBinding resolveForProcess(
            UUID ownerId,
            UUID processUid,
            UUID environmentId,
            String importName
    ) {
        Instant now = clock.instant();
        return transactions.inUserTransaction(ownerId, Isolation.SERIALIZABLE, transaction -> {
            Authorization.require(transaction, ownerId, Capability.PACKAGE_BIND);
            CilProcess process = transaction.processes().findByUid(processUid)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown process"));
            if (!process.ownerId().equals(ownerId)) {
                throw new SecurityException("Process belongs to another user");
            }
            if (process.isTerminal()) {
                throw new IllegalStateException("A terminal process cannot import packages");
            }
            requireActiveEnvironment(transaction.packages(), ownerId, environmentId);
            Optional<ProcessPackageBinding> existing =
                    transaction.packages().findProcessBinding(processUid, importName);
            if (existing.isPresent()) {
                ProcessPackageBinding pinned = existing.orElseThrow();
                if (!pinned.environmentId().equals(environmentId)) {
                    throw new IllegalStateException(
                            "Process import is already pinned to another environment");
                }
                return pinned;
            }
            PackageBinding declared = transaction.packages().findBinding(environmentId, importName)
                    .orElseThrow(() -> new IllegalArgumentException("Package binding is not declared"));
            ProcessPackageBinding resolved = new ProcessPackageBinding(processUid, importName,
                    environmentId, declared.packageHash(), now);
            transaction.packages().saveProcessBinding(resolved);
            return resolved;
        });
    }

    private static PackageEnvironment requireActiveEnvironment(PackageRepository packages,
                                                               UUID ownerId,
                                                               UUID environmentId) {
        PackageEnvironment environment = packages.findEnvironment(environmentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown package environment"));
        if (!environment.ownerId().equals(ownerId)) {
            throw new SecurityException("Package environment belongs to another user");
        }
        if (environment.status() != PackageEnvironment.Status.ACTIVE) {
            throw new IllegalStateException("Package environment is archived");
        }
        return environment;
    }

    private static AuditEvent audit(UUID ownerId, String action, PackageRelease release,
                                    Instant at, Map<String, String> details) {
        return new AuditEvent(UUID.randomUUID(), AuditEvent.ActorType.USER, ownerId.toString(),
                action, "package.release", release.coordinate().key(),
                AuditEvent.Result.SUCCEEDED, details, at);
    }
}
