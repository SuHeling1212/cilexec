package com.follarce.application;

import com.follarce.domain.packageinfo.PackageIndex;
import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.program.Program;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclInstruction;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramLinker;
import com.follarce.fcl.FclProgramCodec;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Loads immutable program artifacts and links exact package bindings.
 * Caches are disposable; authorization is checked in the caller's transaction on every link.
 */
final class FclProgramLoader {
    static boolean isSha256(String target) {
        return target != null && target.matches("(?i)[0-9a-f]{64}");
    }

    static String normalizeImport(String target) {
        String normalized = target != null && target.endsWith(".*")
                ? target.substring(0, target.length() - 2) : target;
        return isSha256(normalized)
                ? normalized.toLowerCase(java.util.Locale.ROOT) : normalized;
    }

    private final FclProgramCodec programCodec;
    private final BoundedCache<ObjectHash, FclProgram> programCache = new BoundedCache<>(128);
    private final BoundedCache<ObjectHash, CachedPackage> packageCache = new BoundedCache<>(64);
    private final FclProgramLinker programLinker = new FclProgramLinker();

    FclProgramLoader(FclProgramCodec programCodec) {
        this.programCodec = Objects.requireNonNull(programCodec, "programCodec");
    }

    FclProgram loadProgram(com.follarce.domain.port.TransactionContext transaction,
                                   Program program) {
        if (!FclProgramCodec.supportsFormat(program.runtimeFormatVersion())) {
            throw new IllegalStateException("Unsupported persisted FCL program format: "
                    + program.runtimeFormatVersion());
        }
        FclProgram decoded;
        if (program.compiledObjectHash().isPresent()) {
            ObjectHash compiledHash = program.compiledObjectHash().orElseThrow();
            decoded = programCache.get(compiledHash, () -> {
                StoredObject sourceObject = transaction.vfs().findObject(program.sourceObjectHash())
                        .orElseThrow(() -> new IllegalStateException(
                                "Program source object is missing"));
                String source = utf8(sourceObject, "program source");
                StoredObject compiledObject = transaction.vfs().findObject(compiledHash)
                        .orElseThrow(() -> new IllegalStateException(
                                "Compiled program object is missing"));
                return programCodec.fromBytes(compiledObject.content().bytes(),
                        program.runtimeFormatVersion(), source);
            });
        } else {
            if (program.runtimeFormatVersion() != FclProgramCodec.LEGACY_FORMAT_VERSION) {
                throw new IllegalStateException("FCLB program has no executable artifact");
            }
            StoredObject sourceObject = transaction.vfs().findObject(program.sourceObjectHash())
                    .orElseThrow(() -> new IllegalStateException(
                            "Program source object is missing"));
            String source = utf8(sourceObject, "program source");
            decoded = new FclCompiler().compile(source);
        }
        if (!decoded.sourceHash().equals(program.programHash().value())) {
            throw new IllegalStateException("Loaded program hash does not match metadata");
        }
        return decoded;
    }

    FclProgram linkPackages(
            com.follarce.domain.port.TransactionContext transaction,
            CilProcess process,
            FclProgram base,
            Program program
    ) {
        List<ImportSpec> imports = base.instructions().stream()
                .filter(FclInstruction.Import.class::isInstance)
                .map(FclInstruction.Import.class::cast)
                .map(value -> new ImportSpec(normalizeImport(value.target()), value.alias(),
                        value.wildcard()))
                .toList();
        if (imports.isEmpty()) return base;
        Map<String, ProcessPackageBinding> bindings = new LinkedHashMap<>();
        transaction.packages().findProcessBindings(process.identity().processUid())
                .forEach(binding -> bindings.put(binding.importName(), binding));
        Map<String, FclProgramLinker.Module> modules = new LinkedHashMap<>();
        SqlitePackageReader reader = new SqlitePackageReader();
        for (Map.Entry<String, ProcessPackageBinding> entry : bindings.entrySet()) {
            List<ImportSpec> matching = imports.stream()
                    .filter(spec -> spec.importName().equals(entry.getKey())).toList();
            if (matching.isEmpty()) continue;
            PackageRelease release = transaction.packages().findRelease(entry.getValue().packageHash())
                    .orElseThrow(() -> new IllegalStateException("Pinned package release is missing"));
            CachedPackage cached = packageCache.get(release.databaseObjectHash(), () ->
                    loadPackage(transaction, release, reader));
            PackageDescriptor descriptor = cached.descriptor();
            cached.capabilityPolicy().requireUserCapabilities(
                    transaction.auth().capabilities(process.ownerId()));
            if (!ProgramService.compatiblePackageLanguage(descriptor.languageVersion(),
                    program.languageVersion())) {
                throw new IllegalStateException("Package language version does not match program: "
                        + descriptor.coordinate());
            }
            String identity = release.packageHash().value().value();
            for (PackageIndex.Module module : descriptor.moduleIndex()) {
                String moduleSource = cached.moduleSources().get(module.objectPath());
                if (moduleSource == null) throw new IllegalStateException(
                        "Package module source is missing: " + module.name());
                List<FclProgramLinker.Export> exports = publishedFunctions(descriptor, module,
                        matching);
                mergeModule(modules, new FclProgramLinker.Module(identity, module.name(),
                        moduleSource, exports));
            }
            Set<ObjectHash> visiting = new LinkedHashSet<>();
            visiting.add(release.databaseFileHash());
            linkDependencies(transaction, process, program, descriptor, reader, modules,
                    visiting, new LinkedHashSet<>());
        }
        return programLinker.link(base, List.copyOf(modules.values()));
    }

