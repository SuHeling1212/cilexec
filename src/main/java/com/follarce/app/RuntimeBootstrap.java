package com.follarce.app;

import com.follarce.application.ProcessStatementExecutor;
import com.follarce.config.CilExecConfig;
import com.follarce.effect.EffectHandler;
import com.follarce.effect.EffectHandlerRegistry;
import com.follarce.effect.EffectWorkerService;
import com.follarce.effect.BuiltinEffectHandlers;
import com.follarce.domain.port.Isolation;
import com.follarce.extension.SourceExtensionIndex;
import com.follarce.health.HealthServer;
import com.follarce.health.HealthMonitor;
import com.follarce.health.HealthState;
import com.follarce.persistence.postgres.connection.ControlLock;
import com.follarce.persistence.postgres.connection.DataSourceFactory;
import com.follarce.persistence.postgres.connection.DatabaseHealth;
import com.follarce.persistence.postgres.connection.FlywayMigrator;
import com.follarce.persistence.postgres.connection.SchemaVerifier;
import com.follarce.persistence.postgres.connection.PostgresWorkListener;
import com.follarce.persistence.postgres.repository.RecoveryCoordinator;
import com.follarce.persistence.postgres.repository.RuntimeMetadataStore;
import com.follarce.persistence.postgres.transaction.JdbcTransactionExecutor;
import com.follarce.scheduler.ClaimedProcessHandler;
import com.follarce.scheduler.SchedulerService;
import com.follarce.timer.TimerService;
import com.follarce.terminal.DatabaseTerminalControl;
import com.follarce.terminal.TerminalAccessService;
import com.follarce.terminal.TerminalBootstrap;
import com.follarce.terminal.TerminalSettings;
import com.follarce.terminal.TerminalServer;
import com.follarce.terminal.ProcessStateNotifier;
import com.follarce.terminal.TerminalProcessOutputPublisher;
import com.zaxxer.hikari.HikariDataSource;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/** Explicit production assembly for the single active Runtime. */
public final class RuntimeBootstrap {
    private RuntimeBootstrap() {
    }

    public static RuntimeLifecycle assemble(CilExecConfig config, BuildInfo buildInfo) {
        ProductionHooks hooks = new ProductionHooks(config, buildInfo, null,
                productionEffectHandlers(), null);
        return new RuntimeLifecycle(hooks, config.shutdownGrace());
    }

    public static RuntimeLifecycle assembleTerminal(CilExecConfig config, BuildInfo buildInfo,
                                                    TerminalSettings settings) {
        ProductionHooks hooks = new ProductionHooks(config, buildInfo,
                null, productionEffectHandlers(), settings);
        RuntimeLifecycle lifecycle = new RuntimeLifecycle(hooks, config.shutdownGrace());
        hooks.runtimeShutdown = () -> Thread.ofPlatform()
                .name("cilexec-administrator-shutdown")
                .start(() -> lifecycle.shutdown("administrator terminal shutdown"));
        return lifecycle;
    }

    public static RuntimeLifecycle assemble(
            CilExecConfig config,
            BuildInfo buildInfo,
            Function<JdbcTransactionExecutor, ? extends ClaimedProcessHandler> handlerFactory,
            List<? extends EffectHandler> effectHandlers
    ) {
        ProductionHooks hooks = new ProductionHooks(config, buildInfo, handlerFactory,
                effectHandlers, null);
        return new RuntimeLifecycle(hooks, config.shutdownGrace());
    }

    private static List<? extends EffectHandler> productionEffectHandlers() {
        List<EffectHandler> handlers = new java.util.ArrayList<>(
                BuiltinEffectHandlers.defaults());
        handlers.addAll(SourceExtensionIndex.catalog().effectHandlers());
        return List.copyOf(handlers);
    }

    private static final class ProductionHooks implements RuntimeLifecycle.Hooks {
        private static final int TIMER_BATCH = 100;

