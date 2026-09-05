package com.follarce.application;

import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.program.Program;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclPath;
import com.follarce.fcl.FclProgram;
import com.follarce.fcl.FclProgramLinker;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclRuntimeException;
import com.follarce.fcl.FclScope;
import com.follarce.fcl.FclStepResult;
import com.follarce.extension.JavaExtensionCatalog;

import java.time.Instant;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolves persisted source imports and initializes source modules outside slice orchestration. */
final class FclSourceModuleLinker {
    static final String SOURCE_IMPORTS_SCOPE_KEY = "cilexec.fcl.sourceImports";
    private static final int MAX_MODULE_STEPS = 4_096;

    private final FclRuntime fixedRuntime;
    private final JavaExtensionCatalog extensions;
    private final FclProgramLinker programLinker = new FclProgramLinker();
    private final FclSourceIncludes sourceIncludes = new FclSourceIncludes();

    FclSourceModuleLinker(FclRuntime fixedRuntime, JavaExtensionCatalog extensions) {
        this.fixedRuntime = fixedRuntime;
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    void resolveDirective(
            com.follarce.domain.port.TransactionContext transaction,
            CilProcess process,
            Program program,
            FclContinuation continuation,
            Instant now
    ) {
        FclContinuation.WaitState wait = continuation.waitState();
        if (wait.kind() == FclContinuation.WaitKind.NONE
                || wait.kind() == FclContinuation.WaitKind.EXTERNAL) return;
        String target = FclProgramLoader.normalizeImport(wait.key());
        if (wait.kind() == FclContinuation.WaitKind.INCLUDE) {
            continuation.rejectDirective(
                    "include requires a compiled source dependency; unresolved include: " + target);
            return;
        }
        Optional<PackageRelease> release = directRelease(transaction, process, target);
        if (!FclProgramLoader.isSha256(target)) {
            resolveSourceImport(transaction, process, program, continuation, wait, target, now);
            continuation.clearWait();
            return;
        }
        if (release.isEmpty()) {
            continuation.rejectDirective("Unresolved package import: " + target);
            return;
        }
        // Last-wins rebinding: re-importing the same alias in the same process re-pins it to
        // the newest hash. Already-linked programs keep the module they were linked with;
        // the pin only affects future compilation.
        ProcessPackageBinding resolved = new ProcessPackageBinding(
                process.identity().processUid(), importName(wait, target),
                release.orElseThrow().packageHash(), now);
        transaction.packages().saveProcessBinding(resolved);
        continuation.clearWait();
    }

    private void resolveSourceImport(com.follarce.domain.port.TransactionContext transaction,
                                     CilProcess process, Program program,
                                     FclContinuation continuation,
                                     FclContinuation.WaitState wait, String target, Instant now) {
        String origin = FclPath.resolve(continuation, target);
        String source = sourceIncludes.expandFile(transaction, process.ownerId(), target,
                FclPath.current(continuation));
        String binding = importName(wait, target);
        FclProgram imported = new FclCompiler().compile(source);
        List<FclProgramLinker.Export> exports = sourceExports(imported, wait.payload());
        FclProgramLinker.Module module = new FclProgramLinker.Module(origin, binding, source, exports);
        programLinker.validateLibraryModule(module);
        Map<String, Object> imports = sourceImports(continuation);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("origin", origin);
        value.put("source", source);
        value.put("bindings", initializeSourceModule(transaction, process, program, imported, now));
        Object alias = wait.payload().get("alias");
        if (alias instanceof String text) value.put("alias", text);
        imports.put(binding, value);
        continuation.globalScope().put(SOURCE_IMPORTS_SCOPE_KEY, imports);
    }

    FclProgram link(com.follarce.domain.port.TransactionContext transaction,
                                         CilProcess process, Program program,
                                         FclContinuation continuation, Instant now,
                                         FclProgram base) {
        Map<String, Object> imports = sourceImports(continuation);
        if (imports.isEmpty()) return base;
        List<FclProgramLinker.Module> modules = new ArrayList<>();
        boolean updated = false;
        for (Map.Entry<String, Object> entry : imports.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> value)
                    || !(value.get("origin") instanceof String origin)
                    || !(value.get("source") instanceof String source)) {
                throw new FclRuntimeException("Persisted source import is invalid: " + entry.getKey());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            if (value.get("alias") instanceof String alias) payload.put("alias", alias);
            FclProgram imported = new FclCompiler().compile(source);
            Map<String, Object> bindings = persistedSourceBindings(value);
            if (bindings == null) {
                FclProgramLinker.Module module = new FclProgramLinker.Module(origin, entry.getKey(),
                        source, sourceExports(imported, payload));
                programLinker.validateLibraryModule(module);
                bindings = initializeSourceModule(transaction, process, program, imported, now);
                Map<String, Object> migrated = new LinkedHashMap<>();
                value.forEach((key, entryValue) -> {
                    if (key instanceof String text) migrated.put(text, entryValue);
                });
                migrated.put("bindings", bindings);
                imports.put(entry.getKey(), migrated);
                updated = true;
            }
            modules.add(new FclProgramLinker.Module(origin, entry.getKey(), source,
                    sourceExports(imported, payload), bindings));
        }
        if (updated) continuation.globalScope().put(SOURCE_IMPORTS_SCOPE_KEY, imports);
        return programLinker.link(base, modules);
    }

