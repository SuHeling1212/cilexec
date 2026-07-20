package com.follarce.extension.pack;

import com.follarce.kernel.Constants;
import com.follarce.kernel.log.Logger;
import com.follarce.kernel.process.ProcessState;
import com.follarce.kernel.security.UserUtil;
import com.follarce.kernel.util.JsonUtil;
import com.follarce.kernel.vfs.FileUtil;
import com.follarce.kernel.vfs.PathUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User-scoped package manager backed by a globally deduplicated immutable object store.
 * User root replacement is the sole visibility boundary for install, upgrade, and removal.
 */
public final class PackageManager {
    private static final Pattern ROOT_BINDING = Pattern.compile("^[A-Za-z_][A-Za-z0-9_-]*$");
    private static final Pattern PACKAGE_IMPORT = Pattern.compile(
            "^import\\s+(?:\"([A-Za-z_][A-Za-z0-9_]*)\\.\\*\"|([A-Za-z_][A-Za-z0-9_]*)\\.\\*)\\s*$");
    private static final ReentrantLock PACKAGE_LOCK = new ReentrantLock(true);
    private static final PackageManager INSTANCE = new PackageManager(
            new PackageStore(), new PackageHookRunner());

    private final PackageStore store;
    private final PackageHookRunner hookRunner;

    public record ImportModule(
            String id,
            String packageHash,
            String path,
            String source,
            String packageDataPath,
            boolean rootPackage,
            Map<String, String> dependencies
    ) {
        public ImportModule {
            dependencies = Map.copyOf(new LinkedHashMap<>(dependencies));
        }
    }

    public record PackageImport(String binding, String rootHash, List<ImportModule> modules) {
        public PackageImport {
            modules = List.copyOf(modules);
        }
    }

    public PackageManager(PackageStore store, PackageHookRunner hookRunner) {
        this.store = Objects.requireNonNull(store);
        this.hookRunner = Objects.requireNonNull(hookRunner);
    }

    public static PackageManager getInstance() {
        return INSTANCE;
    }

    public void initialize() {
        PACKAGE_LOCK.lock();
        try {
            store.initializeGlobal();
            for (String user : UserUtil.getListOfUsers().keySet()) {
                UserUtil.ensureUserAppStructure(user);
                store.initializeUser(user);
            }
        } finally {
            PACKAGE_LOCK.unlock();
        }
    }

