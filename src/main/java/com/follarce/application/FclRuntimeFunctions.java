package com.follarce.application;

import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.program.Program;
import com.follarce.extension.JavaExtensionCatalog;
import com.follarce.extension.SourceExtensionIndex;
import com.follarce.fcl.FclBuiltins;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclFunctionRegistry;
import com.follarce.fcl.FclRuntimeException;

import java.time.Instant;
import java.util.function.Consumer;

/** Assembles the focused FCL registrars for one durable execution slice. */
public final class FclRuntimeFunctions extends FclVfsRuntimeSupport {
    protected FclRuntimeFunctions(TransactionContext transaction, CilProcess process, Program program,
                                  FclContinuation continuation, Instant now,
                                  JavaExtensionCatalog extensions) {
        this(transaction, process, program, continuation, now, extensions,
                FclBuiltins.pureRegistry(), FclRuntimeFunctions::volatileUnavailable,
                FclRuntimeFunctions::terminalRenderUnavailable);
    }

    protected FclRuntimeFunctions(FclRuntimeFunctions source) {
        super(source);
    }

    protected FclRuntimeFunctions(TransactionContext transaction, CilProcess process, Program program,
                                  FclContinuation continuation, Instant now,
                                  JavaExtensionCatalog extensions, FclFunctionRegistry registry) {
        this(transaction, process, program, continuation, now, extensions, registry,
                FclRuntimeFunctions::volatileUnavailable,
                FclRuntimeFunctions::terminalRenderUnavailable);
    }

    protected FclRuntimeFunctions(TransactionContext transaction, CilProcess process, Program program,
                                  FclContinuation continuation, Instant now,
                                  JavaExtensionCatalog extensions, FclFunctionRegistry registry,
                                  Consumer<VolatileProcessRequest> volatileProcessRequests,
                                  Consumer<ProcessOutput> processOutputs) {
        super(transaction, process, program, continuation, now, extensions, registry,
                volatileProcessRequests, processOutputs);
    }

    static FclFunctionRegistry create(TransactionContext transaction, CilProcess process,
                                      Program program, FclContinuation continuation, Instant now) {
        return create(transaction, process, program, continuation, now,
                SourceExtensionIndex.catalog());
    }

    static FclFunctionRegistry create(TransactionContext transaction, CilProcess process,
                                      Program program, FclContinuation continuation, Instant now,
                                      JavaExtensionCatalog extensions) {
        return create(transaction, process, program, continuation, now, extensions,
                FclRuntimeFunctions::volatileUnavailable,
                FclRuntimeFunctions::terminalRenderUnavailable);
    }

    static FclFunctionRegistry create(TransactionContext transaction, CilProcess process,
                                      Program program, FclContinuation continuation, Instant now,
                                      JavaExtensionCatalog extensions,
                                      Consumer<VolatileProcessRequest> volatileProcessRequests) {
        return create(transaction, process, program, continuation, now, extensions,
                volatileProcessRequests, FclRuntimeFunctions::terminalRenderUnavailable);
    }

    static FclFunctionRegistry create(TransactionContext transaction, CilProcess process,
                                      Program program, FclContinuation continuation, Instant now,
                                      JavaExtensionCatalog extensions,
                                      Consumer<VolatileProcessRequest> volatileProcessRequests,
                                      Consumer<ProcessOutput> processOutputs) {
        FclRuntimeFunctions functions = new FclRuntimeFunctions(transaction, process, program,
                continuation, now, extensions, FclBuiltins.pureRegistry(),
                volatileProcessRequests, processOutputs);
        functions.register();
        return functions.registry;
    }

    private static void volatileUnavailable(VolatileProcessRequest request) {
        throw new FclRuntimeException(
                "process.run is only available while executing a durable process");
    }

    private static void terminalRenderUnavailable(ProcessOutput output) {
        throw new FclRuntimeException(
                "term.render is only available while executing a durable process");
    }

    private void register() {
        FclCoreRuntimeFunctions core = new FclCoreRuntimeFunctions(this);
        core.registerPathState();
        core.registerEnvironment();
        core.registerUtilityAndIo();
        core.registerMemory();

        new FclFileRuntimeFunctions(this).registerFiles();

        FclProcessRuntimeFunctions processes = new FclProcessRuntimeFunctions(this);
        processes.registerProcesses();
        processes.registerSwapPool();
        processes.registerIpc();
        processes.registerSystem();

        new FclUserRuntimeFunctions(transaction.auth(), process.ownerId(), now, registry)
                .registerUsers();
        core.registerResourceControl();
        new FclNetworkRuntimeFunctions(this).registerNetworkAndSockets();

        FclPackageRuntimeFunctions packages = new FclPackageRuntimeFunctions(this);
        packages.registerPackages();
        packages.registerPackageData();
        packages.registerMarket();

        extensions.installFunctions(registry, transaction, process, continuation, now);
    }
}
