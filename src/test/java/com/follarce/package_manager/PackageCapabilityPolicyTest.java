package com.follarce.package_manager;

import com.follarce.domain.packageinfo.PackageKind;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Capability inference for the 0.0.5 receiver-object FCL surface. */
class PackageCapabilityPolicyTest {
    private static final String OPEN_THEN_WRITE = """
            path = "/note.txt"
            if file.path(path).exists() {
                file.open(path).write("changed")
            } else {
                file.create(path, "created")
            }
            """;

    @Test
    void requiresCapabilitiesUsedThroughChainedObjectMethods() {
        SecurityException missingWrite = assertThrows(SecurityException.class,
                () -> inspect(OPEN_THEN_WRITE, Map.of("vfs.read", true)));
        assertTrue(missingWrite.getMessage().contains("vfs.write"),
                missingWrite.getMessage());

        assertDoesNotThrow(() -> inspect(OPEN_THEN_WRITE,
                Map.of("vfs.read", true, "vfs.write", true)));
    }

    @Test
    void tracksNamedHandlesToTheirCapability() {
        String source = """
                h = file.open("/note.txt")
                text = h.read()
                h.append("!")
                """;
        SecurityException missing = assertThrows(SecurityException.class,
                () -> inspect(source, Map.of("vfs.read", true)));
        assertTrue(missing.getMessage().contains("vfs.write"), missing.getMessage());
        assertDoesNotThrow(() -> inspect(source,
                Map.of("vfs.read", true, "vfs.write", true)));
    }

    @Test
    void packageDataSpaceMethodsRequireThePackageDataCapability() {
        String source = """
                space = packageData.current()
                space.write("cache/worker.fcl", "value = 1")
                """;
        SecurityException missing = assertThrows(SecurityException.class,
                () -> inspect(source, Map.of("vfs.write", true)));
        assertTrue(missing.getMessage().contains("package.data"), missing.getMessage());
        assertDoesNotThrow(() -> inspect(source,
                Map.of("package.data", true)));
    }

    @Test
    void processHandlesRequireProcessControl() {
        String source = """
                child = process.find(7)
                io.println(child.status())
                """;
        SecurityException missing = assertThrows(SecurityException.class,
                () -> inspect(source, Map.of("vfs.read", true)));
        assertTrue(missing.getMessage().contains("process.control"), missing.getMessage());
        assertDoesNotThrow(() -> inspect(source, Map.of("process.control", true)));
    }

    private static PackageCapabilityPolicy inspect(String source,
                                                   Map<String, Boolean> declared) {
        List<PackageManifest.Capability> capabilities = declared.entrySet().stream()
                .map(entry -> new PackageManifest.Capability(entry.getKey(), entry.getValue(),
                        "test capability"))
                .toList();
        PackageManifest manifest = new PackageManifest("demo", "cap", "1.0.0", "fcl-0.0.5",
                PackageKind.LIBRARY,
                List.of(new PackageManifest.Module("main", "main.fcl")),
                List.of(), List.of(), List.of(), List.of(), capabilities);
        byte[] database = new PackageBuilder().build(manifest,
                path -> source.getBytes(StandardCharsets.UTF_8));
        PackageDescriptor descriptor = new SqlitePackageReader().inspect(database);
        return PackageCapabilityPolicy.inspect(database, descriptor);
    }
}