        private final CilExecConfig config;
        private final BuildInfo buildInfo;
        private final HealthState health = new HealthState();
        private final HikariDataSource runtimeDataSource;
        private final JdbcTransactionExecutor runtimeTransactions;
        private final ClaimedProcessHandler processHandler;
        private final List<? extends EffectHandler> effectHandlers;
        private final RuntimeMetadataStore metadata;
        private final RecoveryCoordinator recovery;
        private final TerminalSettings terminalSettings;
        private final AtomicBoolean resourcesClosed = new AtomicBoolean();
        private final ProcessStateNotifier processStateNotifier = new ProcessStateNotifier();

        private volatile Consumer<Throwable> fence;
        private volatile ControlLock control;
        private volatile RuntimeMetadataStore.BootIdentity boot;
        private volatile HealthServer healthServer;
        private volatile HealthMonitor healthMonitor;
        private volatile SchedulerService scheduler;
        private volatile PostgresWorkListener workListener;
        private volatile HikariDataSource effectDataSource;
        private volatile EffectWorkerService effectWorkers;
        private volatile TimerLoop timerLoop;
        private volatile TerminalServer terminalServer;
        private volatile Runnable runtimeShutdown = () -> {
            throw new IllegalStateException("Runtime shutdown is not available");
        };

        private ProductionHooks(
                CilExecConfig config,
                BuildInfo buildInfo,
                Function<JdbcTransactionExecutor, ? extends ClaimedProcessHandler> handlerFactory,
                List<? extends EffectHandler> effectHandlers,
                TerminalSettings terminalSettings
        ) {
            this.config = Objects.requireNonNull(config, "config");
            this.buildInfo = Objects.requireNonNull(buildInfo, "buildInfo");
            this.effectHandlers = List.copyOf(effectHandlers);
            this.terminalSettings = terminalSettings;
            health.terminalEnabled(terminalSettings != null);
            runtimeDataSource = DataSourceFactory.create(config.runtimeDatabase());
            runtimeTransactions = new JdbcTransactionExecutor(runtimeDataSource);
            processHandler = handlerFactory == null
                    ? new ProcessStatementExecutor(runtimeTransactions,
                    this::wakeScheduler, this::wakeEffects, processStateNotifier,
                    new TerminalProcessOutputPublisher())
                    : handlerFactory.apply(runtimeTransactions);
            metadata = new RuntimeMetadataStore(runtimeDataSource);
            recovery = new RecoveryCoordinator(runtimeDataSource);
        }

        @Override
        public void phase(HealthState.RuntimePhase phase) {
            health.phase(phase);
        }

        @Override
        public int verifySchema() {
            boolean available = new DatabaseHealth(runtimeDataSource).isAvailable();
            health.database(available);
            if (!available) throw new IllegalStateException("Runtime database is unavailable");
            int version = new SchemaVerifier(runtimeDataSource, buildInfo.minimumSchema(),
                    buildInfo.maximumSchema()).verify();
            health.schema(true);
            return version;
        }

        @Override
        public void acquireControl(Consumer<Throwable> onFence) {
            fence = Objects.requireNonNull(onFence, "onFence");
            control = ControlLock.acquire(config.runtimeDatabase(), config.advisoryLockKey());
            health.controlLock(true);
            control.monitor(failure -> {
                health.controlLock(false);
                health.database(false);
                fence.accept(failure);
            });
        }

        @Override
        public void migrate() {
            if (!config.migrateOnStart()) return;
            FlywayMigrator migrator = new FlywayMigrator(config.migratorDatabase());
            migrator.migrate();
            migrator.validate();
        }

        @Override
        public void beginBoot(int schemaVersion) {
            boot = metadata.beginBoot(config.instanceName(), config.advisoryLockKey(),
                    buildInfo.applicationVersion() + "+" + buildInfo.revision(), schemaVersion,
                    buildInfo.fclRuntimeFormat(),
                    Objects.requireNonNull(control, "control lock has not been acquired")
                            .identity());
        }