    private void linkDependencies(
            com.follarce.domain.port.TransactionContext transaction,
            CilProcess process,
            Program program,
            PackageDescriptor parent,
            SqlitePackageReader reader,
            Map<String, FclProgramLinker.Module> modules,
            Set<ObjectHash> visiting,
            Set<ObjectHash> linked
    ) {
        for (PackageIndex.Dependency dependency : parent.dependencyIndex()) {
            ObjectHash fileHash = dependency.databaseFileHash();
            if (linked.contains(fileHash)) continue;
            Optional<PackageRelease> resolved = transaction.packages()
                    .findReleaseByDatabaseFileHash(fileHash);
            if (resolved.isEmpty()) {
                if (dependency.optional()) continue;
                throw new IllegalStateException("Required package dependency is missing: "
                        + fileHash.value());
            }
            if (!visiting.add(fileHash)) {
                throw new IllegalStateException("Cyclic package dependency: " + fileHash.value());
            }
            PackageRelease release = resolved.orElseThrow();
            CachedPackage cached = packageCache.get(release.databaseObjectHash(), () ->
                    loadPackage(transaction, release, reader));
            PackageDescriptor descriptor = cached.descriptor();
            cached.capabilityPolicy().requireUserCapabilities(
                    transaction.auth().capabilities(process.ownerId()));
            if (!ProgramService.compatiblePackageLanguage(descriptor.languageVersion(),
                    program.languageVersion())) {
                throw new IllegalStateException("Dependency language version does not match program: "
                        + descriptor.coordinate());
            }
            linkDependencies(transaction, process, program, descriptor, reader, modules,
                    visiting, linked);
            String identity = release.packageHash().value().value();
            for (PackageIndex.Module module : descriptor.moduleIndex()) {
                String moduleSource = cached.moduleSources().get(module.objectPath());
                if (moduleSource == null) throw new IllegalStateException(
                        "Dependency module source is missing: " + module.name());
                mergeModule(modules, new FclProgramLinker.Module(identity, module.name(),
                        moduleSource, dependencyExports(descriptor, module, fileHash.value())));
            }
            visiting.remove(fileHash);
            linked.add(fileHash);
        }
    }

    private static CachedPackage loadPackage(
            com.follarce.domain.port.TransactionContext transaction,
            PackageRelease release,
            SqlitePackageReader reader
    ) {
        StoredObject database = transaction.vfs().findObject(release.databaseObjectHash())
                .orElseThrow(() -> new IllegalStateException("Pinned package database is missing"));
        byte[] bytes = database.content().bytes();
        PackageDescriptor descriptor = reader.inspect(bytes);
        com.follarce.package_manager.PackageCapabilityPolicy policy =
                com.follarce.package_manager.PackageCapabilityPolicy.inspect(bytes, descriptor);
        Map<String, String> sources = new LinkedHashMap<>();
        for (PackageIndex.Module module : descriptor.moduleIndex()) {
            byte[] sourceBytes = reader.readResource(bytes, module.objectPath());
            if (!ObjectHash.sha256(new com.follarce.domain.vfs.BinaryContent(sourceBytes))
                    .equals(module.hash())) {
                throw new IllegalStateException("Package module hash mismatch: " + module.name());
            }
            sources.put(module.objectPath(), utf8(sourceBytes,
                    "package module " + module.name()));
        }
        return new CachedPackage(descriptor, policy, Map.copyOf(sources));
    }

