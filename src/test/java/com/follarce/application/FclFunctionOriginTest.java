package com.follarce.application;

import com.follarce.domain.packageinfo.PackageRelease;
import com.follarce.domain.packageinfo.ProcessPackageBinding;
import com.follarce.domain.vfs.BinaryContent;
import com.follarce.domain.vfs.ObjectHash;
import com.follarce.domain.vfs.StoredObject;
import com.follarce.fcl.FclCompiler;
import com.follarce.fcl.FclContinuation;
import com.follarce.fcl.FclRuntime;
import com.follarce.fcl.FclStepResult;
import com.follarce.package_manager.PackageBuilder;
import com.follarce.persistence.sqlite.PackageDescriptor;
import com.follarce.persistence.sqlite.SqlitePackageReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FclFunctionOriginTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void distinguishesRuntimeAndImportedPackageFunctions() throws Exception {
        ProgramServiceTest.TestPersistence persistence =
                new ProgramServiceTest.TestPersistence();
        UUID ownerId = UUID.randomUUID();
        String source = """
                runtimeOrigin = util.which("file.read")
                packageOrigin = util.which("e.open")
                barePackageOrigin = util.which("open")
                missingOrigin = util.which("e.missing")
                """;
        var program = new ProgramService(persistence).create(ownerId, source);
        var process = new ProcessService(persistence).create(ownerId, program, Optional.empty());

        Path packageFile = temporaryDirectory.resolve("editor.db");
        PackageDescriptor descriptor = new PackageBuilder().build(
                Path.of("market/sources/editor"), packageFile);
        byte[] bytes = Files.readAllBytes(packageFile);
        StoredObject database = StoredObject.create(new BinaryContent(bytes),
                "application/vnd.sqlite3", Instant.now());
        persistence.vfs.saveObject(database);
        PackageRelease release = new PackageRelease(
                new PackageRelease.Coordinate(descriptor.namespace(), descriptor.name(),
                        descriptor.version()),
                new PackageRelease.Hash(new ObjectHash(descriptor.packageHash())),
                database.objectHash(), new ObjectHash(descriptor.databaseFileHash()),
                Instant.now());
        persistence.packages.releases.put(release.packageHash(), release);
        persistence.packages.saveProcessBinding(new ProcessPackageBinding(
                process.identity().processUid(), "e", UUID.randomUUID(),
                release.packageHash(), Instant.now()));

        FclContinuation continuation = new FclContinuation();
        var compiled = new FclCompiler().compile(source);
        int steps = 0;
        while (!continuation.halted() && steps++ < 20) {
            FclStepResult result = new FclRuntime(FclRuntimeFunctions.create(
                    persistence, process, program, continuation,
                    Clock.systemUTC().instant())).executeOne(compiled, continuation);
            assertFalse(result.status() == FclStepResult.Status.FAILED,
                    () -> String.valueOf(result.value()));
        }

        assertTrue(continuation.halted());
        assertEquals(0L, continuation.scope().get("runtimeOrigin"));
        assertEquals(descriptor.databaseFileHash(),
                continuation.scope().get("packageOrigin"));
        assertEquals(descriptor.databaseFileHash(),
                continuation.scope().get("barePackageOrigin"));
        assertNull(continuation.scope().get("missingOrigin"));
        assertEquals(ObjectHash.sha256(new BinaryContent(bytes)).value(),
                descriptor.databaseFileHash());
        assertEquals(descriptor.databaseFileHash(),
                new SqlitePackageReader().inspect(bytes).databaseFileHash());
    }
}