        @Override
        public void markRecovering() {
            metadata.markRecovering(requireBoot());
            health.recovery(false);
        }

        @Override
        public void recover() {
            recovery.recover(requireBoot().bootId(), Instant.now());
            health.recovery(true);
        }

        @Override
        public void startHealth() {
            healthServer = new HealthServer(config.healthPort(), health);
            healthServer.start();
            DatabaseHealth database = new DatabaseHealth(runtimeDataSource);
            healthMonitor = new HealthMonitor(health, database::isAvailable,
                    () -> scheduler != null && scheduler.isRunning(),
                    () -> effectWorkers != null && effectWorkers.isRunning(),
                    () -> timerLoop != null && timerLoop.isRunning(),
                    () -> workListener != null && workListener.isRunning(),
                    () -> terminalServer != null && terminalServer.isRunning(),
                    config.healthDatabaseProbeInterval());
            healthMonitor.start();
        }

        @Override
        public void startScheduler() {
            scheduler = new SchedulerService(runtimeTransactions, processHandler,
                    requireBoot().bootId(), config.schedulerWorkers(), config.leaseDuration(),
                    config.schedulerErrorBackoff(), requireFence());
            workListener = new PostgresWorkListener(config.runtimeDatabase(),
                    this::wakeScheduler, this::wakeEffects, () -> {
                        TimerLoop current = timerLoop;
                        if (current != null) current.wake();
                    }, () -> {
                        SchedulerService current = scheduler;
                        if (current != null) current.wakeInterrupt();
                    }, requireFence());
            workListener.start();
            health.workListener(true);
            scheduler.start();
            health.schedulerLoop(true);
        }

        @Override
        public void startEffectWorkers() {
            effectDataSource = DataSourceFactory.create(config.effectDatabase());
            JdbcTransactionExecutor effectTransactions =
                    new JdbcTransactionExecutor(effectDataSource);
            effectWorkers = new EffectWorkerService(effectTransactions, runtimeTransactions,
                    requireBoot().bootId(), new EffectHandlerRegistry(effectHandlers), config.effectWorkers(),
                    config.effectErrorBackoff(), Clock.systemUTC(), requireFence(),
                    this::wakeScheduler);
            effectWorkers.start();
            health.effectWorkers(true);
        }

        @Override
        public void startTimerLoop() {
            TimerService timers = new TimerService(runtimeTransactions, runtimeTransactions,
                    Clock.systemUTC());
            DeliverySweeper sweeper = new DeliverySweeper(runtimeTransactions,
                    Clock.systemUTC());
            UUID runnerId = UUID.randomUUID();
            timerLoop = new TimerLoop(() -> {
                int released = runtimeTransactions.inTransaction(
                        com.follarce.domain.port.Isolation.READ_COMMITTED,
                        transaction -> transaction.scheduler().releaseExpired(Instant.now()));
                int fired = timers.fireDue(runnerId, TIMER_BATCH);
                int swept = sweeper.sweepOnce();
                SchedulerService current = scheduler;
                if (current != null) {
                    current.wake();
                    current.wakeInterrupt();
                }
                return released + fired + swept;
            }, () -> 0, this::nextMaintenanceAt, requireFence());
            timerLoop.start();
            health.timerLoop(true);
        }

        private Optional<Instant> nextMaintenanceAt() {
            return runtimeTransactions.inTransaction(Isolation.READ_COMMITTED, transaction ->
                    Stream.of(transaction.timers().nextScheduledWakeAt(),
                                    transaction.scheduler().nextLeaseExpiry(),
                                    transaction.scheduler().nextReadyAt())
                            .flatMap(Optional::stream)
                            .min(Instant::compareTo));
        }

        private void wakeScheduler() {
            SchedulerService current = scheduler;
            if (current != null) current.wake();
        }

        private void wakeEffects() {
            EffectWorkerService current = effectWorkers;
            if (current != null) current.wake();
        }

