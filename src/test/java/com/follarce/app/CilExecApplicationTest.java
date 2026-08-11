package com.follarce.app;

import com.follarce.config.CilExecConfig;
import com.follarce.config.DatabaseConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CilExecApplicationTest {
    @Test
    void runtimeCommandUsesRuntimeAssemblyAndBuildIdentityOnly() {
        CilExecConfig config = config();
        BuildInfo build = new BuildInfo("CilExec", "1.0", "revision", 1, 1, 15);
        AtomicInteger configLoads = new AtomicInteger();
        AtomicInteger buildLoads = new AtomicInteger();
        AtomicInteger runtimeRuns = new AtomicInteger();
        AtomicInteger migrations = new AtomicInteger();
        CilExecApplication application = new CilExecApplication(
                () -> {
                    configLoads.incrementAndGet();
                    return config;
                },
                () -> {
                    buildLoads.incrementAndGet();
                    return build;
                },
                (actualConfig, actualBuild) -> {
                    runtimeRuns.incrementAndGet();
                    assertSame(config, actualConfig);
                    assertSame(build, actualBuild);
                    return 23;
                },
                ignored -> migrations.incrementAndGet(),
                (ignoredConfig, ignoredBuild, ignoredOutput) -> {
                    throw new AssertionError("export must not run for runtime");
                });

        assertEquals(23, application.execute(new String[]{"runtime"}));
        assertEquals(1, configLoads.get());
        assertEquals(1, buildLoads.get());
        assertEquals(1, runtimeRuns.get());
        assertEquals(0, migrations.get());
    }

    @Test
    void migrateCommandNeverLoadsRuntimeBuildOrStartsRuntime() {
        CilExecConfig config = config();
        AtomicInteger migrations = new AtomicInteger();
        CilExecApplication application = new CilExecApplication(
                () -> config,
                () -> {
                    throw new AssertionError("build information belongs to Runtime only");
                },
                (ignoredConfig, ignoredBuild) -> {
                    throw new AssertionError("Runtime must not start for migrate");
                },
                actualConfig -> {
                    assertSame(config, actualConfig);
                    migrations.incrementAndGet();
                },
                (ignoredConfig, ignoredBuild, ignoredOutput) -> {
                    throw new AssertionError("export must not run for migrate");
                });

        assertEquals(0, application.execute(new String[]{"migrate"}));
        assertEquals(1, migrations.get());
    }

    @Test
    void invalidCommandFailsBeforeConfigurationOrSecretsAreTouched() {
        AtomicInteger configLoads = new AtomicInteger();
        CilExecApplication application = new CilExecApplication(
                () -> {
                    configLoads.incrementAndGet();
                    return config();
                },
                () -> new BuildInfo("CilExec", "1", "r", 1, 1, 1),
                (config, build) -> 0,
                config -> { },
                (config, build, output) -> { });

        assertThrows(IllegalArgumentException.class,
                () -> application.execute(new String[]{"unknown"}));
        assertEquals(0, configLoads.get());
    }

    @Test
    void exportUsesBuildAndExplicitOutputWithoutStartingRuntimeOrMigration() {
        CilExecConfig config = config();
        BuildInfo build = new BuildInfo("CilExec", "1.0", "revision", 1, 1, 19);
        AtomicInteger migrations = new AtomicInteger();
        AtomicReference<Path> exported = new AtomicReference<>();
        CilExecApplication application = new CilExecApplication(
                () -> config,
                () -> build,
                (ignoredConfig, ignoredBuild) -> {
                    throw new AssertionError("Runtime must not start for export");
                },
                ignored -> migrations.incrementAndGet(),
                (actualConfig, actualBuild, output) -> {
                    assertSame(config, actualConfig);
                    assertSame(build, actualBuild);
                    exported.set(output);
                });

        assertEquals(0, application.execute(new String[]{"export", "backup.db"}));
        assertEquals(Path.of("backup.db"), exported.get());
        assertEquals(0, migrations.get());
    }

    @Test
    void invalidExportPathFailsBeforeConfigurationOrSecretsAreTouched() {
        AtomicInteger configLoads = new AtomicInteger();
        CilExecApplication application = new CilExecApplication(
                () -> {
                    configLoads.incrementAndGet();
                    return config();
                },
                () -> new BuildInfo("CilExec", "1", "r", 1, 1, 1),
                (config, build) -> 0,
                config -> { },
                (config, build, output) -> { });

        assertThrows(IllegalArgumentException.class,
                () -> application.execute(new String[]{"export", "backup.txt"}));
        assertThrows(IllegalArgumentException.class,
                () -> application.execute(new String[]{"export"}));
        assertEquals(0, configLoads.get());
    }

    @Test
    void packageBuildDoesNotLoadDatabaseConfigurationOrBuildIdentity() {
        AtomicInteger configLoads = new AtomicInteger();
        AtomicReference<Path> source = new AtomicReference<>();
        AtomicReference<Path> output = new AtomicReference<>();
        CilExecApplication application = new CilExecApplication(
                () -> {
                    configLoads.incrementAndGet();
                    return config();
                },
                () -> {
                    throw new AssertionError("package build does not need runtime build metadata");
                },
                (config, build) -> 0,
                config -> { },
                (config, build, target) -> { },
                (actualSource, actualOutput) -> {
                    source.set(actualSource);
                    output.set(actualOutput);
                });

        assertEquals(0, application.execute(new String[]{
                "package", "build", "packages/hello", "dist/hello.db"}));
        assertEquals(Path.of("packages/hello"), source.get());
        assertEquals(Path.of("dist/hello.db"), output.get());
        assertEquals(0, configLoads.get());
    }

    private static CilExecConfig config() {
        DatabaseConfig runtime = database("runtime", "/secrets/runtime", 6);
        DatabaseConfig effects = database("effect", "/secrets/effect", 1);
        DatabaseConfig migrator = database("migrator", "/secrets/migrator", 1);
        DatabaseConfig exporter = database("exporter", "/secrets/exporter", 1);
        return new CilExecConfig("primary", 42L, runtime, effects, migrator, exporter,
                2, 1, Duration.ofSeconds(10),
                Duration.ofMillis(25), Duration.ofMillis(25), Duration.ofSeconds(2),
                Duration.ofSeconds(10), 8081, false);
    }

    private static DatabaseConfig database(String role, String secret, int poolSize) {
        return new DatabaseConfig("jdbc:postgresql://database/cilexec", role,
                Path.of(secret), Optional.of(Path.of("/certificates/postgres-ca.pem")),
                poolSize, 0, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(30), "cilexec-" + role, role.equals("exporter"));
    }
}