    public Map<String, Object> build(String user, String sourceVfsPath, String outputVfsPath) {
        PACKAGE_LOCK.lock();
        Path materializedSource = null;
        try {
            initializeForUser(user);
            String sourcePath = normalizedAbsolute(sourceVfsPath);
            String outputPath = normalizedAbsolute(outputVfsPath);
            requireUserPathAccess(user, sourcePath, "build source");
            requireUserPathAccess(user, outputPath, "build output");
            if (!outputPath.endsWith(".pack")) {
                throw new PackageException("Package output must end with .pack: " + outputPath);
            }
            Path source = PackagePaths.hostPath(sourcePath);
            source = secureHostPath(sourcePath);
            Path output = secureHostPath(outputPath);
            if (output.startsWith(source)) {
                throw new PackageException("Package output must be outside the source directory: " + outputPath);
            }
            if (!Files.isDirectory(output.getParent())) {
                throw new PackageException("Package output directory does not exist: "
                        + PathUtil.getParentPath(outputPath));
            }
            String stagingId = "build-" + UUID.randomUUID();
            materializedSource = PackagePaths.hostPath(Constants.SYSTEM_PACKAGE_STAGING_PATH)
                    .resolve(stagingId + "-source");
            PackageSourceMaterializer.materialize(sourcePath, materializedSource, user);
            PackageBuilder.BuildResult result = PackageBuilder.build(materializedSource, output);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "built");
            response.put("coordinate", result.coordinate().key());
            response.put("integrity", result.integrity());
            response.put("path", outputPath);
            response.put("size", result.size());
            return response;
        } finally {
            if (materializedSource != null) PackageSourceMaterializer.deleteTree(materializedSource);
            PACKAGE_LOCK.unlock();
        }
    }

    public Map<String, Object> install(String user,
                                       String sourceVfsPath,
                                       String requestedBinding,
                                       String repositoryVfsPath,
                                       String effectId,
                                       int pid,
                                       String processGeneration) {
        PACKAGE_LOCK.lock();
        String transactionId = transactionId(user, "install", effectId);
        Path temporaryArchive = null;
        Path materializedSource = null;
        Map<String, Object> transaction = null;
        try {
            initializeForUser(user);
            Map<String, Object> existingTransaction = store.readTransaction(user, transactionId);
            if (existingTransaction != null) {
                String existingState = text(existingTransaction.get("state"));
                if ("COMMITTED".equals(existingState)) return transactionResult(existingTransaction);
                if (isRootCommittedState(existingState)) {
                    completePostHooks(user, transactionId, existingTransaction);
                    return transactionResult(existingTransaction);
                }
                if ("COMMITTING_ROOT".equals(existingState)
                        && transactionCrossedVisibilityBoundary(user, existingTransaction)) {
                    writeTransaction(user, transactionId, existingTransaction, "ROOT_COMMITTED");
                    completePostHooks(user, transactionId, existingTransaction);
                    return transactionResult(existingTransaction);
                }
                if ("ABORTED".equals(existingState)) {
                    throw new PackageException("Previous package transaction was aborted: "
                            + text(existingTransaction.get("error")));
                }
                transaction = existingTransaction;
            }

            String sourcePath = normalizedAbsolute(sourceVfsPath);
            requireUserPathAccess(user, sourcePath, "install source");
            String repositoryPath = repositoryVfsPath == null || repositoryVfsPath.isBlank()
                    ? null : normalizedAbsolute(repositoryVfsPath);
            if (repositoryPath != null) requireUserPathAccess(user, repositoryPath, "package repository");

            if (transaction == null) {
                transaction = newTransaction(transactionId, effectId, user, "INSTALL", pid, processGeneration);
                transaction.put("source", sourcePath);
                transaction.put("requestedBinding", requestedBinding);
                writeTransaction(user, transactionId, transaction, "PREPARING");
            }

            Path source = secureHostPath(sourcePath);
            Path rootArchivePath;
            Path candidateDirectory;
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                temporaryArchive = store.stagingFile(transactionId);
                Files.deleteIfExists(temporaryArchive);
                materializedSource = PackagePaths.hostPath(Constants.SYSTEM_PACKAGE_STAGING_PATH)
                        .resolve(transactionId + "-source");
                PackageSourceMaterializer.materialize(sourcePath, materializedSource, user);
                PackageBuilder.build(materializedSource, temporaryArchive);
                rootArchivePath = temporaryArchive;
                candidateDirectory = source.getParent();
            } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(source)) throw new PackageException("Package source cannot be a symlink");
                rootArchivePath = source;
                candidateDirectory = source.getParent();
            } else {
                throw new PackageException("Package install source not found: " + sourcePath);
            }

            PackageArchive rootArchive = PackageArchive.read(rootArchivePath);
            String binding = requestedBinding == null || requestedBinding.isBlank()
                    ? rootArchive.manifest().coordinate().name() : requestedBinding;
            validateRootBinding(binding);
            transaction.put("binding", binding);
            transaction.put("rootHash", rootArchive.hash());
            writeTransaction(user, transactionId, transaction, "RESOLVING");

            Set<Path> repositories = new LinkedHashSet<>();
            if (candidateDirectory != null) repositories.add(candidateDirectory);
            repositories.add(PackagePaths.hostPath(Constants.SYSTEM_PACKAGE_REPOSITORY_PATH));
            if (repositoryPath != null) {
                Path repository = secureHostPath(repositoryPath);
                if (!Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PackageException("Package repository is not a directory: " + repositoryPath);
                }
                repositories.add(repository);
            }
            ResolvedGraph graph = resolveGraph(rootArchive, repositories);
            validateGraphIndex(graph);
            transaction.put("graph", new ArrayList<>(graph.archives.keySet()));
            writeTransaction(user, transactionId, transaction, "VERIFIED");

            Map<String, Object> root = store.readRoot(user);
            Map<String, Object> expectedRoot = JsonUtil.deepCopy(root);
            Map<String, Object> roots = PackageStore.objectMap(root, "packages");
            Map<String, Object> priorEntry = mapValue(roots.get(binding));
            String priorHash = entryHash(priorEntry);
            if (rootArchive.hash().equals(priorHash)) {
                Map<String, Object> result = result("already-installed", user, binding, rootArchive);
                transaction.put("result", result);
                writeTransaction(user, transactionId, transaction, "COMMITTED");
                return result;
            }

            LinkedHashSet<String> oldReachable = reachableFromRootEntries(roots);
            Map<String, Object> otherRoots = new LinkedHashMap<>(roots);
            otherRoots.remove(binding);
            LinkedHashSet<String> otherReachable = reachableFromRootEntries(otherRoots);
            LinkedHashSet<String> newReachable = new LinkedHashSet<>(otherReachable);
            newReachable.addAll(graph.archives.keySet());

            LinkedHashSet<String> disappearing = difference(oldReachable, newReachable);
            LinkedHashSet<String> appearing = difference(newReachable, oldReachable);
            List<String> uninstallOrder = reverseFilter(
                    dependencyOrder(hashesFromRootEntries(roots)), disappearing);
            List<String> installOrder = filter(new ArrayList<>(graph.archives.keySet()), appearing);

            ensurePackageDataDirectories(user, installOrder, graph);
            runHooks(uninstallOrder, PackageManifest.LifecycleEvent.PRE_UNINSTALL,
                    user, binding, effectBase(transaction), pid, processGeneration, graph);
            runHooks(installOrder, PackageManifest.LifecycleEvent.PRE_INSTALL,
                    user, binding, effectBase(transaction), pid, processGeneration, graph);
            writeTransaction(user, transactionId, transaction, "PRE_HOOKS_COMPLETED");

            commitGraph(graph);
            writeTransaction(user, transactionId, transaction, "OBJECTS_COMMITTED");

            List<Map<String, Object>> postHooks = new ArrayList<>();
            appendPostHooks(postHooks, uninstallOrder, PackageManifest.LifecycleEvent.POST_UNINSTALL,
                    binding, effectBase(transaction));
            appendPostHooks(postHooks, installOrder, PackageManifest.LifecycleEvent.POST_INSTALL,
                    binding, effectBase(transaction));
            transaction.put("postHooks", postHooks);
            transaction.put("completedPostHooks", new ArrayList<String>());
            Map<String, Object> result = result(priorHash == null ? "installed" : "upgraded",
                    user, binding, rootArchive);
            if (priorHash != null) result.put("previousIntegrity", "sha256:" + priorHash);
            transaction.put("result", result);
            writeTransaction(user, transactionId, transaction, "COMMITTING_ROOT");

            roots.put(binding, rootEntry(rootArchive, binding));
            incrementGeneration(root);
            store.replaceRoot(user, expectedRoot, root);
            writeTransaction(user, transactionId, transaction, "ROOT_COMMITTED");

            completePostHooks(user, transactionId, transaction);
            return transactionResult(transaction);
        } catch (PackageException e) {
            if (transaction != null && !rootContains(user, transaction)) {
                transaction.put("error", e.getMessage());
                writeTransaction(user, transactionId, transaction, "ABORTED");
            } else if (transaction != null) {
                transaction.put("error", e.getMessage());
                writeTransaction(user, transactionId, transaction, "POST_HOOKS_FAILED");
            }
            throw e;
        } catch (Exception e) {
            PackageException failure = new PackageException("Package installation failed: " + e.getMessage(), e);
            if (transaction != null && !rootContains(user, transaction)) {
                transaction.put("error", failure.getMessage());
                writeTransaction(user, transactionId, transaction, "ABORTED");
            } else if (transaction != null) {
                transaction.put("error", failure.getMessage());
                writeTransaction(user, transactionId, transaction, "POST_HOOKS_FAILED");
            }
            throw failure;
        } finally {
            if (temporaryArchive != null) deleteQuietly(temporaryArchive);
            if (materializedSource != null) PackageSourceMaterializer.deleteTree(materializedSource);
            PACKAGE_LOCK.unlock();
        }
    }

    public Map<String, Object> remove(String user,
                                      String binding,
                                      String effectId,
                                      int pid,
                                      String processGeneration) {
        PACKAGE_LOCK.lock();
        String transactionId = transactionId(user, "remove", effectId);
        Map<String, Object> transaction = null;
        try {
            initializeForUser(user);
            validateRootBinding(binding);
            Map<String, Object> existing = store.readTransaction(user, transactionId);
            if (existing != null) {
                String state = text(existing.get("state"));
                if ("COMMITTED".equals(state)) return transactionResult(existing);
                if (isRootCommittedState(state)) {
                    completePostHooks(user, transactionId, existing);
                    return transactionResult(existing);
                }
                if ("COMMITTING_ROOT".equals(state)
                        && transactionCrossedVisibilityBoundary(user, existing)) {
                    writeTransaction(user, transactionId, existing, "ROOT_COMMITTED");
                    completePostHooks(user, transactionId, existing);
                    return transactionResult(existing);
                }
                if ("ABORTED".equals(state)) {
                    throw new PackageException("Previous package transaction was aborted: "
                            + text(existing.get("error")));
                }
                transaction = existing;
            }

            Map<String, Object> root = store.readRoot(user);
            Map<String, Object> expectedRoot = JsonUtil.deepCopy(root);
            Map<String, Object> roots = PackageStore.objectMap(root, "packages");
            Map<String, Object> entry = mapValue(roots.get(binding));
            if (entry == null) throw new PackageException("Package is not installed for " + user + ": " + binding);
            String rootHash = entryHash(entry);

            if (transaction == null) {
                transaction = newTransaction(transactionId, effectId, user, "REMOVE", pid, processGeneration);
                transaction.put("binding", binding);
                transaction.put("rootHash", rootHash);
                writeTransaction(user, transactionId, transaction, "PREPARING");
            }

            LinkedHashSet<String> oldReachable = reachableFromRootEntries(roots);
            Map<String, Object> remainingRoots = new LinkedHashMap<>(roots);
            remainingRoots.remove(binding);
            LinkedHashSet<String> newReachable = reachableFromRootEntries(remainingRoots);
            LinkedHashSet<String> disappearing = difference(oldReachable, newReachable);
            List<String> uninstallOrder = reverseFilter(
                    dependencyOrder(List.of(rootHash)), disappearing);

            runHooks(uninstallOrder, PackageManifest.LifecycleEvent.PRE_UNINSTALL,
                    user, binding, effectBase(transaction), pid, processGeneration, null);
            writeTransaction(user, transactionId, transaction, "PRE_HOOKS_COMPLETED");

            List<Map<String, Object>> postHooks = new ArrayList<>();
            appendPostHooks(postHooks, uninstallOrder, PackageManifest.LifecycleEvent.POST_UNINSTALL,
                    binding, effectBase(transaction));
            transaction.put("postHooks", postHooks);
            transaction.put("completedPostHooks", new ArrayList<String>());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "removed");
            result.put("user", user);
            result.put("binding", binding);
            result.put("integrity", "sha256:" + rootHash);
            result.put("garbageCollectable", new ArrayList<>(disappearing));
            transaction.put("result", result);
            writeTransaction(user, transactionId, transaction, "COMMITTING_ROOT");

            roots.remove(binding);
            incrementGeneration(root);
            store.replaceRoot(user, expectedRoot, root);
            writeTransaction(user, transactionId, transaction, "ROOT_COMMITTED");

            completePostHooks(user, transactionId, transaction);
            return transactionResult(transaction);
        } catch (PackageException e) {
            if (transaction != null && !rootRemoved(user, transaction)) {
                transaction.put("error", e.getMessage());
                writeTransaction(user, transactionId, transaction, "ABORTED");
            } else if (transaction != null) {
                transaction.put("error", e.getMessage());
                writeTransaction(user, transactionId, transaction, "POST_HOOKS_FAILED");
            }
            throw e;
        } finally {
            PACKAGE_LOCK.unlock();
        }
    }

    public List<Map<String, Object>> list(String user) {
        PACKAGE_LOCK.lock();
        try {
            initializeForUser(user);
            Map<String, Object> packages = PackageStore.objectMap(store.readRoot(user), "packages");
            List<Map<String, Object>> result = new ArrayList<>();
            packages.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(item -> {
                Map<String, Object> entry = mapValue(item.getValue());
                if (entry != null) {
                    Map<String, Object> copy = new LinkedHashMap<>(entry);
                    copy.put("binding", item.getKey());
                    result.add(copy);
                }
            });
            return result;
        } finally {
            PACKAGE_LOCK.unlock();
        }
    }

    public Map<String, Object> info(String user, String binding) {
        PACKAGE_LOCK.lock();
        try {
            initializeForUser(user);
            validateRootBinding(binding);
            Map<String, Object> roots = PackageStore.objectMap(store.readRoot(user), "packages");
            Map<String, Object> entry = mapValue(roots.get(binding));
            if (entry == null) throw new PackageException("Package is not installed for " + user + ": " + binding);
            String hash = entryHash(entry);
            PackageArchive archive = store.readObject(hash);
            Map<String, Object> result = new LinkedHashMap<>(entry);
            result.put("binding", binding);
            result.put("manifest", archive.manifest().source());
            result.put("references", PackageStore.objectMap(store.readReferences(hash), "references"));
            return result;
        } finally {
            PACKAGE_LOCK.unlock();
        }
    }

    public Map<String, Object> verify(String user, String binding) {
        PACKAGE_LOCK.lock();
        try {
            initializeForUser(user);
            Map<String, Object> roots = PackageStore.objectMap(store.readRoot(user), "packages");
            Map<String, Object> entry = mapValue(roots.get(binding));
            if (entry == null) throw new PackageException("Package is not installed for " + user + ": " + binding);
            List<String> order = dependencyOrder(List.of(entryHash(entry)));
            Map<String, Object> index = PackageStore.objectMap(store.readIndex(), "packages");
            for (String hash : order) verifyObject(hash, index);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("binding", binding);
            result.put("integrity", entry.get("integrity"));
            result.put("verifiedPackages", order.size());
            result.put("graph", order.stream().map(value -> "sha256:" + value).toList());
            return result;
        } finally {
            PACKAGE_LOCK.unlock();
        }
    }

    public Map<String, Object> pin(String user, String bindingOrIntegrity) {
        PACKAGE_LOCK.lock();
        try {
            initializeForUser(user);
            String hash;
            String binding = null;
            if (bindingOrIntegrity != null && bindingOrIntegrity.startsWith("sha256:")) {
                hash = hashFromIntegrity(bindingOrIntegrity);
                store.readObject(hash);
                if (!Constants.DEFAULT_USER_LOCAL.equals(user)) {
                    LinkedHashSet<String> reachable = reachableFromRootEntries(PackageStore.objectMap(
                            store.readRoot(user), "packages"));
                    if (!reachable.contains(hash)) {
                        throw new PackageException("Cannot pin a package outside the user's installed graph");
                    }
                }
            } else {
                binding = bindingOrIntegrity;
                validateRootBinding(binding);
                Map<String, Object> entry = mapValue(PackageStore.objectMap(
                        store.readRoot(user), "packages").get(binding));
                if (entry == null) throw new PackageException("Package is not installed: " + binding);
                hash = entryHash(entry);
            }
            Map<String, Object> pins = store.readPins(user);
            Map<String, Object> expectedPins = JsonUtil.deepCopy(pins);
            Map<String, Object> packages = PackageStore.objectMap(pins, "packages");
            Map<String, Object> existing = mapValue(packages.get(hash));
            if (existing != null) return new LinkedHashMap<>(existing);
            Map<String, Object> pin = new LinkedHashMap<>();
            pin.put("integrity", "sha256:" + hash);
            pin.put("binding", binding);
            pin.put("pinnedAt", Instant.now().toString());
            packages.put(hash, pin);
            store.replacePins(user, expectedPins, pins);
            return pin;
        } finally {
            PACKAGE_LOCK.unlock();
        }
    }

    public boolean unpin(String user, String bindingOrIntegrity) {
        PACKAGE_LOCK.lock();
        try {
            initializeForUser(user);
            Map<String, Object> pins = store.readPins(user);
            Map<String, Object> expectedPins = JsonUtil.deepCopy(pins);
            Map<String, Object> packages = PackageStore.objectMap(pins, "packages");
            String hash = bindingOrIntegrity != null && bindingOrIntegrity.startsWith("sha256:")
                    ? hashFromIntegrity(bindingOrIntegrity) : null;
            if (hash == null) {
                for (Map.Entry<String, Object> item : packages.entrySet()) {
                    Map<String, Object> value = mapValue(item.getValue());
                    if (value != null && Objects.equals(bindingOrIntegrity, value.get("binding"))) {
                        hash = item.getKey();
                        break;
                    }
                }
            }
            if (hash == null) return true;
            boolean removed = packages.remove(hash) != null;
            if (removed) store.replacePins(user, expectedPins, pins);
            return true;
        } finally {
            PACKAGE_LOCK.unlock();
        }
    }

    public Map<String, Object> garbageCollect() {
        PACKAGE_LOCK.lock();
        try {
            initialize();
            LinkedHashSet<String> roots = collectGarbageCollectionRoots();
            LinkedHashSet<String> marked = new LinkedHashSet<>(dependencyOrder(new ArrayList<>(roots)));
            Set<String> all = store.listObjectHashes();
            List<String> removed = all.stream().filter(hash -> !marked.contains(hash)).sorted().toList();
            for (String hash : removed) store.deleteObject(hash);

            if (!removed.isEmpty()) {
                store.updateIndex(index -> {
                    Map<String, Object> packages = PackageStore.objectMap(index, "packages");
                    packages.entrySet().removeIf(item -> {
                        try {
                            return removed.contains(hashFromIntegrity(text(item.getValue())));
                        } catch (PackageException ignored) {
                            return true;
                        }
                    });
                });
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "collected");
            result.put("roots", roots.size());
            result.put("retained", marked.size());
            result.put("removed", removed.stream().map(hash -> "sha256:" + hash).toList());
            return result;
        } finally {
            PACKAGE_LOCK.unlock();
        }
    }

    public String readResource(String user, String binding, String resourcePath) {
        PACKAGE_LOCK.lock();
        try {
            initializeForUser(user);
            Map<String, Object> entry = mapValue(PackageStore.objectMap(
                    store.readRoot(user), "packages").get(binding));
            if (entry == null) throw new PackageException("Package is not installed: " + binding);
            PackageArchive archive = store.readObject(entryHash(entry));
            String normalized = PackageManifestParser.packagePath(
                    resourcePath, "resource", "resources/", null);
            if (!archive.manifest().resources().contains(normalized)) {
                throw new PackageException("Resource is not exported by package " + binding + ": " + normalized);
            }
            return archive.readUtf8(normalized);
        } finally {
            PACKAGE_LOCK.unlock();
        }
    }

    /** Returns null when the import spec is not an installed-package name for this user. */
    public PackageImport loadForImport(String user, String importSpec) {
        PACKAGE_LOCK.lock();
        try {
            initializeForUser(user);
            String binding = installedBindingFromImport(user, importSpec);
            if (binding == null) return null;
            Map<String, Object> entry = mapValue(PackageStore.objectMap(
                    store.readRoot(user), "packages").get(binding));
            if (entry == null) return null;
            String rootHash = entryHash(entry);
            List<String> graph = dependencyOrder(List.of(rootHash));
            List<ImportModule> modules = new ArrayList<>();
            for (String hash : graph) {
                PackageArchive archive = store.readObject(hash);
                Set<String> dependencyBindings = new LinkedHashSet<>();
                Map<String, String> dependencyHashes = new LinkedHashMap<>();
                for (PackageManifest.Dependency dependency : archive.manifest().dependencies()) {
                    dependencyBindings.add(dependency.binding());
                    dependencyHashes.put(dependency.binding(), dependency.hash());
                }
                for (String module : archive.payloadModules()) {
                    String source = normalizePackageImports(archive.readUtf8(module),
                            dependencyBindings, archive.manifest().coordinate());
                    String id = "pack:" + hash + "!/" + module;
                    String dataPath = PackagePaths.userPackageInstanceDataPath(
                            user, archive.manifest().coordinate());
                    modules.add(new ImportModule(id, hash, module, source,
                            dataPath, hash.equals(rootHash), dependencyHashes));
                }
            }
            return new PackageImport(binding, rootHash, modules);
        } finally {
            PACKAGE_LOCK.unlock();
        }
    }

    public void recoverTransactions() {
        PACKAGE_LOCK.lock();
        try {
            initialize();
            for (String user : UserUtil.getListOfUsers().keySet()) {
                for (Map<String, Object> transaction : store.readTransactions(user)) {
                    String state = text(transaction.get("state"));
                    String id = text(transaction.get("transactionId"));
                    if ("COMMITTED".equals(state) || "ABORTED".equals(state)) continue;
                    if (isRootCommittedState(state)) {
                        try {
                            completePostHooks(user, id, transaction);
                        } catch (PackageException e) {
                            Logger.warn("Package post-hook recovery remains pending for " + id + ": "
                                    + e.getMessage());
                        }
                    } else if (transactionCrossedVisibilityBoundary(user, transaction)) {
                        writeTransaction(user, id, transaction, "ROOT_COMMITTED");
                        completePostHooks(user, id, transaction);
                    } else {
                        transaction.put("state", "ABORTED");
                        transaction.put("error", "Interrupted before the user root visibility boundary");
                        transaction.put("updatedAt", Instant.now().toString());
                        store.writeTransaction(user, id, transaction);
                        deleteQuietly(store.stagingFile(id));
                    }
                }
            }
        } finally {
            PACKAGE_LOCK.unlock();
        }
    }

    private ResolvedGraph resolveGraph(PackageArchive root, Set<Path> repositories) {
        Map<String, Path> candidates = indexRepositoryCandidates(repositories);
        ResolvedGraph graph = new ResolvedGraph();
        resolveArchive(root, graph, candidates, new LinkedHashSet<>(), new ArrayList<>());
        return graph;
    }

    private void resolveArchive(PackageArchive archive,
                                ResolvedGraph graph,
                                Map<String, Path> candidates,
                                Set<String> visiting,
                                List<String> chain) {
        String hash = archive.hash();
        if (graph.archives.containsKey(hash)) return;
        if (!visiting.add(hash)) {
            List<String> cycle = new ArrayList<>(chain);
            cycle.add(archive.manifest().coordinate().displayName());
            throw new PackageException("Circular package dependency: " + String.join(" -> ", cycle));
        }
        chain.add(archive.manifest().coordinate().displayName());
        Map<String, Object> references = referenceTable(hash);
        Map<String, Object> referenceEntries = PackageStore.objectMap(references, "references");
        for (PackageManifest.Dependency dependency : archive.manifest().dependencies()) {
            String dependencyHash = dependency.hash();
            PackageArchive dependencyArchive;
            if (store.containsObject(dependencyHash)) {
                dependencyArchive = store.readObject(dependencyHash);
            } else {
                Path source = candidates.get(dependencyHash);
                if (source == null) {
                    throw new PackageException("Dependency not found: " + dependency.coordinate().displayName()
                            + " (" + dependency.integrity() + ")\nDependency chain: "
                            + String.join(" -> ", chain));
                }
                dependencyArchive = PackageArchive.read(source);
            }
            if (!dependency.coordinate().equals(dependencyArchive.manifest().coordinate())) {
                throw new PackageException("Dependency coordinate mismatch for " + dependency.binding()
                        + ": expected " + dependency.coordinate().displayName() + ", archive contains "
                        + dependencyArchive.manifest().coordinate().displayName());
            }
            if (!dependencyHash.equals(dependencyArchive.hash())) {
                throw new PackageException("Dependency hash mismatch for "
                        + dependency.coordinate().displayName());
            }
            resolveArchive(dependencyArchive, graph, candidates, visiting, chain);
            referenceEntries.put(dependency.binding(), referenceEntry(dependency));
        }
        chain.remove(chain.size() - 1);
        visiting.remove(hash);
        graph.archives.put(hash, archive);
        graph.references.put(hash, references);
    }

    private Map<String, Path> indexRepositoryCandidates(Set<Path> repositories) {
        Map<String, Path> candidates = new LinkedHashMap<>();
        for (Path repository : repositories) {
            if (repository == null || !Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(repository)) continue;
            try (var files = Files.list(repository)) {
                List<Path> packageFiles = files
                        .filter(path -> path.getFileName().toString().endsWith(".pack"))
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !Files.isSymbolicLink(path))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
                for (Path path : packageFiles) {
                    try {
                        PackageArchive candidate = PackageArchive.read(path);
                        candidates.putIfAbsent(candidate.hash(), path);
                    } catch (PackageException e) {
                        Logger.warn("Ignoring invalid package repository entry " + path + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                throw new PackageException("Failed to scan package repository: " + repository, e);
            }
        }
        return candidates;
    }

    private void commitGraph(ResolvedGraph graph) {
        validateGraphIndex(graph);
        for (PackageArchive archive : graph.archives.values()) store.putObject(archive);
        for (Map.Entry<String, Map<String, Object>> item : graph.references.entrySet()) {
            store.writeReferences(item.getKey(), item.getValue());
        }
        store.updateIndex(index -> {
            Map<String, Object> packages = PackageStore.objectMap(index, "packages");
            validateGraphIndex(packages, graph);
            for (PackageArchive archive : graph.archives.values()) {
                packages.put(archive.manifest().coordinate().key(), archive.integrity());
            }
        });
    }

    private void validateGraphIndex(ResolvedGraph graph) {
        Map<String, Object> packages = PackageStore.objectMap(store.readIndex(), "packages");
        validateGraphIndex(packages, graph);
    }

    private static void validateGraphIndex(Map<String, Object> packages, ResolvedGraph graph) {
        for (PackageArchive archive : graph.archives.values()) {
            String key = archive.manifest().coordinate().key();
            Object existing = packages.get(key);
            if (existing != null && !archive.integrity().equals(existing.toString())) {
                throw new PackageException("Package version pollution detected for " + key
                        + ": index contains " + existing + ", candidate is " + archive.integrity());
            }
        }
    }

    private void runHooks(List<String> hashes,
                          PackageManifest.LifecycleEvent event,
                          String user,
                          String rootBinding,
                          String effectBase,
                          int pid,
                          String generation,
                          ResolvedGraph graph) {
        for (String hash : hashes) {
            PackageArchive archive = graph != null && graph.archives.containsKey(hash)
                    ? graph.archives.get(hash) : store.readObject(hash);
            hookRunner.run(archive, event, user, rootBinding,
                    effectBase + "-" + event.manifestKey() + "-" + hash,
                    pid, generationOrDefault(generation));
        }
    }

    private static void ensurePackageDataDirectories(String user,
                                                     List<String> hashes,
                                                     ResolvedGraph graph) {
        for (String hash : hashes) {
            PackageArchive archive = graph.archives.get(hash);
            PackageCoordinate coordinate = archive.manifest().coordinate();
            PackagePaths.ensureDirectory(PackagePaths.userPackagesDataPath(user)
                    + coordinate.namespace() + "/", user, true);
            PackagePaths.ensureDirectory(
                    PackagePaths.userPackageInstanceDataPath(user, coordinate), user, true);
        }
    }

    @SuppressWarnings("unchecked")
    private void completePostHooks(String user,
                                   String transactionId,
                                   Map<String, Object> transaction) {
        Object rawHooks = transaction.get("postHooks");
        List<Map<String, Object>> hooks = rawHooks instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        List<String> completed = transaction.get("completedPostHooks") instanceof List<?> list
                ? new ArrayList<>((List<String>) list) : new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int pid = transaction.get("pid") instanceof Number number ? number.intValue() : 0;
        String generation = generationOrDefault(text(transaction.get("processGeneration")));
        for (Map<String, Object> hook : hooks) {
            String hash = text(hook.get("hash"));
            String eventName = text(hook.get("event"));
            String key = eventName + ":" + hash;
            if (completed.contains(key)) continue;
            try {
                PackageManifest.LifecycleEvent event = PackageManifest.LifecycleEvent.fromManifestKey(eventName);
                PackageArchive archive = store.readObject(hash);
                hookRunner.run(archive, event, user, text(hook.get("binding")),
                        text(hook.get("effectId")), pid, generation);
                completed.add(key);
                transaction.put("completedPostHooks", completed);
                store.writeTransaction(user, transactionId, transaction);
            } catch (PackageException e) {
                errors.add(key + ": " + e.getMessage());
            }
        }

        Map<String, Object> result = transactionResult(transaction);
        if (errors.isEmpty()) {
            result.put("postHookStatus", "completed");
            transaction.put("result", result);
            transaction.remove("postHookErrors");
            writeTransaction(user, transactionId, transaction, "COMMITTED");
        } else {
            result.put("postHookStatus", "pending-retry");
            result.put("postHookErrors", errors);
            transaction.put("result", result);
            transaction.put("postHookErrors", errors);
            writeTransaction(user, transactionId, transaction, "POST_HOOKS_FAILED");
        }
    }

    private List<String> dependencyOrder(List<String> rootHashes) {
        List<String> order = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String hash : rootHashes) dependencyOrder(hash, visited, visiting, order);
        return order;
    }

    private void dependencyOrder(String hash, Set<String> visited, Set<String> visiting,
                                 List<String> order) {
        if (visited.contains(hash)) return;
        if (!visiting.add(hash)) throw new PackageException("Circular stored package references at sha256:" + hash);
        PackageArchive archive = store.readObject(hash);
        Map<String, Object> refs = PackageStore.objectMap(store.readReferences(hash), "references");
        for (PackageManifest.Dependency dependency : archive.manifest().dependencies()) {
            Map<String, Object> reference = mapValue(refs.get(dependency.binding()));
            if (reference == null) {
                throw new PackageException("Broken reference " + dependency.binding() + " in sha256:" + hash);
            }
            String target = hashFromIntegrity(text(reference.get("targetPackageHash")));
            if (!target.equals(dependency.hash())) {
                throw new PackageException("Reference hash does not match manifest for "
                        + archive.manifest().coordinate().displayName() + " -> " + dependency.binding());
            }
            dependencyOrder(target, visited, visiting, order);
        }
        visiting.remove(hash);
        visited.add(hash);
        order.add(hash);
    }

    private void verifyObject(String hash, Map<String, Object> index) {
        PackageArchive archive = store.readObject(hash);
        Object indexed = index.get(archive.manifest().coordinate().key());
        if (!archive.integrity().equals(indexed)) {
            throw new PackageException("Package index mismatch for "
                    + archive.manifest().coordinate().displayName());
        }
        Map<String, Object> refs = PackageStore.objectMap(store.readReferences(hash), "references");
        if (refs.size() != archive.manifest().dependencies().size()) {
            throw new PackageException("Reference count mismatch for "
                    + archive.manifest().coordinate().displayName());
        }
        for (PackageManifest.Dependency dependency : archive.manifest().dependencies()) {
            Map<String, Object> reference = mapValue(refs.get(dependency.binding()));
            if (reference == null
                    || !dependency.integrity().equals(reference.get("targetPackageHash"))
                    || !dependency.coordinate().namespace().equals(reference.get("namespace"))
                    || !dependency.coordinate().name().equals(reference.get("name"))
                    || !dependency.coordinate().version().equals(reference.get("version"))) {
                throw new PackageException("Invalid stored reference " + dependency.binding()
                        + " for " + archive.manifest().coordinate().displayName());
            }
            store.readObject(dependency.hash());
        }
    }

    private LinkedHashSet<String> collectGarbageCollectionRoots() {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (String user : UserUtil.getListOfUsers().keySet()) {
            roots.addAll(hashesFromRootEntries(PackageStore.objectMap(store.readRoot(user), "packages")));
            roots.addAll(PackageStore.objectMap(store.readPins(user), "packages").keySet());
            for (Map<String, Object> transaction : store.readTransactions(user)) {
                if (isRootCommittedState(text(transaction.get("state")))) {
                    Object raw = transaction.get("postHooks");
                    if (raw instanceof List<?> hooks) {
                        for (Object item : hooks) {
                            Map<String, Object> hook = mapValue(item);
                            if (hook != null) {
                                String hash = text(hook.get("hash"));
                                if (hash != null && hash.matches("[0-9a-f]{64}")) roots.add(hash);
                            }
                        }
                    }
                }
            }
        }
        roots.addAll(activeProcessPackageHashes());
        return roots;
    }

    @SuppressWarnings("unchecked")
    private Set<String> activeProcessPackageHashes() {
        Set<String> hashes = new LinkedHashSet<>();
        Path processDirectory = PackagePaths.hostPath(Constants.SYSTEM_PROCESS_PATH);
        if (!Files.isDirectory(processDirectory)) return hashes;
        try (var files = Files.list(processDirectory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".proc")).toList()) {
                try {
                    String vfsPath = Constants.SYSTEM_PROCESS_PATH + file.getFileName();
                    Map<String, Object> process = JsonUtil.parseToMapStrict(FileUtil.read(vfsPath));
                    if (ProcessState.restore(process.get("ProcessState")).isTerminal()) continue;
                    Map<String, Object> program = mapValue(process.get("Program"));
                    if (program == null || !(program.get("imports") instanceof List<?> imports)) continue;
                    for (Object value : imports) {
                        String imported = text(value);
                        if (imported != null && imported.matches("pack:[0-9a-f]{64}!/.*")) {
                            hashes.add(imported.substring("pack:".length(), "pack:".length() + 64));
                        }
                    }
                } catch (Exception e) {
                    Logger.warn("Ignoring unreadable process package roots in " + file + ": " + e.getMessage());
                }
            }
            return hashes;
        } catch (Exception e) {
            throw new PackageException("Failed to collect active process package roots", e);
        }
    }

    private String installedBindingFromImport(String user, String importSpec) {
        if (importSpec == null || importSpec.isBlank()) return null;
        String spec = importSpec;
        String prefix = PackagePaths.normalizeUserImportPrefix(user) + "/";
        if (spec.startsWith("/")) {
            String normalized = PathUtil.normalizePath(spec);
            if (!normalized.startsWith(prefix)) return null;
            spec = normalized.substring(prefix.length());
            if (spec.contains("/")) return null;
        } else if (spec.contains("/") || spec.startsWith(".") || spec.startsWith("~")
                || spec.startsWith("$") || spec.startsWith("@")) {
            return null;
        }
        if (!ROOT_BINDING.matcher(spec).matches()) return null;
        return spec;
    }

    private static String normalizePackageImports(String source,
                                                  Set<String> dependencies,
                                                  PackageCoordinate owner) {
        List<String> output = new ArrayList<>();
        for (String line : source.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ") || trimmed.startsWith("include ")) {
                Matcher matcher = PACKAGE_IMPORT.matcher(trimmed);
                if (!matcher.matches()) {
                    throw new PackageException("Installed package modules may only import declared package bindings: "
                            + owner.displayName() + " -> " + trimmed);
                }
                String binding = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                if (!dependencies.contains(binding)) {
                    throw new PackageException("Package " + owner.displayName()
                            + " imports undeclared dependency binding: " + binding);
                }
                continue;
            }
            output.add(line);
        }
        return String.join("\n", output);
    }

    private void initializeForUser(String user) {
        PackagePaths.validateUser(user);
        if (!UserUtil.getListOfUsers().containsKey(user)) {
            throw new PackageException("Unknown package user: " + user);
        }
        store.initializeGlobal();
        UserUtil.ensureUserAppStructure(user);
        store.initializeUser(user);
    }

    private static void requireUserPathAccess(String user, String path, String operation) {
        if (Constants.DEFAULT_USER_LOCAL.equals(user)) return;
        String home = Constants.USER_HOME_PREFIX + user + "/";
        if (!path.equals(home.substring(0, home.length() - 1)) && !path.startsWith(home)) {
            throw new PackageException("Permission denied for " + operation + ": " + path);
        }
    }

    private static String normalizedAbsolute(String path) {
        if (path == null || path.isBlank()) throw new PackageException("Package path is required");
        String normalized = PathUtil.normalizePath(path);
        if (!path.startsWith("/") && !path.startsWith("~") && !path.startsWith("$")
                && !path.startsWith("@")) {
            throw new PackageException("Package API paths must be absolute or use a path token: " + path);
        }
        return normalized;
    }

    private static Path secureHostPath(String vfsPath) {
        try {
            Path configuredRoot = PathUtil.getVfsRoot().toPath().toAbsolutePath().normalize();
            Path realRoot = configuredRoot.toRealPath();
            Path host = PackagePaths.hostPath(vfsPath).toAbsolutePath().normalize();
            if (!host.startsWith(configuredRoot)) {
                throw new PackageException("Package path escapes the VFS root: " + vfsPath);
            }
            Path relative = configuredRoot.relativize(host);
            Path cursor = configuredRoot;
            for (Path component : relative) {
                cursor = cursor.resolve(component);
                if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                    throw new PackageException("Package paths cannot traverse symbolic links: " + vfsPath);
                }
            }

            Path existing = host;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null || !existing.toRealPath().startsWith(realRoot)) {
                throw new PackageException("Package path escapes the VFS root: " + vfsPath);
            }
            return host;
        } catch (PackageException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageException("Failed to validate package path: " + vfsPath, e);
        }
    }

    private static Map<String, Object> newTransaction(String id, String effectId, String user,
                                                      String operation, int pid, String generation) {
        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("schemaVersion", 1);
        transaction.put("transactionId", id);
        transaction.put("effectId", effectId);
        transaction.put("user", user);
        transaction.put("operation", operation);
        transaction.put("state", "PREPARING");
        transaction.put("pid", pid);
        transaction.put("processGeneration", generation);
        transaction.put("createdAt", Instant.now().toString());
        transaction.put("updatedAt", Instant.now().toString());
        return transaction;
    }

    private void writeTransaction(String user, String id, Map<String, Object> transaction, String state) {
        transaction.put("state", state);
        transaction.put("updatedAt", Instant.now().toString());
        store.writeTransaction(user, id, transaction);
    }

    private static String transactionId(String user, String operation, String effectId) {
        String stable = effectId == null || effectId.isBlank() ? UUID.randomUUID().toString() : effectId;
        return PackageArchive.sha256Hex((user + "\0" + operation + "\0" + stable)
                .getBytes(StandardCharsets.UTF_8)).substring(0, 40);
    }

    private static String effectBase(Map<String, Object> transaction) {
        String effect = text(transaction.get("effectId"));
        return effect == null || effect.isBlank()
                ? "package-transaction-" + transaction.get("transactionId") : effect;
    }

    private static Map<String, Object> referenceTable(String ownerHash) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("ownerPackageHash", "sha256:" + ownerHash);
        result.put("references", new LinkedHashMap<String, Object>());
        return result;
    }

    private static Map<String, Object> referenceEntry(PackageManifest.Dependency dependency) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("namespace", dependency.coordinate().namespace());
        result.put("name", dependency.coordinate().name());
        result.put("version", dependency.coordinate().version());
        result.put("targetPackageHash", dependency.integrity());
        return result;
    }

    private static Map<String, Object> rootEntry(PackageArchive archive, String binding) {
        PackageCoordinate coordinate = archive.manifest().coordinate();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("binding", binding);
        result.put("namespace", coordinate.namespace());
        result.put("name", coordinate.name());
        result.put("version", coordinate.version());
        result.put("integrity", archive.integrity());
        result.put("installedAt", Instant.now().toString());
        return result;
    }

    private static Map<String, Object> result(String status, String user, String binding,
                                              PackageArchive archive) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("user", user);
        result.put("binding", binding);
        result.put("coordinate", archive.manifest().coordinate().key());
        result.put("integrity", archive.integrity());
        return result;
    }

    private static void appendPostHooks(List<Map<String, Object>> target,
                                        List<String> hashes,
                                        PackageManifest.LifecycleEvent event,
                                        String binding,
                                        String effectBase) {
        for (String hash : hashes) {
            Map<String, Object> hook = new LinkedHashMap<>();
            hook.put("hash", hash);
            hook.put("event", event.manifestKey());
            hook.put("binding", binding);
            hook.put("effectId", effectBase + "-" + event.manifestKey() + "-" + hash);
            target.add(hook);
        }
    }

    private static void incrementGeneration(Map<String, Object> root) {
        long generation = root.get("generation") instanceof Number number ? number.longValue() : 0L;
        root.put("generation", generation + 1L);
        root.put("updatedAt", Instant.now().toString());
    }

    private LinkedHashSet<String> reachableFromRootEntries(Map<String, Object> entries) {
        return new LinkedHashSet<>(dependencyOrder(hashesFromRootEntries(entries)));
    }

    private static List<String> hashesFromRootEntries(Map<String, Object> entries) {
        List<String> hashes = new ArrayList<>();
        for (Object raw : entries.values()) {
            Map<String, Object> entry = mapValue(raw);
            if (entry != null) hashes.add(entryHash(entry));
        }
        return hashes;
    }

    private static LinkedHashSet<String> difference(Set<String> left, Set<String> right) {
        LinkedHashSet<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static List<String> filter(List<String> order, Set<String> selected) {
        return order.stream().filter(selected::contains).toList();
    }

    private static List<String> reverseFilter(List<String> order, Set<String> selected) {
        List<String> result = new ArrayList<>(order);
        Collections.reverse(result);
        return result.stream().filter(selected::contains).toList();
    }

    private static String entryHash(Map<String, Object> entry) {
        if (entry == null) return null;
        return hashFromIntegrity(text(entry.get("integrity")));
    }

    private boolean rootContains(String user, Map<String, Object> transaction) {
        try {
            String binding = text(transaction.get("binding"));
            String hash = text(transaction.get("rootHash"));
            if (binding == null || hash == null) return false;
            Map<String, Object> entry = mapValue(PackageStore.objectMap(
                    store.readRoot(user), "packages").get(binding));
            return entry != null && hash.equals(entryHash(entry));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean rootRemoved(String user, Map<String, Object> transaction) {
        try {
            String binding = text(transaction.get("binding"));
            return binding != null && !PackageStore.objectMap(
                    store.readRoot(user), "packages").containsKey(binding);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean transactionCrossedVisibilityBoundary(String user, Map<String, Object> transaction) {
        String operation = text(transaction.get("operation"));
        return "INSTALL".equals(operation) ? rootContains(user, transaction)
                : "REMOVE".equals(operation) && rootRemoved(user, transaction);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> transactionResult(Map<String, Object> transaction) {
        Object result = transaction.get("result");
        if (result instanceof Map<?, ?> map) return (Map<String, Object>) map;
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("status", text(transaction.get("state")));
        fallback.put("transactionId", transaction.get("transactionId"));
        transaction.put("result", fallback);
        return fallback;
    }

    private static boolean isRootCommittedState(String state) {
        return "ROOT_COMMITTED".equals(state) || "POST_HOOKS_FAILED".equals(state);
    }

    private static void validateRootBinding(String binding) {
        if (binding == null || !ROOT_BINDING.matcher(binding).matches()) {
            throw new PackageException("Invalid package binding: " + binding);
        }
    }

    private static String hashFromIntegrity(String integrity) {
        if (integrity == null || !integrity.matches("sha256:[0-9a-f]{64}")) {
            throw new PackageException("Invalid package integrity: " + integrity);
        }
        return integrity.substring("sha256:".length());
    }

    private static String generationOrDefault(String generation) {
        return generation == null || generation.isBlank() ? "package-manager" : generation;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private static final class ResolvedGraph {
        private final LinkedHashMap<String, PackageArchive> archives = new LinkedHashMap<>();
        private final LinkedHashMap<String, Map<String, Object>> references = new LinkedHashMap<>();

        private String rootHash() {
            String root = null;
            for (String hash : archives.keySet()) root = hash;
            return root;
        }
    }
}
