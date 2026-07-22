package com.follarce.app;

import com.follarce.config.CilExecConfig;
import com.follarce.exporter.LogicalExportReport;
import com.follarce.exporter.LogicalExportService;
import com.follarce.persistence.postgres.connection.DataSourceFactory;
import com.follarce.persistence.postgres.connection.FlywayMigrator;
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
    private final MigrationAction migration;
    private final ExportAction export;

    CilExecApplication(
            Supplier<CilExecConfig> configSource,
            Supplier<BuildInfo> buildSource,
            RuntimeAction runtime,
            MigrationAction migration,
            ExportAction export
    ) {
        this.configSource = Objects.requireNonNull(configSource, "configSource");
        this.buildSource = Objects.requireNonNull(buildSource, "buildSource");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.migration = Objects.requireNonNull(migration, "migration");
        this.export = Objects.requireNonNull(export, "export");
    }

    public static int run(String[] arguments) {
        CilExecApplication application = new CilExecApplication(
                CilExecConfig::load,
                BuildInfo::load,
                CilExecApplication::runRuntime,
                CilExecApplication::runMigration,
                CilExecApplication::runExport);
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
        CilExecConfig config = Objects.requireNonNull(configSource.get(), "config");
        return switch (command) {
            case RUNTIME -> runtime.run(config,
                    Objects.requireNonNull(buildSource.get(), "buildInfo"));
            case MIGRATE -> {
                migration.run(config);
                yield 0;
            }
            case EXPORT -> {
                export.run(config, Objects.requireNonNull(buildSource.get(), "buildInfo"),
                        ApplicationCommand.exportPath(arguments));
                yield 0;
            }
        };
    }

    private static int runRuntime(CilExecConfig config, BuildInfo buildInfo) {
        RuntimeLifecycle lifecycle = RuntimeBootstrap.assemble(config, buildInfo);
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
}
