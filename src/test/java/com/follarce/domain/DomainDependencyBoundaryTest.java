package com.follarce.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainDependencyBoundaryTest {
    private static final Path DOMAIN_SOURCE = Path.of("src/main/java/com/follarce/domain");
    private static final List<String> FORBIDDEN = List.of(
            "java.sql",
            "javax.sql",
            "org.postgresql",
            "com.zaxxer.hikari",
            "org.flywaydb",
            "org.sqlite",
            "ch.qos.logback",
            "org.slf4j");

    @Test
    void domainOnlyImportsJdkAndItsOwnPackages() throws IOException {
        for (Path source : javaSources()) {
            List<String> externalProjectImports = Files.readAllLines(source).stream()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import com.follarce."))
                    .filter(line -> !line.startsWith("import com.follarce.domain."))
                    .toList();
            assertTrue(externalProjectImports.isEmpty(),
                    () -> source + " crosses the domain boundary: " + externalProjectImports);
        }
    }

    @Test
    void domainContainsNoInfrastructureDependencies() throws IOException {
        for (Path source : javaSources()) {
            String content = Files.readString(source).toLowerCase();
            for (String forbidden : FORBIDDEN) {
                assertFalse(content.contains(forbidden),
                        () -> source + " contains forbidden dependency " + forbidden);
            }
        }
    }

    @Test
    void repositoryPortsHaveNoCompletionMethods() throws IOException {
        Path ports = DOMAIN_SOURCE.resolve("port");
        try (var files = Files.list(ports)) {
            for (Path source : files.filter(path -> path.getFileName().toString()
                    .endsWith("Repository.java")).toList()) {
                String content = Files.readString(source);
                assertFalse(content.matches("(?s).*\\b(commit|rollback)\\s*\\(.*"),
                        () -> source + " owns transaction completion");
            }
        }
    }

    private static List<Path> javaSources() throws IOException {
        try (var files = Files.walk(DOMAIN_SOURCE)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }
}
