package com.follarce.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelExtensionBoundaryTest {
    private static final Path MAIN_SOURCE = Path.of("src/main/java/com/follarce");

    @Test
    void kernelApiDoesNotDependOnKernelImplementations() throws IOException {
        for (Path source : javaFiles(MAIN_SOURCE.resolve("kernel/api"))) {
            List<String> forbidden = Files.readAllLines(source).stream()
                    .filter(line -> line.startsWith("import com.follarce."))
                    .filter(line -> !line.startsWith("import com.follarce.kernel.api."))
                    .toList();
            assertTrue(forbidden.isEmpty(), () -> source + " crosses the API boundary: " + forbidden);
        }
    }

    @Test
    void kernelNeverImportsConcreteBuiltins() throws IOException {
        for (Path source : javaFiles(MAIN_SOURCE.resolve("kernel"))) {
            String content = Files.readString(source);
            assertFalse(content.contains("com.follarce.extension.builtin"),
                    () -> source + " imports a concrete built-in extension");
        }
    }

    @Test
    void runtimeClasspathDiscoveryCannotBeReintroduced() throws IOException {
        for (Path source : javaFiles(MAIN_SOURCE)) {
            String content = Files.readString(source);
            assertFalse(content.contains("ServiceLoader"),
                    () -> source + " enables classpath extension discovery");
        }
        assertFalse(Files.exists(Path.of("src/main/resources/META-INF/services/"
                + "com.follarce.kernel.api.function.FunctionProvider")));
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
