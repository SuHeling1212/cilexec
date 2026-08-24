package com.follarce.application;

import com.follarce.auth.Authorization;
import com.follarce.auth.PasswordPolicy;
import com.follarce.auth.UsernamePolicy;
import com.follarce.domain.audit.AuditEvent;
import com.follarce.domain.auth.Capability;
import com.follarce.domain.auth.UserAccount;
import com.follarce.domain.effect.EffectRequest;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageInstallation;
import com.follarce.domain.packageinfo.PackageDataUsage;
import com.follarce.domain.packageinfo.PackageUninstallResult;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.ipc.IpcChannel;
import com.follarce.domain.ipc.IpcMessage;
import com.follarce.domain.ipc.IpcTopic;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.EnvironmentRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessInbox;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.program.Program;
import com.follarce.domain.timer.ProcessTimer;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.ipc.IpcService;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.VfsNode;
import com.follarce.domain.vfs.VfsFileLimits;
import com.follarce.fcl.FclBuiltins;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclInstruction;
import com.follarce.fcl.FclPath;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramCodec;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclScope;
import com.follarce.fcl.FclValues;
import com.follarce.fcl.TerminalModeState;
import com.follarce.fcl.FclSuspension;
import com.follarce.extension.JavaExtensionCatalog;
import com.follarce.extension.SourceExtensionIndex;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import com.follarce.package_manager.PackageCoordinateConflictException;
import com.follarce.package_manager.PackageBuilder;
import com.follarce.package_manager.PackageDataService;
import com.follarce.package_manager.PackageDependencyPolicy;
import com.follarce.market.client.MarketRuntimeFunctions;
import com.follarce.terminal.TerminalAccessService;
import com.follarce.terminal.TerminalDimensions;
import com.follarce.timer.TimerService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class FclPackageRuntimeFunctions extends FclRuntimeFunctions {
    FclPackageRuntimeFunctions(FclRuntimeFunctions source) { super(source); }

    protected void registerPackages() {
        registry.register("package", "info", args -> {
                    Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
                    return packageDetails(requirePackage(args, "package.info"));
                })
                .register("package", "list", args -> {
                    arity(args, 0, "package.list");
                    Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
                    return transaction.packages().findInstalledReleases(process.ownerId()).stream()
                            .map(FclRuntimeFunctions::packageMap).toList();
                })
                .register("package", "install", args -> installPackage(args))
                .register("package", "uninstall", args -> uninstallPackage(args))
                .register("package", "build", args -> buildPackage(args))
                .register("package", "run", args -> runPackage(args))
                .register("package", "verify", args -> {
                    Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
                    PackageRelease release = requirePackage(args, "package.verify");
                    StoredObject object = transaction.vfs().findObject(release.databaseObjectHash())
                            .orElseThrow(() -> new FclRuntimeException(
                                    "Package database object is missing"));
                    boolean hashMatches = ObjectHash.sha256(object.content())
                            .equals(release.databaseFileHash());
                    Map<String, Object> verification = new LinkedHashMap<>(
                            packageDetails(release));
                    verification.put("valid", hashMatches);
                    verification.put("hashMatches", hashMatches);
                    return Map.copyOf(verification);
                })
                .register("package", "resource", args -> {
                    arity(args, 2, "package.resource");
                    Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
                    PackageRelease release = requirePackage(List.of(args.getFirst()),
                            "package.resource");
                    StoredObject object = transaction.vfs().findObject(release.databaseObjectHash())
                            .orElseThrow(() -> new FclRuntimeException(
                                    "Package database object is missing"));
                    byte[] content = new SqlitePackageReader().readResource(object.content().bytes(),
                            string(args.get(1), "package.resource path"));
                    return decodeUtf8(content, "package.resource");
                })
                .register("package", "pin", args -> pinPackage(args))
                .register("package", "dataInfo", args -> packageDataInfo(args))
                .register("package", "dataList", args -> packageDataList(args))
                .register("package", "dataRead", args -> packageDataRead(args))
                .register("package", "dataExport", args -> packageDataExport(args))
                .register("package", "dataImport", args -> packageDataImport(args))
                .register("package", "clearData", args -> packageDataClear(args))
                .register("package", "dataQuota", args -> packageDataQuota(args))
                .register("package", "setDataQuota", args -> packageSetDataQuota(args))
                .register("package", "removeDataQuota", args -> packageClearDataQuota(args))
                .register("package", "recover", args -> {
                    arity(args, 0, "package.recover");
                    Authorization.requireAdministrator(transaction, process.ownerId());
                    Map<String, Object> report = transaction.packages()
                            .recoverReport(process.ownerId());
                    audit("package.recover", process.identity().processUid(), Map.of(
                            "ok", Boolean.toString(Boolean.TRUE.equals(report.get("ok"))),
                            "issues", Integer.toString(
                                    report.get("issues") instanceof List<?> issues
                                            ? issues.size() : 0)));
                    return Map.copyOf(report);
                });
    }

    /**
     * Private package data functions. The current package identity comes from the
     * linked function provenance carried by the invocation; top-level user code
     * and ordinary VFS paths can never address another package's data space.
     */
    protected void registerPackageData() {
        registry.registerContextual("packageData", "root", (args, invocation) -> {
            arity(args, 0, "packageData.root");
            return "package-data://" + requirePackageDataFile(invocation).value() + "/";
        }, "packageRoot");
        registry.registerContextual("packageData", "exists", (args, invocation) -> {
            arity(args, 1, "packageData.exists");
            ObjectHash fileHash = requirePackageDataFile(invocation);
            String path = string(args.getFirst(), "packageData.exists path");
            return packageDataEntryExists(fileHash, path);
        }, "packageExists");
        registry.registerContextual("packageData", "read", (args, invocation) -> {
            arity(args, 1, "packageData.read");
            ObjectHash fileHash = requirePackageDataFile(invocation);
            String path = string(args.getFirst(), "packageData.read path");
            byte[] content = transaction.packages().readDataEntry(
                    process.ownerId(), fileHash, path);
            if (content == null) throw new FclRuntimeException(
                    "Unknown package data file: " + path);
            return decodeUtf8(content, "packageData.read");
        }, "packageRead");
        registry.registerContextual("packageData", "readChunk", (args, invocation) -> {
            arity(args, 3, "packageData.readChunk");
            ObjectHash fileHash = requirePackageDataFile(invocation);
            String path = string(args.getFirst(), "packageData.readChunk path");
            long offset = integer(args.get(1), "packageData.readChunk offset");
            long maximum = integer(args.get(2), "packageData.readChunk maximumBytes");
            if (offset < 0 || maximum < 0 || maximum > MAX_IN_MEMORY_READ_BYTES) {
                throw new FclRuntimeException("Invalid packageData.readChunk range");
            }
            byte[] content = transaction.packages().readDataEntry(
                    process.ownerId(), fileHash, path);
            if (content == null) throw new FclRuntimeException(
                    "Unknown package data file: " + path);
            if (offset >= content.length) return "";
            long end = Math.min(content.length, offset + maximum);
            return decodeUtf8(java.util.Arrays.copyOfRange(content, (int) offset, (int) end),
                    "packageData.readChunk");
        }, "packageReadChunk");
        registry.registerContextual("packageData", "write", (args, invocation) -> {
            arity(args, 2, "packageData.write");
            ObjectHash fileHash = requirePackageDataFile(invocation);
            String path = string(args.getFirst(), "packageData.write path");
            byte[] content = string(args.get(1), "packageData.write value")
                    .getBytes(StandardCharsets.UTF_8);
            long expected = packageDataVersion(fileHash, path);
            transaction.packages().writeDataEntry(process.ownerId(), fileHash, path,
                    content, TEXT, expected);
            return true;
        }, "packageWrite");
        registry.registerContextual("packageData", "seedResource", (args, invocation) -> {
            arity(args, 2, "packageData.seedResource");
            ObjectHash fileHash = requirePackageDataFile(invocation);
            String resourcePath = string(args.getFirst(), "packageData.seedResource resource");
            String destination = string(args.get(1), "packageData.seedResource destination");
            if (packageDataEntryExists(fileHash, destination)) return false;
            String identity = invocation.packageIdentity();
            PackageRelease release = transaction.packages().findRelease(
                            new PackageRelease.Hash(new ObjectHash(identity)))
                    .orElseThrow(() -> new FclRuntimeException("Linked package release is missing"));
            StoredObject database = transaction.vfs().findObject(release.databaseObjectHash())
                    .orElseThrow(() -> new FclRuntimeException(
                            "Package database object is missing"));
            byte[] resource = new SqlitePackageReader().readResource(database.content().bytes(),
                    resourcePath);
            transaction.packages().writeDataEntry(process.ownerId(), fileHash, destination,
                    resource, TEXT, -1);
            return true;
        }, "packageSeedResource");
        registry.registerContextual("packageData", "append", (args, invocation) -> {
            arity(args, 2, "packageData.append");
            ObjectHash fileHash = requirePackageDataFile(invocation);
            String path = string(args.getFirst(), "packageData.append path");
            long expected = packageDataVersion(fileHash, path);
            if (expected < 0) {
                throw new FclRuntimeException("packageData.append target does not exist: "
                        + path);
            }
            byte[] content = string(args.get(1), "packageData.append value")
                    .getBytes(StandardCharsets.UTF_8);
            transaction.packages().appendDataEntry(process.ownerId(), fileHash, path,
                    content, expected);
            return true;
        }, "packageAppend");
        registry.registerContextual("packageData", "mkdir", (args, invocation) -> {
            arity(args, 1, "packageData.mkdir");
            ObjectHash fileHash = requirePackageDataFile(invocation);
            transaction.packages().mkdirDataEntry(process.ownerId(), fileHash,
                    string(args.getFirst(), "packageData.mkdir path"));
            return true;
        }, "packageMkdir");
        registry.registerContextual("packageData", "list", (args, invocation) -> {
            arity(args, 1, "packageData.list");
            ObjectHash fileHash = requirePackageDataFile(invocation);
            String path = string(args.getFirst(), "packageData.list path");
            return transaction.packages().listDataEntries(process.ownerId(), fileHash, path)
                    .stream().map(PackageDataEntryMap::of).toList();
        }, "packageList");
        registry.registerContextual("packageData", "remove", (args, invocation) -> {
            arity(args, 1, "packageData.remove");
            ObjectHash fileHash = requirePackageDataFile(invocation);
            return transaction.packages().removeDataEntry(process.ownerId(), fileHash,
                    string(args.getFirst(), "packageData.remove path"));
        }, "packageRemove");
        registry.registerContextual("packageData", "clear", (args, invocation) -> {
            arity(args, 1, "packageData.clear");
            ObjectHash fileHash = requirePackageDataFile(invocation);
            long removed = transaction.packages().clearDataDirectory(process.ownerId(), fileHash,
                    string(args.getFirst(), "packageData.clear path"));
            return Map.of("entriesRemoved", removed);
        }, "packageClear");
        registry.registerContextual("packageData", "rename", (args, invocation) -> {
            arity(args, 2, "packageData.rename");
            ObjectHash fileHash = requirePackageDataFile(invocation);
            transaction.packages().renameDataEntry(process.ownerId(), fileHash,
                    string(args.getFirst(), "packageData.rename source"),
                    string(args.get(1), "packageData.rename destination"));
            return true;
        }, "packageRename");
        registry.registerContextual("packageData", "size", (args, invocation) -> {
            arity(args, 1, "packageData.size");
            ObjectHash fileHash = requirePackageDataFile(invocation);
            String path = string(args.getFirst(), "packageData.size path");
            byte[] content = transaction.packages().readDataEntry(
                    process.ownerId(), fileHash, path);
            if (content == null) throw new FclRuntimeException(
                    "Unknown package data file: " + path);
            return (long) content.length;
        }, "packageSize");
        registry.registerContextual("packageData", "usage", (args, invocation) -> {
            arity(args, 0, "packageData.usage");
            ObjectHash fileHash = requirePackageDataFile(invocation);
            PackageDataUsage usage = transaction.packages().findDataUsage(
                    process.ownerId(), fileHash);
            return Map.of("logicalBytes", usage.logicalBytes(), "quota", usage.quota(),
                    "files", usage.files());
        }, "packageUsage");
    }

    protected record PackageDataEntryMap(String name, String type, long size) {
        static Map<String, Object> of(com.follarce.domain.packageinfo.PackageDataEntry entry) {
            return Map.of("name", entry.relativePath(), "type", entry.entryType(),
                    "size", entry.byteSize());
        }
    }

    /** Resolves the current package's private data space from linked provenance. */
    protected ObjectHash requirePackageDataFile(FclFunctionRegistry.Invocation invocation) {
        return currentPackageDataFile(invocation);
    }

    protected boolean packageDataEntryExists(ObjectHash fileHash, String path) {
        if (transaction.packages().readDataEntry(process.ownerId(), fileHash, path) != null) {
            return true;
        }
        int separator = path.lastIndexOf('/');
        String parent = separator <= 0 ? "" : path.substring(0, separator);
        String name = separator <= 0 ? path : path.substring(separator + 1);
        return transaction.packages().listDataEntries(process.ownerId(), fileHash, parent)
                .stream().anyMatch(entry -> entry.relativePath().equals(name));
    }

    /** Returns the durable CAS version of a package data file, or -1 when absent. */
    protected long packageDataVersion(ObjectHash fileHash, String path) {
        int separator = path.lastIndexOf('/');
        String parent = separator <= 0 ? "" : path.substring(0, separator);
        String name = separator <= 0 ? path : path.substring(separator + 1);
        return transaction.packages().listDataEntries(process.ownerId(), fileHash, parent)
                .stream()
                .filter(entry -> entry.relativePath().equals(name) && !entry.isDirectory())
                .mapToLong(com.follarce.domain.packageinfo.PackageDataEntry::stateVersion)
                .findFirst().orElse(-1);
    }

    protected void registerMarket() {
        new MarketRuntimeFunctions(new MarketRuntimeFunctions.Host() {
            @Override public String environment(String name) {
                return transaction.environment().findUser(process.ownerId(), name)
                        .or(() -> transaction.environment().findShared(name)).orElse(null);
            }

            @Override public void setEnvironment(String name, String value) {
                transaction.environment().saveUser(process.ownerId(), name, value, now);
            }

            @Override public boolean exists(String path) {
                return resolve(normalize(path)).isPresent();
            }

            @Override public void ensureDirectory(String path) {
                Optional<VfsNode> existing = resolve(normalize(path));
                if (existing.isEmpty()) {
                    createDirectory(path, process.ownerId());
                } else {
                    requireType(existing.orElseThrow(), VfsNode.Type.DIRECTORY,
                            "market storage");
                }
            }

            @Override public String readText(String path) {
                return FclPackageRuntimeFunctions.this.readText(path);
            }

            @Override public void writeText(String path, String content) {
                FclPackageRuntimeFunctions.this.writeText(path, content, false);
            }

            @Override public boolean removeFile(String path) {
                if (resolve(normalize(path)).isEmpty()) return false;
                return Boolean.TRUE.equals(remove(List.of(path), VfsNode.Type.FILE,
                        "market.removeFile"));
            }

            @Override public boolean fileMatches(String path, String sha256, long bytes) {
                return downloadedFileMatches(path, sha256, bytes);
            }

            @Override public Object httpGet(String url,
                                            FclFunctionRegistry.Invocation invocation) {
                return external(invocation, "network.http-get", Map.of("url", url),
                        idempotentPolicy(invocation, "MARKET-INDEX:" + url), true);
            }

            @Override public Object download(String url, String path,
                                             FclFunctionRegistry.Invocation invocation) {
                return FclPackageRuntimeFunctions.this.download(List.of(url, path), invocation);
            }

            @Override @SuppressWarnings("unchecked")
            public Map<String, Object> install(String path) {
                Object installed = installPackage(List.of(path));
                if (!(installed instanceof Map<?, ?> map)) {
                    throw new FclRuntimeException("Package installer returned an invalid result");
                }
                return (Map<String, Object>) map;
            }

            @Override
            public List<Map<String, Object>> marketInstallations() {
                return transaction.packages().findInstallations(process.ownerId()).stream()
                        .filter(installation -> installation.source().equals("MARKET"))
                        .map(installation -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("sha256", installation.rootFileHash().value());
                            item.put("coordinate", installation.rootCoordinate().key());
                            item.put("namespace", installation.rootCoordinate().namespace());
                            item.put("name", installation.rootCoordinate().name());
                            item.put("version", installation.rootCoordinate().version());
                            installation.members().stream()
                                    .filter(member -> member.dependencyDepth() == 0)
                                    .findFirst()
                                    .ifPresent(member -> item.put("packageHash",
                                            member.packageHash().value()));
                            return Map.copyOf(item);
                        }).toList();
            }

            @Override
            public void registerCacheNode(String path, String sha256) {
                VfsNode node = requireNode(normalize(path));
                requireType(node, VfsNode.Type.FILE, "market cache");
                transaction.packages().registerManagedNode(process.ownerId(), node.nodeId(),
                        new ObjectHash(sha256), "MARKET_CACHE");
            }
        }).register(registry);
    }

    protected Object installPackage(List<Object> args) {
        if (args.size() != 1) throw new FclRuntimeException(
                "package.install expects a VFS path to an immutable package database");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
        VfsNode node = requireNode(string(args.getFirst(), "package.install path"));
        requireType(node, VfsNode.Type.FILE, "package.install");
        ObjectHash sourceHash = node.currentObjectHash().orElseThrow();
        byte[] databaseBytes = readLogicalObject(sourceHash, MAX_PACKAGE_DATABASE_BYTES,
                "Package database");
        PackageDescriptor descriptor = new SqlitePackageReader().inspect(databaseBytes);
        com.follarce.package_manager.PackageCapabilityPolicy.inspect(
                databaseBytes, descriptor).requireUserCapabilities(
                transaction.auth().capabilities(process.ownerId()));
        ObjectHash fileHash = new ObjectHash(descriptor.databaseFileHash());
        StoredObject database = StoredObject.create(new BinaryContent(databaseBytes),
                "application/vnd.sqlite3", now);
        if (!fileHash.equals(database.objectHash())) {
            throw new FclRuntimeException("Package database hash changed during inspection");
        }
        transaction.vfs().saveObject(database);
        PackageRelease release = new PackageRelease(new PackageRelease.Coordinate(
                descriptor.namespace(), descriptor.name(), descriptor.version()),
                new PackageRelease.Hash(new ObjectHash(descriptor.packageHash())), fileHash,
                fileHash, now);
        PackageIndex index = new PackageIndex(release, descriptor.moduleIndex(),
                descriptor.dependencyIndex(), descriptor.entrypoints(), descriptor.exports(),
                descriptor.capabilityIndex());
        PackageDependencyPolicy.requireInstalled(transaction.packages(), index.dependencies());
        var result = transaction.packages().registerRelease(index);
        if (result == com.follarce.domain.port.PackageRepository.ReleaseWriteResult.COORDINATE_CONFLICT) {
            throw new PackageCoordinateConflictException(release.coordinate());
        }
        release = transaction.packages().findRelease(release.coordinate()).orElseThrow(
                () -> new IllegalStateException("Installed package release is missing"));
        List<PackageInstallation.Member> closure = installationClosure(release);
        transaction.packages().publishInstallation(UUID.randomUUID(), process.ownerId(),
                release.databaseFileHash(), "LOCAL", closure, now);
        audit("package.install", node.nodeId(), Map.of(
                "coordinate", release.coordinate().key(), "writeResult", result.name()));
        Map<String, Object> installed = new LinkedHashMap<>(packageMap(release));
        installed.putAll(descriptorMap(descriptor));
        return Map.copyOf(installed);
    }

    /**
     * Resolves the complete exact-hash dependency closure of one package release.
     * Required dependencies must already be installed; optional dependencies join
     * the closure only when their releases are installed.
     */
    protected List<PackageInstallation.Member> installationClosure(PackageRelease root) {
        Map<String, PackageInstallation.Member> members = new LinkedHashMap<>();
        Deque<PackageRelease> queue = new ArrayDeque<>();
        Map<String, Integer> depths = new LinkedHashMap<>();
        members.put(root.packageHash().value().value(), new PackageInstallation.Member(
                root.coordinate(), root.packageHash().value(), root.databaseFileHash(), 0, false));
        depths.put(root.packageHash().value().value(), 0);
        queue.add(root);
        while (!queue.isEmpty()) {
            PackageRelease current = queue.removeFirst();
            int depth = depths.getOrDefault(current.packageHash().value().value(), 0);
            StoredObject database = transaction.vfs().findObject(current.databaseObjectHash())
                    .orElseThrow(() -> new FclRuntimeException(
                            "Installed package database is missing"));
            PackageDescriptor descriptor = new SqlitePackageReader().inspect(
                    database.content().bytes());
            for (PackageIndex.Dependency dependency : descriptor.dependencyIndex()) {
                Optional<PackageRelease> installed = transaction.packages()
                        .findReleaseByDatabaseFileHash(dependency.databaseFileHash());
                if (installed.isEmpty()) continue;
                PackageRelease dependencyRelease = installed.orElseThrow();
                String key = dependencyRelease.packageHash().value().value();
                if (members.containsKey(key)) continue;
                if (members.size() >= 256) {
                    throw new FclRuntimeException("Package dependency closure exceeds 256 packages");
                }
                int next = Math.addExact(depth, 1);
                if (next > 64) {
                    throw new FclRuntimeException("Package dependency depth exceeds 64");
                }
                members.put(key, new PackageInstallation.Member(
                        dependencyRelease.coordinate(), dependencyRelease.packageHash().value(),
                        dependencyRelease.databaseFileHash(), next, dependency.optional()));
                depths.put(key, next);
                queue.addLast(dependencyRelease);
            }
        }
        return List.copyOf(members.values());
    }

    protected Object uninstallPackage(List<Object> args) {
        arity(args, 1, "package.uninstall");
        String packageId = string(args.getFirst(), "package.uninstall package");
        if (!packageId.matches("(?i)[0-9a-f]{64}")) {
            throw new FclRuntimeException(
                    "package.uninstall requires a 64-character package SHA-256");
        }
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        ObjectHash fileHash = new ObjectHash(packageId.toLowerCase(Locale.ROOT));
        // Report the exact dependent installations instead of relying on the database's
        // count-only rejection, so the caller can resolve every reference explicitly.
        List<String> dependents = transaction.packages()
                .findInstallations(process.ownerId()).stream()
                .filter(installation -> !installation.rootFileHash().equals(fileHash))
                .filter(installation -> installation.members().stream()
                        .anyMatch(member -> member.fileHash().equals(fileHash)))
                .map(installation -> installation.rootCoordinate().namespace() + "/"
                        + installation.rootCoordinate().name() + "/"
                        + installation.rootCoordinate().version())
                .sorted()
                .toList();
        if (!dependents.isEmpty()) {
            throw new FclRuntimeException("cannot uninstall: installed packages depend on it: "
                    + String.join(", ", dependents)
                    + "; uninstall the dependent packages first");
        }
        PackageUninstallResult result = transaction.packages().uninstall(
                process.ownerId(), fileHash, false, process.identity().processUid());
        audit("package.uninstall", process.identity().processUid(), Map.of(
                "packageFileSha256", fileHash.value(),
                "removed", Boolean.toString(result.removed()),
                "packagesRemoved", Integer.toString(result.packagesRemoved()),
                "dependenciesRemoved", Integer.toString(result.dependenciesRemoved()),
                "processesRemoved", Integer.toString(result.processesRemoved()),
                "dataNodesRemoved", Integer.toString(result.dataNodesRemoved()),
                "releasesPurged", Integer.toString(result.releasesPurged()),
                "objectsPurged", Integer.toString(result.objectsPurged())));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("removed", result.removed());
        summary.put("packagesRemoved", (long) result.packagesRemoved());
        summary.put("dependenciesRemoved", (long) result.dependenciesRemoved());
        summary.put("processesRemoved", (long) result.processesRemoved());
        summary.put("bindingsRemoved", (long) result.bindingsRemoved());
        summary.put("cacheFilesRemoved", (long) result.cacheFilesRemoved());
        summary.put("dataNodesRemoved", (long) result.dataNodesRemoved());
        summary.put("releasesPurged", (long) result.releasesPurged());
        summary.put("objectsPurged", (long) result.objectsPurged());
        return Map.copyOf(summary);
    }

    protected ObjectHash requireInstalledFileHash(List<Object> args, String function) {
        if (args.isEmpty() || args.size() > 2) throw new FclRuntimeException(
                function + " expects a package SHA-256 and optional target user");
        String packageId = string(args.getFirst(), function + " package");
        if (!packageId.matches("(?i)[0-9a-f]{64}")) {
            throw new FclRuntimeException(
                    function + " requires a 64-character package SHA-256");
        }
        return new ObjectHash(packageId.toLowerCase(Locale.ROOT));
    }

    protected Object packageDataInfo(List<Object> args) {
        ObjectHash fileHash = requireInstalledFileHash(args, "package.dataInfo");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        PackageDataUsage usage = transaction.packages().findDataUsage(
                process.ownerId(), fileHash);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("spaceId", usage.spaceId());
        info.put("packageHash", usage.packageHash().value());
        info.put("databaseFileSha256", usage.databaseFileHash().value());
        info.put("logicalBytes", usage.logicalBytes());
        info.put("quota", usage.quota());
        info.put("files", usage.files());
        info.put("updatedAt", usage.updatedAt().toString());
        return Map.copyOf(info);
    }

    protected Object packageDataList(List<Object> args) {
        if (args.size() < 1 || args.size() > 2) throw new FclRuntimeException(
                "package.dataList expects a package SHA-256 and optional path");
        ObjectHash fileHash = requireInstalledFileHash(args, "package.dataList");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        String path = args.size() == 2
                ? string(args.get(1), "package.dataList path") : "";
        return transaction.packages().listDataEntries(process.ownerId(), fileHash, path)
                .stream().map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", entry.relativePath());
                    item.put("type", entry.entryType());
                    item.put("size", entry.byteSize());
                    return Map.copyOf(item);
                }).toList();
    }

    protected Object packageDataRead(List<Object> args) {
        arity(args, 2, "package.dataRead");
        ObjectHash fileHash = requireInstalledFileHash(args, "package.dataRead");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        String path = string(args.get(1), "package.dataRead path");
        byte[] content = transaction.packages().readDataEntry(
                process.ownerId(), fileHash, path);
        if (content == null) throw new FclRuntimeException("Unknown package data file: " + path);
        return decodeUtf8(content, "package.dataRead");
    }

    protected Object packageDataExport(List<Object> args) {
        arity(args, 2, "package.dataExport");
        ObjectHash fileHash = requireInstalledFileHash(args, "package.dataExport");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        Authorization.require(transaction, process.ownerId(), Capability.VFS_WRITE);
        String destination = normalize(string(args.get(1), "package.dataExport destination"));
        if (resolve(destination).isPresent()) {
            throw new FclRuntimeException("package.dataExport destination exists: " + destination);
        }
        byte[] archive = PackageDataService.exportArchive(transaction, process.ownerId(),
                fileHash);
        String nodeId = writeBinary(destination, archive, "application/vnd.sqlite3");
        audit("package.dataExport", process.identity().processUid(), Map.of(
                "packageFileSha256", fileHash.value(), "destination", destination,
                "bytes", Integer.toString(archive.length)));
        return Map.of("path", destination, "nodeId", nodeId, "bytes", archive.length);
    }

    protected Object packageDataImport(List<Object> args) {
        arity(args, 2, "package.dataImport");
        ObjectHash fileHash = requireInstalledFileHash(args, "package.dataImport");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        Authorization.require(transaction, process.ownerId(), Capability.VFS_READ);
        VfsNode node = requireNode(string(args.get(1), "package.dataImport source"));
        requireType(node, VfsNode.Type.FILE, "package.dataImport");
        byte[] archive = readLogicalObject(node.currentObjectHash().orElseThrow(),
                MAX_PACKAGE_DATABASE_BYTES, "package.dataImport archive");
        long imported = PackageDataService.importArchive(transaction, process.ownerId(),
                fileHash, archive);
        audit("package.dataImport", process.identity().processUid(), Map.of(
                "packageFileSha256", fileHash.value(), "entries", Long.toString(imported)));
        return Map.of("entries", imported);
    }

    protected Object packageDataClear(List<Object> args) {
        ObjectHash fileHash = requireInstalledFileHash(args, "package.clearData");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        long removed = transaction.packages().clearDataEntries(process.ownerId(), fileHash);
        audit("package.clearData", process.identity().processUid(), Map.of(
                "packageFileSha256", fileHash.value(), "entries", Long.toString(removed)));
        return Map.of("entriesRemoved", removed);
    }

    protected Object packageDataQuota(List<Object> args) {
        ObjectHash fileHash = requireInstalledFileHash(args, "package.dataQuota");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        return transaction.packages().findDataQuota(process.ownerId(), fileHash);
    }

    protected Object packageSetDataQuota(List<Object> args) {
        arity(args, 3, "package.setDataQuota");
        UUID owner = owner(args, 1);
        ObjectHash fileHash = requireInstalledFileHash(args, "package.setDataQuota");
        long quotaBytes = integer(args.get(2), "package.setDataQuota bytes");
        if (quotaBytes < 0) throw new FclRuntimeException(
                "package.setDataQuota bytes cannot be negative");
        transaction.packages().setDataQuota(process.ownerId(), owner, fileHash, quotaBytes);
        audit("package.setDataQuota", process.identity().processUid(), Map.of(
                "targetUser", owner.toString(), "packageFileSha256", fileHash.value(),
                "quotaBytes", Long.toString(quotaBytes)));
        return Map.of("quota", quotaBytes);
    }

    protected Object packageClearDataQuota(List<Object> args) {
        arity(args, 2, "package.removeDataQuota");
        UUID owner = owner(args, 1);
        ObjectHash fileHash = requireInstalledFileHash(args, "package.removeDataQuota");
        transaction.packages().clearDataQuota(process.ownerId(), owner, fileHash);
        audit("package.removeDataQuota", process.identity().processUid(), Map.of(
                "targetUser", owner.toString(), "packageFileSha256", fileHash.value()));
        return true;
    }

    protected Object buildPackage(List<Object> args) {
        arity(args, 2, "package.build");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_IMPORT);
        Authorization.require(transaction, process.ownerId(), Capability.VFS_READ);
        Authorization.require(transaction, process.ownerId(), Capability.VFS_WRITE);
        String manifestPath = normalize(string(args.get(0), "package manifest path"));
        String outputPath = normalize(string(args.get(1), "package output path"));
        byte[] manifest = readBytes(manifestPath);
        PackageBuilder builder = new PackageBuilder();
        var parsed = builder.parseManifest(manifest);
        int separator = manifestPath.lastIndexOf('/');
        String directory = separator <= 0 ? "/" : manifestPath.substring(0, separator);
        byte[] database = builder.build(parsed,
                path -> readBytes(normalize(directory + "/" + path)));
        String nodeId = writeBinary(outputPath, database, "application/vnd.sqlite3");
        PackageDescriptor descriptor = new SqlitePackageReader().inspect(database);
        Map<String, Object> built = new LinkedHashMap<>(descriptorMap(descriptor));
        built.put("nodeId", nodeId);
        built.put("path", outputPath);
        built.put("coordinate", descriptor.coordinate());
        built.put("packageHash", descriptor.packageHash());
        built.put("databaseFileHash", descriptor.databaseFileHash());
        return Map.copyOf(built);
    }

    protected Object runPackage(List<Object> args) {
        if (args.isEmpty() || args.size() > 2) {
            throw new FclRuntimeException(
                    "package.run expects a package SHA-256 and optional entrypoint");
        }
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        Authorization.require(transaction, process.ownerId(), Capability.PROCESS_CREATE);
        String packageId = string(args.getFirst(), "package hash");
        if (!packageId.matches("(?i)[0-9a-f]{64}")) {
            throw new FclRuntimeException(
                    "package.run requires a 64-character package SHA-256");
        }
        String entrypointName = args.size() == 2
                ? string(args.get(1), "package entrypoint") : "run";
        PackageRelease release = transaction.packages().findInstalledReleaseByDatabaseFileHash(
                        process.ownerId(),
                        new ObjectHash(packageId.toLowerCase(java.util.Locale.ROOT)))
                .orElseThrow(() -> new FclRuntimeException("Installed package release is missing"));
        StoredObject database = transaction.vfs().findObject(release.databaseObjectHash())
                .orElseThrow(() -> new FclRuntimeException("Installed package database is missing"));
        PackageDescriptor descriptor = new SqlitePackageReader().inspect(database.content().bytes());
        PackageIndex.Entrypoint entrypoint = descriptor.entrypoints().stream()
                .filter(value -> value.name().equals(entrypointName)).findFirst()
                .orElseThrow(() -> new FclRuntimeException("Unknown package entrypoint: "
                        + entrypointName));
        if (!isSafeFclIdentifier(entrypoint.name())) {
            throw new FclRuntimeException("Invalid entrypoint name");
        }
        String importHash = release.databaseFileHash().value();
        String source = "import \"" + escapeFcl(importHash)
                + "\" as \"__package_entry\"\n"
                + "return __package_entry." + entrypoint.name() + "()\n";
        Program entryProgram = createProgram(source);
        long pid = transaction.processes().allocatePid();
        UUID processUid = UUID.randomUUID();
        Map<String, ObjectHash> packageBindings = Map.of(importHash,
                release.packageHash().value());
        Continuation continuation = new Continuation(entryProgram.programId(),
                entryProgram.programHash(), 0, List.of(), List.of(), List.of(), List.of(),
                Optional.empty(), Map.of(), packageBindings, entryProgram.languageVersion(),
                Integer.toString(entryProgram.runtimeFormatVersion()));
        CilProcess child = new CilProcess(new ProcessIdentity(processUid, pid), process.ownerId(),
                CilProcess.Status.READY, 0, 0, continuation,
                Optional.of(process.identity().processUid()), now, now);
        transaction.processes().insert(child);
        transaction.packages().saveProcessBinding(new ProcessPackageBinding(processUid,
                importHash, release.packageHash(), now));
        transaction.scheduler().enqueue(new com.follarce.domain.scheduler.SchedulerQueueEntry(
                processUid, now, now,
                com.follarce.domain.scheduler.SchedulerQueueEntry.Status.READY));
        audit("package.run", processUid, Map.of("pid", Long.toString(pid),
                "entrypoint", entrypointName,
                "coordinate", release.coordinate().key()));
        return Map.of("pid", pid, "processUid", processUid.toString(),
                "programId", entryProgram.programId().toString(),
                "coordinate", release.coordinate().key(), "entrypoint", entrypointName);
    }

    protected Program createProgram(String source) {
        FclProgram compiled = new FclCompiler().compile(source);
        StoredObject sourceObject = StoredObject.create(new BinaryContent(
                source.getBytes(StandardCharsets.UTF_8)), ProgramService.SOURCE_MEDIA_TYPE, now);
        StoredObject compiledObject = StoredObject.create(new BinaryContent(
                new FclProgramCodec().toBytes(compiled)), ProgramService.COMPILED_MEDIA_TYPE, now);
        transaction.vfs().saveObject(sourceObject);
        transaction.vfs().saveObject(compiledObject);
        int statements = Math.toIntExact(compiled.instructions().stream()
                .filter(instruction -> !(instruction instanceof FclInstruction.Jump)).count());
        return transaction.programs().saveIfAbsent(new Program(UUID.randomUUID(),
                sourceObject.objectHash(), ProgramService.LANGUAGE_VERSION,
                FclProgramCodec.FORMAT_VERSION, sourceObject.objectHash(),
                Optional.of(compiledObject.objectHash()), statements, now));
    }

    protected Object pinPackage(List<Object> args) {
        if (args.size() != 1) throw new FclRuntimeException(
                "package.pin expects a package SHA-256");
        Authorization.require(transaction, process.ownerId(), Capability.PACKAGE_BIND);
        PackageRelease release = requirePackage(args, "package.pin");
        return packageMap(release);
    }

    protected PackageRelease requirePackage(List<Object> args, String function) {
        Optional<PackageRelease> release;
        if (args.size() == 1) {
            String identity = string(args.getFirst(), function + " package");
            if (identity.matches("[0-9a-fA-F]{64}")) {
                release = transaction.packages().findInstalledReleaseByDatabaseFileHash(
                        process.ownerId(),
                        new ObjectHash(identity.toLowerCase(java.util.Locale.ROOT)));
            } else {
                String[] coordinate = identity.split("/", 3);
                if (coordinate.length != 3) throw new FclRuntimeException(
                        function + " requires namespace/name/version or a package hash");
                release = installedCoordinate(coordinate[0], coordinate[1], coordinate[2]);
            }
        } else if (args.size() == 3) {
            release = installedCoordinate(string(args.get(0), "package namespace"),
                    string(args.get(1), "package name"),
                    string(args.get(2), "package version"));
        } else {
            throw new FclRuntimeException(function + " expects one or three package arguments");
        }
        return release.orElseThrow(() -> new FclRuntimeException("Unknown package release"));
    }

    protected Optional<PackageRelease> installedCoordinate(String namespace, String name,
                                                          String version) {
        return transaction.packages().findInstalledReleases(process.ownerId()).stream()
                .filter(release -> release.coordinate().key().equals(
                        namespace + "/" + name + "/" + version))
                .findFirst();
    }

    protected static Map<String, Object> packageMap(PackageRelease release) {
        return Map.of("coordinate", release.coordinate().key(),
                "name", release.coordinate().name(),
                "hash", release.packageHash().value().value(),
                "sha256", release.databaseFileHash().value(),
                "importedAt", release.importedAt().toString());
    }

    protected Map<String, Object> packageDetails(PackageRelease release) {
        StoredObject object = transaction.vfs().findObject(release.databaseObjectHash())
                .orElseThrow(() -> new FclRuntimeException("Package database object is missing"));
        PackageDescriptor descriptor = new SqlitePackageReader().inspect(object.content().bytes());
        Map<String, Object> result = new LinkedHashMap<>(packageMap(release));
        result.putAll(descriptorMap(descriptor));
        return Map.copyOf(result);
    }

    protected static Map<String, Object> descriptorMap(PackageDescriptor descriptor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", descriptor.kind().wireName());
        result.put("languageVersion", descriptor.languageVersion());
        result.put("dependencies", descriptor.dependencyIndex().stream().map(dependency -> Map.of(
                "sha256", dependency.databaseFileHash().value(),
                "optional", dependency.optional())).toList());
        result.put("entrypoints", descriptor.entrypoints().stream().map(entrypoint -> Map.of(
                "name", entrypoint.name(),
                "module", entrypoint.moduleName(),
                "function", entrypoint.functionName())).toList());
        result.put("exports", descriptor.exports().stream().map(export -> Map.of(
                "name", export.name(),
                "module", export.moduleName(),
                "symbol", export.symbolName())).toList());
        result.put("capabilities", descriptor.capabilityIndex().stream().map(capability -> Map.of(
                "key", capability.key(),
                "required", capability.required(),
                "rationale", capability.rationale())).toList());
        return Map.copyOf(result);
    }

    protected static String escapeFcl(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    protected static boolean isSafeFclIdentifier(String value) {
        return value != null
                && value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                && !value.equals("func") && !value.equals("if") && !value.equals("else")
                && !value.equals("while") && !value.equals("break")
                && !value.equals("continue") && !value.equals("return")
                && !value.equals("import") && !value.equals("include")
                && !value.equals("as") && !value.equals("and") && !value.equals("or")
                && !value.equals("not") && !value.equals("public") && !value.equals("private")
                && !value.equals("true") && !value.equals("false") && !value.equals("null");
    }

}