    private record CachedPackage(
            PackageDescriptor descriptor,
            com.follarce.package_manager.PackageCapabilityPolicy capabilityPolicy,
            Map<String, String> moduleSources
    ) { }

    /** Small synchronized LRU for immutable, database-derived runtime artifacts. */
    private static final class BoundedCache<K, V> {
        private final int maximum;
        private final LinkedHashMap<K, V> values = new LinkedHashMap<>(16, 0.75f, true);

        private BoundedCache(int maximum) {
            this.maximum = maximum;
        }

        private V get(K key, Supplier<V> loader) {
            synchronized (values) {
                V existing = values.get(key);
                if (existing != null) return existing;
            }
            V loaded = Objects.requireNonNull(loader.get(), "cache loader returned null");
            synchronized (values) {
                V raced = values.get(key);
                if (raced != null) return raced;
                values.put(key, loaded);
                while (values.size() > maximum) {
                    values.remove(values.keySet().iterator().next());
                }
                return loaded;
            }
        }
    }

    private static void mergeModule(Map<String, FclProgramLinker.Module> modules,
                                    FclProgramLinker.Module candidate) {
        String key = candidate.packageIdentity() + "\u0000" + candidate.moduleName();
        FclProgramLinker.Module existing = modules.get(key);
        if (existing == null) {
            modules.put(key, candidate);
            return;
        }
        if (!existing.source().equals(candidate.source())) {
            throw new IllegalStateException("Identical package module identity has different source");
        }
        Map<String, List<String>> published = new LinkedHashMap<>();
        for (FclProgramLinker.Export export : existing.exports()) {
            published.put(export.symbol(), new ArrayList<>(export.publicNames()));
        }
        for (FclProgramLinker.Export export : candidate.exports()) {
            published.computeIfAbsent(export.symbol(), ignored -> new ArrayList<>())
                    .addAll(export.publicNames());
        }
        List<FclProgramLinker.Export> exports = published.entrySet().stream()
                .map(entry -> new FclProgramLinker.Export(entry.getKey(),
                        entry.getValue().stream().distinct().toList())).toList();
        modules.put(key, new FclProgramLinker.Module(existing.packageIdentity(),
                existing.moduleName(), existing.source(), exports));
    }

    private static List<FclProgramLinker.Export> publishedFunctions(
            PackageDescriptor descriptor,
            PackageIndex.Module module,
            List<ImportSpec> imports
    ) {
        Map<String, List<String>> names = new LinkedHashMap<>();
        descriptor.exports().stream().filter(value -> value.moduleName().equals(module.name()))
                .forEach(value -> imports.forEach(spec -> names
                        .computeIfAbsent(value.symbolName(), ignored -> new ArrayList<>())
                        .add(publicName(spec, value.name()))));
        descriptor.entrypoints().stream()
                .filter(value -> value.moduleName().equals(module.name()))
                .forEach(value -> imports.forEach(spec -> names
                        .computeIfAbsent(value.functionName(), ignored -> new ArrayList<>())
                        .add(publicName(spec, value.name()))));
        return names.entrySet().stream().map(entry -> new FclProgramLinker.Export(
                entry.getKey(), entry.getValue().stream().distinct().toList())).toList();
    }

    private static List<FclProgramLinker.Export> dependencyExports(
            PackageDescriptor descriptor,
            PackageIndex.Module module,
            String fileHash
    ) {
        return descriptor.exports().stream()
                .filter(value -> value.moduleName().equals(module.name()))
                .map(value -> new FclProgramLinker.Export(value.symbolName(),
                        List.of(fileHash + "." + value.name())))
                .toList();
    }

    private static String publicName(ImportSpec spec, String exportedName) {
        if (spec.wildcard()) return exportedName;
        String namespace = spec.alias() == null ? spec.target() : spec.alias();
        return namespace + "." + exportedName;
    }

    private static String utf8(StoredObject object, String description) {
        return utf8(object.content().bytes(), description);
    }

    private static String utf8(byte[] bytes, String description) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalStateException(description + " is not valid UTF-8", failure);
        }
    }

    private record ImportSpec(String target, String alias, boolean wildcard) {
        private String importName() {
            return alias == null ? target : alias;
        }
    }

}
