package com.follarce.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mechanical guards for the first Kernel dependency-inversion slice. */
class CoreDependencyBoundaryTest {
    private static final Path APPLICATION = Path.of("src/main/java/com/follarce/application");

    @Test
    void userRegistrarDoesNotInheritTheRuntimeAdapter() {
        org.junit.jupiter.api.Assertions.assertEquals(Object.class,
                FclUserRuntimeFunctions.class.getSuperclass());
        for (var field : FclUserRuntimeFunctions.class.getDeclaredFields()) {
            assertFalse(FclRuntimeFunctions.class.isAssignableFrom(field.getType()),
                    "User registration must not retain the full runtime adapter");
        }
        assertFalse(sourceContains(APPLICATION.resolve("FclUserRuntimeFunctions.java"),
                "FclRuntimeFunctions"));
    }

    @Test
    void focusedRegistrarsDoNotInheritTheAssemblyCoordinator() {
        for (Class<?> registrar : List.of(FclCoreRuntimeFunctions.class,
                FclFileRuntimeFunctions.class,
                FclNetworkRuntimeFunctions.class, FclProcessRuntimeFunctions.class,
                FclPackageRuntimeFunctions.class)) {
            assertFalse(FclRuntimeFunctions.class.isAssignableFrom(registrar),
                    () -> registrar.getSimpleName() + " must not inherit the assembly coordinator");
        }
    }

    @Test
    void artifactLoaderDoesNotDependOnSliceOrchestration() throws IOException {
        assertFalse(Files.readString(APPLICATION.resolve("FclProgramLoader.java"))
                .contains("ProcessStatementExecutor"));
        assertFalse(Files.readString(APPLICATION.resolve("FclSourceModuleLinker.java"))
                .contains("ProcessStatementExecutor"));
    }

    @Test
    void fclKernelRegistrarsDoNotImportTerminalAdapters() throws IOException {
        for (String name : List.of("FclRuntimeFunctions.java", "FclFileRuntimeFunctions.java",
                "FclProcessRuntimeFunctions.java", "FclPackageRuntimeFunctions.java",
                "FclNetworkRuntimeFunctions.java")) {
            assertNoImport(APPLICATION.resolve(name), "com.follarce.terminal.");
        }
    }

    @Test
    void processSliceDoesNotImportTerminalOrPostgresImplementations() throws IOException {
        Path executor = APPLICATION.resolve("ProcessStatementExecutor.java");
        assertNoImport(executor, "com.follarce.terminal.");
        assertNoImport(executor, "com.follarce.persistence.postgres.");
    }

    @Test
    void kernelPackagesDoNotReferenceTerminalAdaptersOrPostgres() throws IOException {
        for (Path root : List.of(
                Path.of("src/main/java/com/follarce/domain"),
                Path.of("src/main/java/com/follarce/fcl"),
                Path.of("src/main/java/com/follarce/scheduler"),
                APPLICATION)) {
            assertNoReferenceBelow(root, "com.follarce.terminal.");
            assertNoReferenceBelow(root, "com.follarce.persistence.postgres.");
        }
    }

    @Test
    void interactiveContinuationKeysRemainFormatCompatible() {
        org.junit.jupiter.api.Assertions.assertEquals("cilexec.repl.library",
                InteractiveProcessState.LIBRARY_SCOPE_KEY);
        org.junit.jupiter.api.Assertions.assertEquals("cilexec.repl.terminalProcess",
                InteractiveProcessState.PROCESS_SCOPE_KEY);
        org.junit.jupiter.api.Assertions.assertEquals("cilexec.repl.terminalSession",
                InteractiveProcessState.SESSION_SCOPE_KEY);
        org.junit.jupiter.api.Assertions.assertEquals("cilexec.terminal.outputRoute",
                InteractiveProcessState.OUTPUT_ROUTE_SCOPE_KEY);
    }

    private static void assertNoImport(Path source, String forbidden) throws IOException {
        List<String> violating = Files.readAllLines(source).stream()
                .map(String::strip)
                .filter(line -> line.startsWith("import "))
                .filter(line -> line.contains(forbidden))
                .toList();
        assertFalse(violating.size() > 0, () -> source + " has forbidden imports: " + violating);
    }

    private static void assertNoReferenceBelow(Path root, String forbidden) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> violating = paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> sourceContains(path, forbidden)).toList();
            assertTrue(violating.isEmpty(), () -> root + " references " + forbidden
                    + " from " + violating);
        }
    }

    private static boolean sourceContains(Path source, String value) {
        try {
            return Files.readString(source).contains(value);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + source, failure);
        }
    }
}