        @Override
        public void markReady() {
            metadata.markReady(requireBoot());
            if (terminalSettings != null) startTerminalServer();
        }

        private void startTerminalServer() {
            Clock clock = Clock.systemUTC();
            new TerminalBootstrap(runtimeTransactions, clock).ensure(terminalSettings)
                    .ifPresent(_ -> {});
            var access = new TerminalAccessService(runtimeTransactions,
                    config.runtimeDatabase().jdbcUrl(), clock, terminalSettings.username());
            terminalServer = new TerminalServer(terminalSettings.port(), access,
                    (account, contextId) -> contextId.isEmpty()
                            ? new DatabaseTerminalControl(runtimeTransactions, account,
                            runtimeShutdown, password -> access.login(account.username(), password).isPresent(),
                            this::wakeScheduler, () -> {
                                SchedulerService current = scheduler;
                                if (current != null) current.wakeInterrupt();
                            }, processStateNotifier)
                            : DatabaseTerminalControl.interactive(runtimeTransactions, account, contextId,
                            runtimeShutdown, password -> access.login(account.username(), password).isPresent(),
                            this::wakeScheduler, () -> {
                                SchedulerService current = scheduler;
                                if (current != null) current.wakeInterrupt();
                            }, processStateNotifier),
                    (account, contextId) -> DatabaseTerminalControl.headless(
                            runtimeTransactions, account, contextId, runtimeShutdown,
                            password -> access.login(account.username(), password).isPresent(),
                            this::wakeScheduler, () -> {
                                SchedulerService current = scheduler;
                                if (current != null) current.wakeInterrupt();
                            }, processStateNotifier),
                    terminalSettings.username());
            terminalServer.start();
            health.terminalServer(true);
        }

        @Override
        public synchronized void stopScheduler() {
            health.schedulerLoop(false);
            health.workListener(false);
            if (processHandler instanceof ProcessStatementExecutor durableExecutor) {
                durableExecutor.closeVolatileProcesses();
            }
            if (workListener != null) {
                workListener.close();
                workListener = null;
            }
            if (scheduler != null) {
                scheduler.close();
                scheduler = null;
            }
        }

        @Override
        public synchronized void stopEffectWorkers() {
            health.effectWorkers(false);
            if (effectWorkers != null) {
                effectWorkers.close();
                effectWorkers = null;
            }
        }

        @Override
        public synchronized void stopTimerLoop() {
            health.timerLoop(false);
            if (timerLoop != null) {
                timerLoop.close();
                timerLoop = null;
            }
        }

        @Override
        public synchronized void stopHealth() {
            if (healthMonitor != null) {
                healthMonitor.close();
                healthMonitor = null;
            }
            health.terminalServer(false);
            if (terminalServer != null) {
                terminalServer.close();
                terminalServer = null;
            }
            if (healthServer != null) {
                healthServer.close();
                healthServer = null;
            }
        }

        @Override
        public void markClean(String reason) {
            metadata.markClean(requireBoot(), reason);
        }

        @Override
        public void markFenced(String reason) {
            metadata.markFenced(requireBoot(), reason);
        }

        @Override
        public synchronized void releaseControl() {
            health.controlLock(false);
            if (control != null) {
                control.close();
                control = null;
            }
        }

        @Override
        public void closeResources() {
            if (!resourcesClosed.compareAndSet(false, true)) return;
            HikariDataSource effects = effectDataSource;
            if (effects != null) effects.close();
            runtimeDataSource.close();
            health.database(false);
        }

        @Override
        public void forceClose() {
            stopScheduler();
            stopEffectWorkers();
            stopTimerLoop();
            stopHealth();
            releaseControl();
            closeResources();
        }

        private RuntimeMetadataStore.BootIdentity requireBoot() {
            return Objects.requireNonNull(boot, "boot metadata has not been created");
        }

        private Consumer<Throwable> requireFence() {
            return Objects.requireNonNull(fence, "control lock has not been acquired");
        }
    }
}
