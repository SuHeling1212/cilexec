package com.follarce.extension;

import com.follarce.domain.port.AuditRepository;
import com.follarce.domain.port.AuthRepository;
import com.follarce.domain.port.EffectRepository;
import com.follarce.domain.port.IpcRepository;
import com.follarce.domain.port.PackageRepository;
import com.follarce.domain.port.ProcessRepository;
import com.follarce.domain.port.ProgramRepository;
import com.follarce.domain.port.SchedulerRepository;
import com.follarce.domain.port.TerminalRepository;
import com.follarce.domain.port.TimerRepository;
import com.follarce.domain.port.TransactionContext;
import com.follarce.domain.port.VfsRepository;
import com.follarce.domain.process.CilProcess;
import com.follarce.domain.process.Continuation;
import com.follarce.domain.process.ProcessIdentity;
import com.follarce.domain.program.Program;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.extension.api.CilExecExtension;
import com.follarce.extension.api.ExtensionDescriptor;
import com.follarce.extension.api.ExtensionEffectHandler;
import com.follarce.extension.api.ExtensionEffectPolicy;
import com.follarce.extension.api.ExtensionRegistrar;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclContinuationCodec;
import com.follarce.fcl.FclFunctionRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaExtensionCatalogTest {
    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

    @Test
    void compilesFunctionsEffectsAndDurableNamespacedState() throws Exception {
        JavaExtensionCatalog catalog = JavaExtensionCatalog.compile(List.of(new DemoExtension()));
        assertEquals(List.of("example.demo"), catalog.descriptors().stream()
                .map(ExtensionDescriptor::id).toList());
        assertEquals(java.util.Set.of("demo"), catalog.namespaces());
        assertEquals(1, catalog.effectHandlers().size());

        FclContinuation continuation = new FclContinuation();
        FclFunctionRegistry registry = new FclFunctionRegistry();
        ProcessAndProgram runtime = runtime();
        catalog.installFunctions(registry, new EmptyTransaction(), runtime.process(),
                continuation, NOW);

        assertEquals(1L, registry.invoke("demo.next", List.of(),
                new FclFunctionRegistry.Invocation(10, continuation)));
        assertEquals(2L, registry.invoke("demo.increment", List.of(),
                new FclFunctionRegistry.Invocation(11, continuation)));
        assertEquals(2L, continuation.scope()
                .get("cilexec.extension.12.example.demo.count"));

        var codec = new FclContinuationCodec();
        Continuation.PersistedValue request = new Continuation.PersistedValue(
                codec.valueType(Map.of("message", "hello")),
                codec.valueToJson(Map.of("message", "hello")));
        Object result = codec.valueFromJson(catalog.effectHandlers().getFirst()
                .execute(request, Optional.of("stable-key")).canonicalPayload());
        assertTrue(result instanceof Map<?, ?>);
        assertEquals(Map.of("message", "hello"), ((Map<?, ?>) result).get("value"));
        assertEquals("example.demo", ((Map<?, ?>) result).get("extensionId"));
    }

    @Test
    void rejectsDuplicateIdsFunctionsAndBuiltInNamespaceChanges() {
        assertThrows(IllegalArgumentException.class, () -> JavaExtensionCatalog.compile(
                List.of(new DemoExtension(), new DemoExtension())));
        assertThrows(IllegalArgumentException.class, () -> JavaExtensionCatalog.compile(
                List.of(extension("example.first", registrar -> {
                    registrar.function("demo", "same", _ -> null);
                    registrar.function("demo", "same", _ -> null);
                }))));
        assertThrows(IllegalArgumentException.class, () -> JavaExtensionCatalog.compile(
                List.of(extension("example.bad", registrar ->
                        registrar.function("system", "replace", _ -> null)))));
        assertThrows(IllegalArgumentException.class, () -> JavaExtensionCatalog.compile(
                List.of(extension("example.effect", registrar -> registrar.effect(
                        new ExtensionEffectHandler() {
                            @Override public String effectType() { return "io.output"; }
                            @Override public Object execute(Object request, Optional<String> key) {
                                return null;
                            }
                        })))));
    }

    @Test
    void exposesOneSealedProductionCatalogAndValidatesRecoveryPolicies() {
        assertSame(SourceExtensionIndex.catalog(), SourceExtensionIndex.catalog());
        assertEquals(List.of(), SourceExtensionIndex.catalog().descriptors());
        assertEquals(ExtensionEffectPolicy.Recovery.MANUAL,
                ExtensionEffectPolicy.manual().recovery());
        assertThrows(IllegalArgumentException.class, () ->
                ExtensionEffectPolicy.retryIdempotent(" "));
    }

    @Test
    void installRejectsNamespacesAndBareNamesAlreadyLiveInTheRegistry() {
        FclContinuation continuation = new FclContinuation();
        ProcessAndProgram runtime = runtime();
        FclFunctionRegistry registry = com.follarce.fcl.FclBuiltins.pureRegistry();

        JavaExtensionCatalog catalog = JavaExtensionCatalog.compile(List.of(
                extension("example.array", registrar ->
                        registrar.function("array", "extra", _ -> null))));
        IllegalStateException namespaceConflict = assertThrows(IllegalStateException.class,
                () -> catalog.installFunctions(registry, new EmptyTransaction(),
                        runtime.process(), continuation, NOW));
        assertTrue(namespaceConflict.getMessage().contains("array"));

        JavaExtensionCatalog bare = JavaExtensionCatalog.compile(List.of(
                extension("example.bare", registrar ->
                        registrar.function("demo", "insert", _ -> null))));
        IllegalStateException bareConflict = assertThrows(IllegalStateException.class,
                () -> bare.installFunctions(registry, new EmptyTransaction(),
                        runtime.process(), continuation, NOW));
        assertTrue(bareConflict.getMessage().contains("insert"));
    }

    @Test
    void extensionFunctionsReceiveFclNullArguments() throws Exception {
        JavaExtensionCatalog catalog = JavaExtensionCatalog.compile(List.of(
                extension("example.null", registrar -> registrar.function("nullDemo", "echo",
                        context -> {
                            assertEquals(2, context.arguments().size());
                            assertTrue(context.arguments().contains(null));
                            assertEquals(1L, context.argument(1));
                            assertThrows(IllegalArgumentException.class, () ->
                                    context.argument(2));
                            return context.argument(0);
                        }))));
        FclContinuation continuation = new FclContinuation();
        FclFunctionRegistry registry = new FclFunctionRegistry();
        ProcessAndProgram runtime = runtime();
        catalog.installFunctions(registry, new EmptyTransaction(), runtime.process(),
                continuation, NOW);
        assertNull(registry.invoke("nullDemo.echo",
                java.util.Arrays.asList(null, 1L),
                new FclFunctionRegistry.Invocation(20, continuation)));
    }

    private static ProcessAndProgram runtime() {
        UUID owner = UUID.randomUUID();
        UUID programId = UUID.randomUUID();
        ObjectHash hash = ObjectHash.sha256(new BinaryContent(
                "extension-test".getBytes(StandardCharsets.UTF_8)));
        Program program = new Program(programId, hash, "fcl-1", 1, hash, Optional.empty(),
                1, NOW);
        Continuation persisted = new Continuation(programId, hash, 0, List.of(), List.of(),
                List.of(), List.of(), Optional.empty(), Map.of(), Map.of(), "fcl-1", "1");
        CilProcess process = new CilProcess(new ProcessIdentity(UUID.randomUUID(), 7), owner,
                CilProcess.Status.RUNNING, 0, 1, persisted, Optional.empty(), NOW, NOW);
        return new ProcessAndProgram(process, program);
    }

    private static CilExecExtension extension(String id,
                                              java.util.function.Consumer<ExtensionRegistrar> body) {
        return new CilExecExtension() {
            @Override public ExtensionDescriptor descriptor() {
                return new ExtensionDescriptor(id, "1.0.0", "test");
            }

            @Override public void register(ExtensionRegistrar registrar) {
                body.accept(registrar);
            }
        };
    }

    private static final class DemoExtension implements CilExecExtension {
        @Override public ExtensionDescriptor descriptor() {
            return new ExtensionDescriptor("example.demo", "1.0.0", "Demo extension");
        }

        @Override
        public void register(ExtensionRegistrar registrar) {
            registrar.function("demo", "next", context -> {
                context.requireArity(0);
                long previous = context.state().find("count")
                        .map(Number.class::cast).map(Number::longValue).orElse(0L);
                long next = previous + 1;
                context.state().put("count", next);
                return next;
            }, "increment");
            registrar.effect(new ExtensionEffectHandler() {
                @Override public String effectType() { return "demo.echo"; }

                @Override public Object execute(Object request, Optional<String> key) {
                    return request;
                }
            });
        }
    }

    private record ProcessAndProgram(CilProcess process, Program program) { }

    private static final class EmptyTransaction implements TransactionContext {
        @Override public ProgramRepository programs() { return null; }
        @Override public ProcessRepository processes() { return null; }
        @Override public SchedulerRepository scheduler() { return null; }
        @Override public IpcRepository ipc() { return null; }
        @Override public TimerRepository timers() { return null; }
        @Override public VfsRepository vfs() { return null; }
        @Override public PackageRepository packages() { return null; }
        @Override public EffectRepository effects() { return null; }
        @Override public AuthRepository auth() { return null; }
        @Override public AuditRepository audit() { return null; }
        @Override public TerminalRepository terminal() { return null; }
        @Override public void commit() { }
        @Override public void rollback() { }
        @Override public void close() { }
    }
}