    private Map<String, Object> initializeSourceModule(
            com.follarce.domain.port.TransactionContext transaction, CilProcess process,
            Program program, FclProgram imported, Instant now
    ) {
        FclContinuation module = new FclContinuation();
        FclRuntime moduleRuntime = fixedRuntime != null ? fixedRuntime
                : new FclRuntime(FclRuntimeFunctions.create(transaction, process, program,
                module, now, extensions));
        int steps = 0;
        while (!module.halted() && steps++ < MAX_MODULE_STEPS) {
            FclStepResult step = moduleRuntime.executeOne(imported, module);
            if (step.status() == FclStepResult.Status.DIRECTIVE
                    || step.status() == FclStepResult.Status.WAITING) {
                throw new FclRuntimeException("Imported source module cannot suspend during "
                        + "initialization");
            }
            if (step.status() == FclStepResult.Status.FAILED) {
                throw new FclRuntimeException("Source module initialization failed: " + step.value());
            }
        }
        if (!module.halted()) {
            throw new FclRuntimeException("Source module initialization exceeds "
                    + MAX_MODULE_STEPS + " steps");
        }
        return module.scope().persistedValues();
    }

    private static Map<String, Object> persistedSourceBindings(Map<?, ?> sourceImport) {
        Object value = sourceImport.get("bindings");
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> bindings)) {
            throw new FclRuntimeException("Persisted source import bindings are invalid");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        bindings.forEach((name, binding) -> {
            if (!(name instanceof String text)) throw new FclRuntimeException(
                    "Persisted source import binding name is invalid");
            result.put(text, binding);
        });
        return result;
    }

    private static List<FclProgramLinker.Export> sourceExports(FclProgram imported,
                                                                Map<String, Object> payload) {
        Object alias = payload.get("alias");
        return imported.functions().entrySet().stream()
                .filter(entry -> entry.getValue().publicBinding())
                .map(Map.Entry::getKey)
                .map(name -> new FclProgramLinker.Export(name,
                        List.of(alias instanceof String prefix ? prefix + "." + name : name)))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sourceImports(FclContinuation continuation) {
        FclScope root = continuation.globalScope();
        if (!root.contains(SOURCE_IMPORTS_SCOPE_KEY)) return new LinkedHashMap<>();
        Object value = root.get(SOURCE_IMPORTS_SCOPE_KEY);
        if (!(value instanceof Map<?, ?> map)) {
            throw new FclRuntimeException("Persisted source imports are invalid");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((name, binding) -> {
            if (!(name instanceof String text)) throw new FclRuntimeException(
                    "Persisted source import name is invalid");
            result.put(text, binding);
        });
        return result;
    }

    static Optional<PackageRelease> directRelease(
            com.follarce.domain.port.TransactionContext transaction, CilProcess process,
            String target) {
        if (!FclProgramLoader.isSha256(target)) return Optional.empty();
        return transaction.packages().findInstalledReleaseByDatabaseFileHash(
                process.ownerId(),
                new ObjectHash(target.toLowerCase(java.util.Locale.ROOT)));
    }

    private static String importName(FclContinuation.WaitState wait, String target) {
        Object alias = wait.payload().get("alias");
        return alias instanceof String name ? name : target;
    }
}
