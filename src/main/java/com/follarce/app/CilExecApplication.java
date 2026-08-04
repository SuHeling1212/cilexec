package com.follarce.app;

import com.follarce.config.CilExecConfig;
import com.follarce.exporter.LogicalExportReport;
import com.follarce.exporter.LogicalExportService;
import com.follarce.persistence.postgres.connection.DataSourceFactory;
import com.follarce.persistence.postgres.connection.FlywayMigrator;
import com.follarce.package_manager.PackageBuilder;
import com.follarce.host.HostVfsImportService;
import com.follarce.persistence.postgres.error.PersistenceFailure;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

/** Command dispatcher for the dedicated migration and Runtime processes. */
public final class CilExecApplication {
    private static final Logger LOG = LoggerFactory.getLogger(CilExecApplication.class);
    private static final int USAGE_ERROR = 64;
    private static final int RUNTIME_FAILURE = 70;

    private final Supplier<CilExecConfig> configSource;
    private final Supplier<BuildInfo> buildSource;
    private final RuntimeAction runtime;
    private final RuntimeAction terminal;
    private final MigrationAction migration;
    private final ExportAction export;
    private final PackageBuildAction packageBuild;

    CilExecApplication(
            Supplier<CilExecConfig> configSource,
            Supplier<BuildInfo> buildSource,
            RuntimeAction runtime,
            MigrationAction migration,
            ExportAction export
    ) {
        this(configSource, buildSource, runtime, migration, export,
                (source, output) -> {
                    throw new AssertionError("package build action was not configured");
                }, (config, build) -> {
                    throw new AssertionError("terminal action was not configured");
                });
    }

    CilExecApplication(
            Supplier<CilExecConfig> configSource,
            Supplier<BuildInfo> buildSource,
            RuntimeAction runtime,
            MigrationAction migration,
            ExportAction export,
            PackageBuildAction packageBuild
    ) {
        this(configSource, buildSource, runtime, migration, export, packageBuild,
                (config, build) -> {
                    throw new AssertionError("terminal action was not configured");
                });
    }

    CilExecApplication(
            Supplier<CilExecConfig> configSource,
            Supplier<BuildInfo> buildSource,
            RuntimeAction runtime,
            MigrationAction migration,
            ExportAction export,
            PackageBuildAction packageBuild,
            RuntimeAction terminal
    ) {
        this.configSource = Objects.requireNonNull(configSource, "configSource");
        this.buildSource = Objects.requireNonNull(buildSource, "buildSource");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.migration = Objects.requireNonNull(migration, "migration");
        this.export = Objects.requireNonNull(export, "export");
        this.packageBuild = Objects.requireNonNull(packageBuild, "packageBuild");
    }

    public static int run(String[] arguments) {
        CilExecApplication application = new CilExecApplication(
                CilExecConfig::load,
                BuildInfo::load,
                CilExecApplication::runRuntime,
                CilExecApplication::runMigration,
                CilExecApplication::runExport,
                CilExecApplication::runPackageBuild,
                CilExecApplication::runTerminal);
        try {
            return application.execute(arguments);
        } catch (IllegalArgumentException usage) {
            System.err.println(usage.getMessage());
            return USAGE_ERROR;
        } catch (Throwable failure) {
            LOG.error("CilExec command failed", failure);
            return RUNTIME_FAILURE;
        }
    }

    int execute(String[] arguments) {
        ApplicationCommand command = ApplicationCommand.parse(arguments);
        return switch (command) {
            case TERMINAL -> terminal.run(config(),
                    Objects.requireNonNull(buildSource.get(), "buildInfo"));
            case RUNTIME -> runtime.run(config(),
                    Objects.requireNonNull(buildSource.get(), "buildInfo"));
            case MIGRATE -> {
                migration.run(config());
                yield 0;
            }
            case EXPORT -> {
                export.run(config(), Objects.requireNonNull(buildSource.get(), "buildInfo"),
                        ApplicationCommand.exportPath(arguments));
                yield 0;
            }
            case PACKAGE_BUILD -> {
                packageBuild.run(ApplicationCommand.packageSourcePath(arguments),
                        ApplicationCommand.packageOutputPath(arguments));
                yield 0;
            }
            case HOST_MOVE -> {
                runHostMove(config(), ApplicationCommand.hostSourcePath(arguments),
                        ApplicationCommand.hostTargetPath(arguments),
                        ApplicationCommand.hostUsername(arguments));
                yield 0;
            }
        };
    }

    private CilExecConfig config() {
        return Objects.requireNonNull(configSource.get(), "config");
    }

    private static int runRuntime(CilExecConfig config, BuildInfo buildInfo) {
        return runLifecycle(RuntimeBootstrap.assemble(config, buildInfo));
    }

    private static int runTerminal(CilExecConfig config, BuildInfo buildInfo) {
        return runLifecycle(RuntimeBootstrap.assembleTerminal(config, buildInfo,
                com.follarce.terminal.TerminalSettings.load()));
    }

    private static int runLifecycle(RuntimeLifecycle lifecycle) {
        Thread shutdownHook = Thread.ofPlatform().name("cilexec-sigterm")
                .unstarted(() -> lifecycle.shutdown("SIGTERM"));
        Runtime runtime = Runtime.getRuntime();
        runtime.addShutdownHook(shutdownHook);
        try {
            lifecycle.start();
            lifecycle.awaitStop();
            return lifecycle.state() == RuntimeLifecycle.State.FENCED ? RUNTIME_FAILURE : 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            lifecycle.shutdown("main thread interrupted");
            return RUNTIME_FAILURE;
        } catch (PersistenceFailure failure) {
            if (failure.kind() == PersistenceFailure.Kind.RUNTIME_FENCED) {
                LOG.warn("Runtime was fenced by another CilExec Runtime", failure);
                return RUNTIME_FAILURE;
            }
            throw failure;
        } finally {
            try {
                runtime.removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // The VM is already running shutdown hooks.
            }
            lifecycle.close();
        }
    }

    private static void runMigration(CilExecConfig config) {
        FlywayMigrator migrator = new FlywayMigrator(config.migratorDatabase());
        migrator.migrate();
        migrator.validate();
    }

    private static void runExport(CilExecConfig config, BuildInfo buildInfo, Path output) {
        try (HikariDataSource dataSource = DataSourceFactory.create(config.migratorDatabase())) {
            LogicalExportReport report = new LogicalExportService(dataSource, Clock.systemUTC())
                    .export(output, buildInfo);
            System.out.printf("Exported %d tables and %d rows to %s (SHA-256 %s)%n",
                    report.tableCount(), report.rowCount(), report.database(),
                    report.manifestSha256());
        }
    }

    private static void runPackageBuild(Path source, Path output) {
        var descriptor = new PackageBuilder().build(source, output);
        System.out.printf("Built package %s at %s (package hash %s, file hash %s)%n",
                descriptor.coordinate(), output.toAbsolutePath(), descriptor.packageHash(),
                descriptor.databaseFileHash());
    }

    private static void runHostMove(CilExecConfig config, Path source, String target,
                                    String username) {
        try (HikariDataSource dataSource = DataSourceFactory.create(config.runtimeDatabase())) {
            var report = new HostVfsImportService(new JdbcTransactionExecutor(dataSource),
                    Clock.systemUTC()).importFile(source, target, username);
            System.out.printf("Imported %d bytes for %s into VFS %s (node %s)%n",
                    report.bytes(), report.username(), report.vfsPath(), report.nodeId());
        }
    }

    @FunctionalInterface
    interface RuntimeAction {
        int run(CilExecConfig config, BuildInfo buildInfo);
    }

    @FunctionalInterface
    interface MigrationAction {
        void run(CilExecConfig config);
    }

    @FunctionalInterface
    interface ExportAction {
        void run(CilExecConfig config, BuildInfo buildInfo, Path output);
    }

    @FunctionalInterface
    interface PackageBuildAction {
        void run(Path source, Path output);
    }
}
